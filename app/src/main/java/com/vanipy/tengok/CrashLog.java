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
                sb.append("android ").append(android.os.Build.VERSION.RELEASE)
                        .append(" sdk ").append(android.os.Build.VERSION.SDK_INT)
                        .append(" device ").append(android.os.Build.MODEL).append('\n');
                // Full trace stays in this app-private file, which no share
                // path reads; sharing goes through shareableReport below.
                sb.append(Log.getStackTraceString(error));
                try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                DiagLog.log(ctx, "CRASH " + error.getClass().getName() + " op=uncaught");
            } catch (Throwable ignored) {
                // never let crash reporting crash again
            }
            if (prev != null) prev.uncaughtException(thread, error);
        });
        DiagLog.log(ctx, "APP-START version " + BuildConfig.VERSION_NAME
                + " android " + android.os.Build.VERSION.RELEASE
                + " sdk " + android.os.Build.VERSION.SDK_INT
                + " device " + android.os.Build.MODEL);
    }

    /**
     * The report the crash card shares. Exception messages and frames from
     * other apps (a provider's classes name installed software) are dropped:
     * keep header, exception classes, and tengok's own frames only.
     */
    public static String shareableReport(Context ctx) {
        String crash = read(ctx);
        return crash == null ? "" : shareable(crash);
    }

    static String shareable(String crash) {
        StringBuilder out = new StringBuilder();
        int frames = 0;
        for (String line : crash.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("at ")) {
                if (frames < 12 && t.startsWith("at com.vanipy.tengok.")) {
                    out.append(t).append('\n');
                    frames++;
                }
                continue;
            }
            if (t.startsWith("... ")) continue; // "... N more" frame tail
            if (t.startsWith("Suppressed: ")) {
                out.append("Suppressed: ").append(classOnly(t.substring("Suppressed: ".length()))).append('\n');
                continue;
            }
            if (t.startsWith("Caused by: ")) {
                out.append("Caused by: ").append(classOnly(t.substring("Caused by: ".length()))).append('\n');
                continue;
            }
            if (t.startsWith("tengok crash report") || t.startsWith("version ")
                    || t.startsWith("thread ") || t.startsWith("android ")
                    || t.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                out.append(t).append('\n');
                continue;
            }
            // Exception headline line: keep the class, drop the message.
            out.append(classOnly(t)).append('\n');
        }
        out.append("(full trace stays on this phone)");
        return out.toString();
    }

    private static String classOnly(String headline) {
        int cut = headline.indexOf(':');
        String cls = cut < 0 ? headline : headline.substring(0, cut);
        return cls.matches("[A-Za-z0-9_.$]+") ? cls : "(exception class unreadable)";
    }

    /**
     * Exception messages can embed URIs, paths or display names of the file
     * being viewed. Keep class/method/line info; blank anything that could
     * carry user data. Stack frames use dotted class names, so they survive.
     */
    static String sanitize(String trace) {
        // paths need at least two segments, so MIME types (audio/midi) and
        // component names (com.vanipy.tengok/.MainActivity) stay readable
        return trace
                .replaceAll("content://\\S+", "content://<redacted>")
                .replaceAll("file://\\S+", "file://<redacted>")
                .replaceAll("/[\\w.\\-+/%]+/[\\w.\\-+/%]+", "/<path>");
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
