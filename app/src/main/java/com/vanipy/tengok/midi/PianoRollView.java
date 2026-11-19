package com.vanipy.tengok.midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A simple piano roll: time runs left to right, pitch bottom to top, notes
 * are colored by channel. A dark line marks the playback position. Rows
 * shrink to fit the panel height, so a wide pitch range still fits.
 */
public class PianoRollView extends View {
    private static final int[] CHANNEL_COLORS = {
            0xFF3E6FB0, 0xFF4CAF50, 0xFFE8A33D, 0xFFAB47BC,
            0xFFEF5350, 0xFF26A69A, 0xFF8D6E63, 0xFF78909C,
            0xFFD4E157, 0xFF5C6BC0, 0xFFFF7043, 0xFF9CCC65,
            0xFF7E57C2, 0xFF26C6DA, 0xFFEC407A, 0xFFA1887F};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private MidiFile midi;
    private int minPitch = 48;
    private int maxPitch = 72;
    private double cursorSec = -1;
    private float pxPerSec;
    private float rowH = 12;
    private int panelHeightPx = 100;
    private int widthPx = 100;

    public PianoRollView(Context context) {
        super(context);
        setContentDescription("piano roll");
    }

    public void setMidi(MidiFile m, int panelHeightPx) {
        this.midi = m;
        this.panelHeightPx = panelHeightPx;
        float density = getResources().getDisplayMetrics().density;
        int lo = 127, hi = 0;
        for (MidiFile.Note n : m.notes) {
            if (n.pitch < lo) lo = n.pitch;
            if (n.pitch > hi) hi = n.pitch;
        }
        if (lo <= hi) {
            minPitch = Math.max(0, lo - 2);
            maxPitch = Math.min(127, hi + 2);
        }
        int rows = maxPitch - minPitch + 1;
        rowH = Math.max(4 * density, Math.min(14 * density, (float) panelHeightPx / rows));
        pxPerSec = 100 * density;
        double dur = Math.max(1.0, m.durationSeconds);
        widthPx = (int) (dur * pxPerSec) + (int) (24 * density);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(widthPx, panelHeightPx);
    }

    public void setCursor(double sec) {
        cursorSec = sec;
        invalidate();
    }

    /** X pixel of the playback cursor inside this view, or -1 when hidden. */
    public float cursorX() {
        return cursorSec < 0 ? -1f : (float) (cursorSec * pxPerSec);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (midi == null) return;
        int w = getWidth();
        int h = getHeight();
        int rows = maxPitch - minPitch + 1;
        float usedH = rows * rowH;
        float top = (h - usedH) / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRect(0, 0, w, h, paint);

        for (int i = 0; i < rows; i++) {
            int pitch = minPitch + i;
            float y = top + (rows - 1 - i) * rowH;
            paint.setColor(pitch % 12 == 0 ? 0xFFE0E4F0 : 0xFFF4F5F9);
            canvas.drawRect(0, y, w, y + rowH, paint);
        }

        double dur = Math.max(1.0, midi.durationSeconds);
        paint.setColor(0xFFE4E6EF);
        paint.setStrokeWidth(1);
        for (int s = 0; s <= (int) dur + 1; s++) {
            float x = s * pxPerSec;
            canvas.drawLine(x, 0, x, h, paint);
        }

        for (MidiFile.Note n : midi.notes) {
            float x0 = (float) (n.startSec * pxPerSec);
            float x1 = (float) (Math.max(n.endSec, n.startSec + 0.03) * pxPerSec);
            float y1 = top + (maxPitch - n.pitch + 1) * rowH;
            float y0 = y1 - rowH + 1;
            paint.setColor(CHANNEL_COLORS[n.channel & 15]);
            rect.set(x0, y0, x1, y1 - 1);
            canvas.drawRoundRect(rect, rowH * 0.3f, rowH * 0.3f, paint);
        }

        if (cursorSec >= 0) {
            paint.setColor(0xFF1F2A5C);
            paint.setStrokeWidth(3);
            float x = (float) (cursorSec * pxPerSec);
            canvas.drawLine(x, 0, x, h, paint);
        }
    }
}
