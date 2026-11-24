package com.vanipy.tengok;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF viewer on Android's own PdfRenderer: no library, no network. One page
 * at a time with previous/next and zoom steps. Pages are drawn to bitmaps;
 * there is no text selection, search, forms or annotations.
 */
public final class PdfPanel {
    private static final float[] ZOOMS = {1f, 1.5f, 2f, 3f};
    private static final long MAX_PIXELS = 12_000_000L;

    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ParcelFileDescriptor fd;
    private PdfRenderer renderer;
    private boolean released;
    private int page, pageCount, zoomIndex;
    private int renderToken;
    private Bitmap shown;
    private ImageView image;
    private TextView label;
    private TextView status;
    private Context ctx;
    private int viewWidth;

    /** Opens the file; throws IOException with a readable message when it can't be shown. */
    public View build(Context context, Source source) throws IOException {
        this.ctx = context;
        ParcelFileDescriptor opened = source.openFd();
        if (opened == null) {
            throw new IOException("a PDF needs a seekable file, which this source cannot give");
        }
        try {
            renderer = new PdfRenderer(opened);
        } catch (SecurityException e) {
            closeQuietly(opened);
            throw new IOException("this PDF is password protected; tengok does not open protected PDFs");
        } catch (IOException | RuntimeException e) {
            closeQuietly(opened);
            throw new IOException("this PDF is damaged or uses a feature the phone's PDF engine cannot read"
                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
        }
        fd = opened;
        pageCount = renderer.getPageCount();
        if (pageCount <= 0) {
            release();
            throw new IOException("this PDF has no pages");
        }
        viewWidth = Math.max(320, context.getResources().getDisplayMetrics().widthPixels - Ui.dp(context, 24));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = Ui.row(context);
        bar.setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4));
        Button prev = small("‹ Prev");
        Button next = small("Next ›");
        Button zoomOut = small("−");
        Button zoomIn = small("+");
        label = new TextView(context);
        label.setTextSize(15);
        label.setTextColor(0xFF1B2340);
        label.setGravity(android.view.Gravity.CENTER);
        bar.addView(prev);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(next);
        bar.addView(zoomOut);
        bar.addView(zoomIn);
        root.addView(bar);
        status = Ui.muted(context, "Page view only. No text selection, search, forms or annotations.");
        status.setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), Ui.dp(context, 4));
        root.addView(status);

        image = new ImageView(context);
        image.setBackgroundColor(Color.WHITE);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setContentDescription("PDF page");
        HorizontalScrollView h = new HorizontalScrollView(context);
        h.addView(image);
        ScrollView v = new ScrollView(context);
        v.setFillViewport(false);
        v.addView(h);
        v.setBackgroundColor(0xFFDDE0EA);
        root.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        prev.setOnClickListener(x -> go(page - 1));
        next.setOnClickListener(x -> go(page + 1));
        zoomOut.setOnClickListener(x -> zoom(zoomIndex - 1));
        zoomIn.setOnClickListener(x -> zoom(zoomIndex + 1));
        go(0);
        return root;
    }

    private Button small(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(Ui.dp(ctx, 56));
        return b;
    }

    private void go(int p) {
        if (p < 0 || p >= pageCount) return;
        page = p;
        draw();
    }

    private void zoom(int z) {
        if (z < 0 || z >= ZOOMS.length) return;
        zoomIndex = z;
        draw();
    }

    private void draw() {
        label.setText("Page " + (page + 1) + " of " + pageCount + " · " + Math.round(ZOOMS[zoomIndex] * 100) + "%");
        final int token = ++renderToken;
        final int pageNow = page;
        final float zoomNow = ZOOMS[zoomIndex];
        worker.execute(() -> {
            Bitmap bmp = null;
            String error = null;
            synchronized (lock) {
                if (released || token != renderToken) return;
                try (PdfRenderer.Page p = renderer.openPage(pageNow)) {
                    float pw = Math.max(1, p.getWidth()), ph = Math.max(1, p.getHeight());
                    float scale = viewWidth * zoomNow / pw;
                    long px = (long) (pw * scale) * (long) (ph * scale);
                    if (px > MAX_PIXELS) scale *= (float) Math.sqrt((double) MAX_PIXELS / px);
                    int bw = Math.max(1, Math.min(4096, Math.round(pw * scale)));
                    int bh = Math.max(1, Math.min(8192, Math.round(ph * scale)));
                    bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (RuntimeException | OutOfMemoryError e) {
                    error = "tengok couldn't draw this page (" + e.getClass().getSimpleName() + ")";
                    bmp = null;
                }
            }
            final Bitmap result = bmp;
            final String err = error;
            image.post(() -> {
                if (released || token != renderToken) return;
                if (result != null) {
                    // exact pixel size: the bitmap's density must not rescale it past the screen
                    image.setLayoutParams(new ViewGroup.LayoutParams(result.getWidth(), result.getHeight()));
                    image.setImageBitmap(result);
                    Bitmap old = shown;
                    shown = result;
                    if (old != null && old != result) old.recycle();
                    status.setText("Page view only. No text selection, search, forms or annotations.");
                } else {
                    status.setText(err == null ? "tengok couldn't draw this page" : err);
                }
            });
        });
    }

    public void release() {
        synchronized (lock) {
            released = true;
            renderToken++;
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (RuntimeException ignored) {
                }
                renderer = null;
            }
            closeQuietly(fd);
            fd = null;
        }
        worker.shutdown();
    }

    private static void closeQuietly(ParcelFileDescriptor f) {
        if (f == null) return;
        try {
            f.close();
        } catch (IOException ignored) {
        }
    }
}
