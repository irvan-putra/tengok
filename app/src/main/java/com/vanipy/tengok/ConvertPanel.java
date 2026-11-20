package com.vanipy.tengok;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The Convert card on the viewer screen. One row per target from
 * Convert.targetsFor; a tap asks where the new file should go (Storage
 * Access Framework, so no storage permission), then converts offline.
 * Images use the platform Bitmap codecs, text is a charset re-encode, and
 * audio/video go through Media3 Transformer, which drives the phone's own
 * hardware codecs. The original is only ever read.
 */
public class ConvertPanel {
    public static final int REQ_CREATE = 4211;

    private final Activity host;
    private final Source source;
    private final Sniffer.Kind kind;
    private final byte[] head;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout card;
    private android.widget.TextView status;
    private Convert.Target pending;
    private Transformer transformer;
    private File tmpOut;

    public ConvertPanel(Activity host, Source source, Sniffer.Kind kind, byte[] head) {
        this.host = host;
        this.source = source;
        this.kind = kind;
        this.head = head;
    }

    /** The card view, or null when this kind has nothing to convert to. */
    public View build() {
        java.util.List<Convert.Target> targets = Convert.targetsFor(kind);
        if (targets.isEmpty()) return null;
        card = Ui.card(host);
        card.addView(Ui.line(host, "Convert"));
        card.addView(Ui.muted(host,
                "Offline, on this phone: the original stays untouched and you pick where "
                + "the new file goes."));
        if (kind == Sniffer.Kind.IMAGE && head != null && head.length >= 3
                && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
            card.addView(Ui.muted(host,
                    "This is a GIF; if it is animated, only the first frame is converted."));
        }
        for (Convert.Target t : targets) {
            Button b = new Button(host);
            b.setText("Save as " + t.label);
            b.setOnClickListener(v -> askDestination(t));
            card.addView(b);
            card.addView(Ui.muted(host, t.note));
        }
        status = Ui.muted(host, "");
        card.addView(status);
        return card;
    }

    private void askDestination(Convert.Target t) {
        pending = t;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(t.mime);
        i.putExtra(Intent.EXTRA_TITLE, Convert.outName(source.name, t.ext));
        host.startActivityForResult(i, REQ_CREATE);
    }

    /** ViewerActivity forwards onActivityResult here. */
    public void onDestination(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CREATE) return;
        Convert.Target t = pending;
        pending = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null
                || t == null) {
            say("");
            return;
        }
        Uri dest = data.getData();
        say("Converting...");
        switch (kind) {
            case IMAGE:
            case TEXT:
                new Thread(() -> convertLocal(t, dest), "tengok-convert").start();
                break;
            case AUDIO:
            case VIDEO:
                convertWithTransformer(t, dest);
                break;
            default:
                say("This file has no conversions.");
        }
    }

    /** Images and text convert directly into the picked destination. */
    private void convertLocal(Convert.Target t, Uri dest) {
        try (OutputStream out = host.getContentResolver().openOutputStream(dest)) {
            if (out == null) throw new IOException("the destination would not open");
            if (kind == Sniffer.Kind.IMAGE) {
                writeImage(t, out);
            } else {
                TextPanel.Result r = TextPanel.decode(source, Source.MAX_BUFFERED_BYTES);
                out.write(Convert.reencodeText(r.text, t.id));
            }
            done("Saved " + Convert.outName(source.name, t.ext) + ".");
        } catch (OutOfMemoryError e) {
            done("Could not convert: the image is too large for this phone's memory.");
        } catch (Exception e) {
            done("Could not convert: " + e.getMessage());
        }
    }

    private void writeImage(Convert.Target t, OutputStream out) throws IOException {
        ImagePanel.Decoded d = ImagePanel.decode(source, Integer.MAX_VALUE);
        Bitmap bmp = d.bitmap;
        Bitmap.CompressFormat fmt;
        int quality = 100;
        switch (t.id) {
            case "jpeg":
                fmt = Bitmap.CompressFormat.JPEG;
                quality = 92;
                // JPEG has no alpha: flatten onto white first.
                Bitmap flat = Bitmap.createBitmap(
                        bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(flat);
                c.drawColor(0xFFFFFFFF);
                c.drawBitmap(bmp, 0, 0, null);
                bmp = flat;
                break;
            case "webp":
                fmt = Build.VERSION.SDK_INT >= 30
                        ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
                quality = 95;
                break;
            default:
                fmt = Bitmap.CompressFormat.PNG;
        }
        if (!bmp.compress(fmt, quality, out)) {
            throw new IOException("the " + t.id + " encoder refused the image");
        }
    }

    /**
     * Audio and video go through Media3 Transformer on the phone's own
     * codecs. Transformer writes a real file, so the export lands in the
     * cache first and is then copied to the picked destination.
     */
    private void convertWithTransformer(Convert.Target t, Uri dest) {
        cancelRunning();
        tmpOut = new File(host.getCacheDir(), "convert-out." + t.ext);
        //noinspection ResultOfMethodCallIgnored
        tmpOut.delete();
        transformer = new Transformer.Builder(host)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        copyTmpTo(t, dest);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result,
                                        ExportException exception) {
                        cleanupTmp();
                        done("Could not convert: " + exception.getMessage());
                    }
                })
                .build();
        Uri in = source.uri() != null ? source.uri() : Uri.fromFile(source.localFile());
        transformer.start(new EditedMediaItem.Builder(MediaItem.fromUri(in)).build(),
                tmpOut.getAbsolutePath());
        pollProgress();
    }

    private final Transformer.ProgressHolder progressHolder = new Transformer.ProgressHolder();

    private void pollProgress() {
        if (transformer == null) return;
        int state = transformer.getProgress(progressHolder);
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            say("Converting... " + progressHolder.progress + "%");
        }
        handler.postDelayed(this::pollProgress, 500);
    }

    private void copyTmpTo(Convert.Target t, Uri dest) {
        new Thread(() -> {
            try (InputStream in = new FileInputStream(tmpOut);
                 OutputStream out = host.getContentResolver().openOutputStream(dest)) {
                if (out == null) throw new IOException("the destination would not open");
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
                done("Saved " + Convert.outName(source.name, t.ext) + ".");
            } catch (Exception e) {
                done("Could not copy the result out: " + e.getMessage());
            } finally {
                cleanupTmp();
            }
        }, "tengok-convert-copy").start();
    }

    /** Stop any running export; the activity calls this from onDestroy. */
    public void cancelRunning() {
        handler.removeCallbacksAndMessages(null);
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
        cleanupTmp();
    }

    private void cleanupTmp() {
        if (tmpOut != null) {
            //noinspection ResultOfMethodCallIgnored
            tmpOut.delete();
            tmpOut = null;
        }
    }

    private void say(String s) {
        host.runOnUiThread(() -> status.setText(s));
    }

    private void done(String s) {
        say(s);
    }
}
