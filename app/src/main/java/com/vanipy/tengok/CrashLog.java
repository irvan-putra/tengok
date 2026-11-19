package com.vanipy.tengok;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Last-crash breadcrumb. When the app dies unexpectedly, the exception's
 * stack trace is written to a private file. On the next launch the home
 * screen offers to share it - sharing is always the user's own action, the
 * file never leaves the phone by itself, and it carries no file contents.
 */
public final class CrashLog {
    private static final String FILE = "last-crash.txt";

    private CrashLog() {
    }

    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringBuilder sb = new StringBuilder();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
                fmt.setTimeZone(TimeZone.getDefault());
                sb.append("tengok crash report\n");
                sb.append(fmt.format(new Date())).append('\n');
                sb.append("version ").append(BuildConfig.VERSION_NAME).append('\n');
                sb.append("thread ").append(thread.getName()).append('\n');
                sb.append(sanitize(Log.getStackTraceString(error)));
                try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // never let crash reporting crash again
            }
            if (prev != null) prev.uncaughtException(thread, error);
        });
    }

    /**
     * Exception messages can embed URIs, paths or display names of the file
     * being viewed. Keep class/method/line info; blank anything that could
     * carry user data. Stack frames use dotted class names, so they survive.
     */
    static String sanitize(String trace) {
        return trace
                .replaceAll("content://\\S+", "content://<redacted>")
                .replaceAll("file://\\S+", "file://<redacted>")
                .replaceAll("/[\\w.\\-+/%]+", "/<path>");
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static String read(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        try {
            byte[] data = new byte[(int) Math.min(f.length(), 128 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(data);
                if (n <= 0) return null;
                return new String(data, 0, n, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        file(ctx).delete();
    }
}
