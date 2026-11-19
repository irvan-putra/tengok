package com.vanipy.tengok;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Debug builds only. Makes small synthetic files in the app's own cache and
 * opens them in the real viewer, so the emulator smoke test sees genuine
 * rendering without touching anyone's data. Never in a release APK.
 */
public class FixtureActivity extends Activity {
    private static final String TAG = "tengok";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);

        addButton(column, "MIDI: C major scale", "scale.mid", FixtureActivity::scaleMid);
        addButton(column, "MIDI: two-track song, tempo change", "song.mid", FixtureActivity::songMid);
        addButton(column, "MIDI: corrupt file", "corrupt.mid", FixtureActivity::corruptMid);
        addButton(column, "Text: hello", "hello.txt", FixtureActivity::helloTxt);
        addButton(column, "Hex: random blob", "blob.bin", FixtureActivity::blob);
        addButton(column, "Image: painted PNG", "painted.png", FixtureActivity::paintedPng);

        setContentView(column);
    }

    private interface Maker {
        byte[] make() throws IOException;
    }

    private void addButton(LinearLayout column, String label, String fileName, Maker maker) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> {
            try {
                byte[] data = maker.make();
                Log.i(TAG, "TENGOK-FIXTURE-READY " + label);
                open(fileName, data);
            } catch (IOException e) {
                Log.e(TAG, "fixture failed", e);
            }
        });
        column.addView(b);
    }

    private void open(String fileName, byte[] data) throws IOException {
        File named = new File(getCacheDir(), fileName);
        try (FileOutputStream out = new FileOutputStream(named)) {
            out.write(data);
        }
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra("fixture_path", named.getAbsolutePath());
        startActivity(i);
    }

    /** Format 0: eight quarter notes of C major on an acoustic grand, 120 bpm. */
    static byte[] scaleMid() throws IOException {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        metaText(track, 0, 0x03, "scale");
        metaTempo(track, 0, 500000); // 120 bpm
        metaTimeSig(track, 0, 4, 4);
        event(track, 0, 0xC0, 0, -1); // program 0: acoustic grand
        int[] pitches = {60, 62, 64, 65, 67, 69, 71, 72};
        for (int p : pitches) {
            event(track, 0, 0x90, p, 96);
            event(track, 480, 0x80, p, 0);
        }
        metaEnd(track, 0);
        return smf(0, 480, new byte[][]{track.toByteArray()});
    }

    /** Format 1: tempo/time-sig track plus a melody and a bass line, 120 -> 60 bpm. */
    static byte[] songMid() throws IOException {
        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        metaText(t0, 0, 0x03, "tempo");
        metaTempo(t0, 0, 500000);
        metaTimeSig(t0, 0, 3, 4);
        metaTempo(t0, 960, 1000000); // halves the pace halfway
        metaEnd(t0, 0);

        ByteArrayOutputStream t1 = new ByteArrayOutputStream();
        metaText(t1, 0, 0x03, "melody");
        event(t1, 0, 0xC0, 40, -1); // violin
        event(t1, 0, 0x90, 72, 100);
        event(t1, 480, 0x90, 74, 100); // running status: no 0x90 needed, but explicit is fine
        event(t1, 480, 0x90, 76, 100);
        event(t1, 480, 0x90, 77, 100);
        event(t1, 480, 0x80, 72, 0);
        event(t1, 0, 0x80, 74, 0);
        event(t1, 0, 0x80, 76, 0);
        event(t1, 480, 0x80, 77, 0);
        metaEnd(t1, 0);

        ByteArrayOutputStream t2 = new ByteArrayOutputStream();
        metaText(t2, 0, 0x03, "bass");
        event(t2, 0, 0xC1, 32, -1); // acoustic bass
        event(t2, 0, 0x91, 48, 90);
        event(t2, 960, 0x81, 48, 0);
        event(t2, 0, 0x91, 43, 90);
        event(t2, 960, 0x81, 43, 0);
        metaEnd(t2, 0);

        return smf(1, 480, new byte[][]{t0.toByteArray(), t1.toByteArray(), t2.toByteArray()});
    }

    static byte[] corruptMid() {
        return new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0}; // truncated header
    }

    static byte[] helloTxt() {
        return ("tengok fixture text\n"
                + "line two with some numbers 0123456789\n"
                + "unicode: melihat • berkas • 0.1.0 • \u00e9\u00e0\u00fc\n"
                + "the quick brown fox jumps over the lazy dog\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static byte[] blob() {
        byte[] b = new byte[300];
        int x = 123456789;
        for (int i = 0; i < b.length; i++) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            b[i] = (byte) x;
        }
        b[0] = 'T';
        b[1] = 'G';
        b[2] = 'K';
        b[3] = 0;
        return b;
    }

    static byte[] paintedPng() throws IOException {
        Bitmap bmp = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int y = 0; y < 320; y++) {
            p.setColor(0xFF000000 | (0x1F + y / 3) << 16 | (0x2A + y / 4) << 8 | 0x5C);
            c.drawRect(0, y, 480, y + 1, p);
        }
        p.setColor(0xFFFFB300);
        c.drawCircle(240, 130, 70, p);
        p.setColor(0xFFFAFAF7);
        c.drawCircle(215, 115, 14, p);
        c.drawCircle(265, 115, 14, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    // ---- tiny SMF writer for fixtures ----

    static byte[] smf(int format, int division, byte[][] tracks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6});
        out.write((format >> 8) & 0xFF);
        out.write(format & 0xFF);
        out.write((tracks.length >> 8) & 0xFF);
        out.write(tracks.length & 0xFF);
        out.write((division >> 8) & 0xFF);
        out.write(division & 0xFF);
        for (byte[] t : tracks) {
            out.write(new byte[]{'M', 'T', 'r', 'k'});
            out.write((t.length >> 24) & 0xFF);
            out.write((t.length >> 16) & 0xFF);
            out.write((t.length >> 8) & 0xFF);
            out.write(t.length & 0xFF);
            out.write(t);
        }
        return out.toByteArray();
    }

    static void varlen(ByteArrayOutputStream out, long v) {
        long buffer = v & 0x7F;
        v >>= 7;
        while (v > 0) {
            buffer <<= 8;
            buffer |= (v & 0x7F) | 0x80;
            v >>= 7;
        }
        while (true) {
            out.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) != 0) buffer >>= 8;
            else break;
        }
    }

    static void event(ByteArrayOutputStream out, long delta, int status, int d1, int d2) {
        varlen(out, delta);
        out.write(status & 0xFF);
        out.write(d1 & 0x7F);
        if (d2 >= 0) out.write(d2 & 0x7F);
    }

    static void metaText(ByteArrayOutputStream out, long delta, int type, String text) throws IOException {
        varlen(out, delta);
        out.write(0xFF);
        out.write(type);
        byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        varlen(out, b.length);
        out.write(b);
    }

    static void metaTempo(ByteArrayOutputStream out, long delta, int usPerQuarter) {
        varlen(out, delta);
        out.write(0xFF);
        out.write(0x51);
        out.write(3);
        out.write((usPerQuarter >> 16) & 0xFF);
        out.write((usPerQuarter >> 8) & 0xFF);
        out.write(usPerQuarter & 0xFF);
    }

    static void metaTimeSig(ByteArrayOutputStream out, long delta, int num, int den) {
        varlen(out, delta);
        out.write(0xFF);
        out.write(0x58);
        out.write(4);
        out.write(num);
        int p = 0;
        while ((1 << p) < den) p++;
        out.write(p);
        out.write(24);
        out.write(8);
    }

    static void metaEnd(ByteArrayOutputStream out, long delta) {
        varlen(out, delta);
        out.write(0xFF);
        out.write(0x2F);
        out.write(0);
    }
}
