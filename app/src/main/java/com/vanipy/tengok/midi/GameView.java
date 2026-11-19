package com.vanipy.tengok.midi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The falling-notes game view. Notes of the player's track drop toward an
 * on-screen keyboard; the player taps the matching key as each note lands
 * (or plays it on a connected MIDI keyboard - same strike path). Velocity
 * shades the notes, timings stay exactly as written, and nothing is
 * quantized: a gently rolled chord arrives rolled.
 */
public final class GameView extends View {
    private static final String TAG = "tengok";
    private static final int[] BLACK = {1, 3, 6, 8, 10};
    private static final int[] CHANNEL_COLORS = {
            0xFF3F6FD8, 0xFF3DA35D, 0xFFD8913F, 0xFF9B59D0,
            0xFFD84848, 0xFF3FA8A0, 0xFFC45BA8, 0xFF8A8F3D,
            0xFF6E7FD8, 0xFF4D9E4D, 0xFFB87333, 0xFF7A5CD0,
            0xFFD05858, 0xFF2F9E8E, 0xFFB84FA0, 0xFF999933};

    public interface StrikeListener {
        void onStrike(int pitch);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<MidiFile.Note> notes = new ArrayList<>();
    private GameScoring scoring;
    private StrikeListener listener;

    private int loPitch = 48;
    private int hiPitch = 72;
    private double nowMs;
    private double pxPerSec;
    private int keyboardH;
    private int keysLoggedW = -1;
    private int keysLoggedH = -1;
    private int keysLoggedX = -1;
    private int keysLoggedY = -1;

    public GameView(Context ctx) {
        super(ctx);
    }

    public void setSong(MidiFile midi, GameScoring scoring, int track) {
        this.scoring = scoring;
        notes.clear();
        int lo = 127, hi = 0;
        for (MidiFile.Note n : midi.notes) {
            if (n.track != track) continue;
            notes.add(n);
            if (n.pitch < lo) lo = n.pitch;
            if (n.pitch > hi) hi = n.pitch;
        }
        if (lo > hi) { lo = 60; hi = 72; }
        // pad to whole octaves so the keyboard starts and ends on C
        loPitch = Math.max(0, (lo / 12) * 12 - 12);
        hiPitch = Math.min(127, ((hi / 12) + 2) * 12 - 1);
        keysLoggedW = -1;
        invalidate();
    }

    public void setStrikeListener(StrikeListener l) {
        listener = l;
    }

    public void setNow(double ms) {
        nowMs = ms;
        invalidate();
    }

    private static boolean isBlack(int pitch) {
        int s = pitch % 12;
        for (int b : BLACK) if (s == b) return true;
        return false;
    }

    private static int whitesUpTo(int lo, int pitchInclusive) {
        int c = 0;
        for (int p = lo; p <= pitchInclusive; p++) if (!isBlack(p)) c++;
        return c;
    }

    /** Horizontal rect of a key, in view coordinates. */
    RectF keyRect(int pitch) {
        int w = getWidth();
        int whiteCount = whitesUpTo(loPitch, hiPitch);
        float whiteW = (float) w / whiteCount;
        float top = getHeight() - keyboardH;
        if (!isBlack(pitch)) {
            int idx = whitesUpTo(loPitch, pitch) - 1;
            return new RectF(idx * whiteW, top, (idx + 1) * whiteW, getHeight());
        }
        // black key sits on the boundary after its preceding white key
        int before = whitesUpTo(loPitch, pitch - 1);
        float cx = before * whiteW;
        float bw = whiteW * 0.62f;
        return new RectF(cx - bw / 2, top, cx + bw / 2, top + keyboardH * 0.6f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        keyboardH = (int) (h * 0.18f);
        pxPerSec = (h - keyboardH) / 3.0; // ~3 seconds of lookahead
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float hitY = h - keyboardH;

        paint.setColor(0xFFFBFBFE);
        canvas.drawRect(0, 0, w, h, paint);

        // falling notes of the player's track, velocity-shaded like the viewer
        for (MidiFile.Note n : notes) {
            double y1 = hitY - (n.startSec * 1000.0 - nowMs) / 1000.0 * pxPerSec;
            double y0 = hitY - (n.endSec * 1000.0 - nowMs) / 1000.0 * pxPerSec;
            if (y1 < 0 || y0 > hitY) continue;
            RectF kr = keyRect(n.pitch);
            paint.setColor(CHANNEL_COLORS[n.channel & 15]);
            paint.setAlpha(80 + (n.velocity * 175 / 127));
            rect.set(kr.left + 1, (float) Math.max(0, y0), kr.right - 1, (float) Math.min(hitY, y1));
            canvas.drawRoundRect(rect, 6, 6, paint);
        }
        paint.setAlpha(255);

        // hit line
        paint.setColor(0xFF1F2A5C);
        canvas.drawRect(0, hitY - 2, w, hitY, paint);

        // keyboard: whites, then blacks on top
        int whiteCount = whitesUpTo(loPitch, hiPitch);
        float whiteW = (float) w / whiteCount;
        for (int p = loPitch; p <= hiPitch; p++) {
            if (isBlack(p)) continue;
            RectF kr = keyRect(p);
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(kr, paint);
            paint.setColor(0xFFB9BECB);
            canvas.drawRect(kr.left, kr.top, kr.left + 1, kr.bottom, paint);
        }
        for (int p = loPitch; p <= hiPitch; p++) {
            if (!isBlack(p)) continue;
            RectF kr = keyRect(p);
            paint.setColor(0xFF22242E);
            canvas.drawRect(kr, paint);
        }

        // judged-note feedback: brief tint over the key area is handled by alpha notes;
        // scoring readout lives in the panel text under this view.

        int[] loc = new int[2];
        getLocationOnScreen(loc);
        if (w > 0 && (w != keysLoggedW || h != keysLoggedH
                || loc[0] != keysLoggedX || loc[1] != keysLoggedY)) {
            keysLoggedW = w;
            keysLoggedH = h;
            keysLoggedX = loc[0];
            keysLoggedY = loc[1];
            StringBuilder sb = new StringBuilder("TENGOK-GAME-KEYS");
            for (int p = loPitch; p <= hiPitch; p++) {
                RectF kr = keyRect(p);
                sb.append(' ').append(p).append(':')
                        .append((int) (loc[0] + kr.left)).append(',')
                        .append((int) (loc[0] + kr.right)).append(',')
                        .append((int) (loc[1] + (kr.top + kr.bottom) / 2));
            }
            Log.i(TAG, sb.toString());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX();
        float y = e.getY();
        if (y < getHeight() - keyboardH) return true;
        // black keys win over whites where they overlap
        for (int p = loPitch; p <= hiPitch; p++) {
            if (!isBlack(p)) continue;
            if (keyRect(p).contains(x, y)) {
                strike(p);
                return true;
            }
        }
        for (int p = loPitch; p <= hiPitch; p++) {
            if (isBlack(p)) continue;
            if (keyRect(p).contains(x, y)) {
                strike(p);
                return true;
            }
        }
        return true;
    }

    private void strike(int pitch) {
        Log.i(TAG, "TENGOK-GAME-TAP pitch=" + pitch);
        if (listener != null) listener.onStrike(pitch);
    }
}
