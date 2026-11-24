package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.IOException;

/**
 * Basic SVG viewer: draws the shapes, paths and plain text that SvgModel
 * understands onto a bitmap and shows it with pinch zoom. Anything it skips
 * (CSS, gradients, filters, clips, images, scripts) is listed on screen, and
 * the source text is one tap away.
 */
public final class SvgPanel {
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_SIDE = 2048;

    private SvgPanel() {
    }

    public static View build(Context ctx, Source source) {
        FrameLayout host = new FrameLayout(ctx);
        LinearLayout column = Ui.column(ctx);
        LinearLayout card = Ui.card(ctx);
        byte[] data;
        try {
            data = source.readAll(MAX_BYTES);
        } catch (IOException e) {
            card.addView(Ui.line(ctx, "tengok can't read this SVG"));
            card.addView(Ui.muted(ctx, e.getMessage() + " (SVG preview is limited to 2 MB.)"));
            column.addView(card);
            return scroll(ctx, column);
        }
        SvgModel model;
        try {
            model = SvgModel.parse(data);
        } catch (IOException e) {
            card.addView(Ui.line(ctx, "tengok can't draw this SVG"));
            card.addView(Ui.muted(ctx, e.getMessage()));
            addSourceButton(ctx, card, host, column, source);
            column.addView(card);
            return scroll(ctx, column);
        }
        Bitmap bmp = render(model);
        card.addView(Ui.line(ctx, "SVG preview"));
        card.addView(Ui.muted(ctx, Math.round(model.width) + " × " + Math.round(model.height) + " · "
                + model.items.size() + " shapes and text runs drawn"
                + (model.truncated ? " · stopped at the safety limit" : "")));
        if (!model.skipped.isEmpty()) {
            card.addView(Ui.muted(ctx, "Not drawn: " + String.join(", ", model.skipped)
                    + ". This is a basic preview, not a full SVG renderer."));
        } else {
            card.addView(Ui.muted(ctx, "Basic preview: shapes, paths and plain text. Pinch to zoom, double-tap to enlarge."));
        }
        ImagePanel.ZoomImageView view = new ImagePanel.ZoomImageView(ctx);
        view.setImage(bmp);
        card.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 360)));
        addSourceButton(ctx, card, host, column, source);
        column.addView(card);
        return scroll(ctx, column);
    }

    private static void addSourceButton(Context ctx, LinearLayout card, FrameLayout host, LinearLayout column, Source source) {
        Button b = new Button(ctx);
        b.setText("Show SVG source");
        b.setOnClickListener(v -> {
            try {
                TextPanel.Result r = TextPanel.decode(source, MAX_BYTES);
                android.widget.TextView t = new android.widget.TextView(ctx);
                t.setText(r.text);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(13);
                t.setTextIsSelectable(true);
                t.setPadding(0, Ui.dp(ctx, 8), 0, 0);
                card.addView(t);
                b.setVisibility(View.GONE);
            } catch (IOException e) {
                b.setText("Cannot read the source: " + e.getMessage());
            }
        });
        card.addView(b);
    }

    private static View scroll(Context ctx, LinearLayout column) {
        ScrollView s = new ScrollView(ctx);
        s.addView(column);
        return s;
    }

    /** Draws the model on a white bitmap, long side about 1600 px (never above 2048). */
    static Bitmap render(SvgModel m) {
        float w = Math.max(1, m.width), h = Math.max(1, m.height);
        float scale = Math.min(1600f / Math.max(w, h), MAX_SIDE / Math.max(w, h));
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0xFFFFFFFF);
        canvas.scale(scale, scale);
        if (m.hasViewBox) {
            float s = Math.min(w / m.vbW, h / m.vbH);
            canvas.translate((w - m.vbW * s) / 2f, (h - m.vbH * s) / 2f);
            canvas.scale(s, s);
            canvas.translate(-m.vbX, -m.vbY);
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Matrix mat = new Matrix();
        for (SvgModel.Item it : m.items) {
            canvas.save();
            float[] a = it.matrix;
            mat.setValues(new float[]{a[0], a[2], a[4], a[1], a[3], a[5], 0, 0, 1});
            canvas.concat(mat);
            if (it.text) {
                drawText(canvas, paint, it);
            } else {
                drawShape(canvas, paint, it);
            }
            canvas.restore();
        }
        return bmp;
    }

    private static void drawShape(Canvas canvas, Paint paint, SvgModel.Item it) {
        Path path = new Path();
        path.setFillType(it.evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        int c = 0;
        for (int op : it.ops) {
            switch (op) {
                case SvgModel.M:
                    path.moveTo(it.coords[c], it.coords[c + 1]);
                    c += 2;
                    break;
                case SvgModel.L:
                    path.lineTo(it.coords[c], it.coords[c + 1]);
                    c += 2;
                    break;
                case SvgModel.C:
                    path.cubicTo(it.coords[c], it.coords[c + 1], it.coords[c + 2], it.coords[c + 3], it.coords[c + 4], it.coords[c + 5]);
                    c += 6;
                    break;
                case SvgModel.Q:
                    path.quadTo(it.coords[c], it.coords[c + 1], it.coords[c + 2], it.coords[c + 3]);
                    c += 4;
                    break;
                default:
                    path.close();
            }
        }
        if ((it.fill >>> 24) != 0) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(it.fill);
            canvas.drawPath(path, paint);
        }
        if ((it.stroke >>> 24) != 0 && it.strokeWidth > 0) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(it.stroke);
            paint.setStrokeWidth(it.strokeWidth);
            paint.setStrokeCap(it.cap == 1 ? Paint.Cap.ROUND : it.cap == 2 ? Paint.Cap.SQUARE : Paint.Cap.BUTT);
            paint.setStrokeJoin(it.join == 1 ? Paint.Join.ROUND : it.join == 2 ? Paint.Join.BEVEL : Paint.Join.MITER);
            canvas.drawPath(path, paint);
        }
    }

    private static void drawText(Canvas canvas, Paint paint, SvgModel.Item it) {
        if ((it.fill >>> 24) == 0) return;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(it.fill);
        paint.setTextSize(it.fontSize);
        int style = (it.bold ? Typeface.BOLD : 0) | (it.italic ? Typeface.ITALIC : 0);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, style));
        paint.setTextAlign(it.anchor == 1 ? Paint.Align.CENTER : it.anchor == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(it.content, it.x, it.y, paint);
    }
}
