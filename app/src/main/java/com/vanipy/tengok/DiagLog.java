package com.vanipy.tengok;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * On-device diagnostic log for caught errors and key events: what kind of
 * file was opened, which step failed and how. Written to app-private
 * storage, trimmed to a tail, sanitized before it is written, and shared
 * only when the user taps "Share diagnostics". Nothing leaves the phone
 * by itself, and file contents are never logged - only events.
 */
public final class DiagLog {
    private static final String FILE = "diag.log";
    private static final int MAX_BYTES = 48 * 1024;
    private static final Object LOCK = new Object();

    private DiagLog() {
    }

    /** The display name of the file currently open: never written to the log. */
    private static volatile String sensitiveName;

    public static void watch(String displayName) {
        sensitiveName = displayName;
    }

    /**
     * Display-only scrub for the on-screen Details fallback message: URI/path
     * sanitize plus the watched display name. Shareable surfaces never carry
     * raw messages, so they do not depend on this.
     */
    public static String scrub(String text) {
        String out = CrashLog.sanitize(text);
        String n = sensitiveName;
        if (n != null && n.length() >= 3) {
            out = out.replace(n, "<file>");
        }
        return out;
    }

    /** Normalize an intent-declared type: a MIME token or "<other>". */
    public static String mime(String type) {
        if (type == null) return "none";
        String t = type.toLowerCase(Locale.ROOT).trim();
        if (t.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) return t;
        return "<other>";
    }

    public static void log(Context ctx, String event) {
        append(file(ctx), stamp() + " " + scrub(event));
    }

    public static String read(Context ctx) {
        return read(file(ctx));
    }

    public static void clear(Context ctx) {
        file(ctx).delete();
    }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    private static String stamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date());
    }

    // ---- file core, JVM-testable ----

    static void append(File f, String line) {
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
            trim(f);
        }
    }

    static String read(File f) {
        if (!f.exists()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Keep the newest MAX_BYTES, starting on a line boundary. */
    static void trim(File f) {
        if (!f.exists() || f.length() <= MAX_BYTES) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long start = f.length() - MAX_BYTES;
            raf.seek(start);
            byte[] data = new byte[MAX_BYTES];
            int n = raf.read(data);
            int cut = 0;
            while (cut < n && data[cut] != '\n') cut++;
            cut++;
            byte[] tail = new byte[n - cut];
            System.arraycopy(data, cut, tail, 0, tail.length);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(tail);
            }
        } catch (IOException ignored) {
        }
    }
}
