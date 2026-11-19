package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.IOException;
import java.io.InputStream;

/**
 * Image screen using the platform decoders (PNG, JPEG, WebP, GIF, BMP, and
 * HEIC/AVIF where the Android version has them). Bitmaps are downsampled so
 * a giant photo cannot exhaust memory. Pinch to zoom, drag to pan; animated
 * GIFs play without zoom.
 */
public class ImagePanel {

    public static class Decoded {
        public Bitmap bitmap;
        public Drawable animated;
        public int width;
        public int height;
        public int sample;
    }

    public static Decoded decode(Source source, int maxDim) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = source.open()) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("the image data could not be decoded");
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = source.open()) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("the image data could not be decoded");
        Decoded d = new Decoded();
        d.bitmap = bmp;
        d.width = bounds.outWidth;
        d.height = bounds.outHeight;
        d.sample = sample;
        return d;
    }

    public static boolean isGif(String name, byte[] head) {
        if (head != null && head.length >= 3 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return true;
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
    }

    public static View build(Context ctx, Source source, byte[] head) {
        LinearLayout column = Ui.column(ctx);
        // Animated GIF goes through ImageDecoder so frames keep moving.
        if (android.os.Build.VERSION.SDK_INT >= 28 && isGif(source.name, head)) {
            try {
                android.graphics.ImageDecoder.Source src = null;
                if (source.uri() != null) {
                    src = android.graphics.ImageDecoder.createSource(ctx.getContentResolver(), source.uri());
                } else if (source.localFile() != null) {
                    src = android.graphics.ImageDecoder.createSource(source.localFile());
                }
                if (src != null) {
                    Drawable drawable = android.graphics.ImageDecoder.decodeDrawable(src);
                    LinearLayout card = Ui.card(ctx);
                    if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        ((android.graphics.drawable.AnimatedImageDrawable) drawable).start();
                        card.addView(Ui.muted(ctx, "animated GIF · tap and drag in other viewers is not available for animations"));
                    } else {
                        card.addView(Ui.muted(ctx, "GIF image"));
                    }
                    ImageView iv = new ImageView(ctx);
                    iv.setImageDrawable(drawable);
                    iv.setAdjustViewBounds(true);
                    card.addView(iv);
                    column.addView(card);
                    ScrollView scroll = new ScrollView(ctx);
                    scroll.addView(column);
                    return scroll;
                }
            } catch (IOException ignored) {
                // fall through to the still decoder below
            }
        }
        LinearLayout card = Ui.card(ctx);
        try {
            Decoded d = decode(source, 2048);
            ZoomImageView view = new ZoomImageView(ctx);
            view.setImage(d.bitmap);
            card.addView(Ui.muted(ctx, d.width + " × " + d.height
                    + (d.sample > 1 ? " · shown at 1/" + d.sample + " to save memory" : "")));
            card.addView(view, new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 360)));
        } catch (IOException e) {
            card.addView(Ui.line(ctx, "tengok can't show this image"));
            card.addView(Ui.muted(ctx, e.getMessage()));
        }
        column.addView(card);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(column);
        return scroll;
    }

    /** A bitmap view with pinch zoom and drag pan. */
    static class ZoomImageView extends View {
        private Bitmap bitmap;
        private final Matrix matrix = new Matrix();
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private float scale = 1f;
        private float tx, ty;
        private final GestureDetector gestures;
        private final ScaleGestureDetector scaler;

        ZoomImageView(Context ctx) {
            super(ctx);
            setContentDescription("image, pinch to zoom");
            gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    tx -= dx;
                    ty -= dy;
                    invalidate();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    scale = scale > 1.5f ? 1f : scale * 2f;
                    invalidate();
                    return true;
                }
            });
            scaler = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scale *= detector.getScaleFactor();
                    scale = Math.max(0.5f, Math.min(scale, 12f));
                    invalidate();
                    return true;
                }
            });
        }

        void setImage(Bitmap b) {
            bitmap = b;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            scaler.onTouchEvent(event);
            gestures.onTouchEvent(event);
            return true;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float base = Math.min(getWidth() / bw, getHeight() / bh);
            matrix.reset();
            float s = base * scale;
            matrix.setScale(s, s);
            matrix.postTranslate((getWidth() - bw * s) / 2f + tx, (getHeight() - bh * s) / 2f + ty);
            canvas.drawBitmap(bitmap, matrix, paint);
        }
    }
}
