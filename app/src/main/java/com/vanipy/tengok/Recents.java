package com.vanipy.tengok;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The home screen's recent-files list. On-device only: Uri strings and
 * display names in a private SharedPreferences file, never logged and never
 * shared. Reopening uses the read access Android already granted - the
 * folder picker (a tree grant) or a per-document persisted permission - so
 * tengok asks for no new access. Removing an entry only clears the list and
 * releases any persisted grant; the file itself is never touched.
 *
 * The list codec is plain String work (no Android) so unit tests can pin it;
 * the Android glue at the bottom is deliberately thin.
 */
public final class Recents {
    public static final int MAX = 12;
    private static final String KEY = "recents_v1";

    private Recents() {
    }

    public static final class Entry {
        public final String uri;
        public final String name;
        public final long ts;
        /** True only when tengok itself took a persistable grant for this Uri. */
        public final boolean granted;

        public Entry(String uri, String name, long ts) {
            this(uri, name, ts, false);
        }

        public Entry(String uri, String name, long ts, boolean granted) {
            this.uri = uri;
            this.name = name;
            this.ts = ts;
            this.granted = granted;
        }
    }

    // ---- pure codec (unit-tested) ----

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getUrlDecoder().decode(s),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * One entry per line: base64url(uri) TAB base64url(name) TAB openedAtMs TAB
     * grant flag ("1"/"0"). Three-field lines from older versions decode with
     * the flag false - never releasing a grant we cannot prove we took.
     */
    static String encode(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(b64(e.uri)).append('\t')
                    .append(b64(e.name == null ? "file" : e.name)).append('\t')
                    .append(e.ts).append('\t')
                    .append(e.granted ? '1' : '0').append('\n');
        }
        return sb.toString();
    }

    /** Tolerates truncated or foreign lines: they are skipped, never fatal. */
    static List<Entry> decode(String s) {
        List<Entry> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t");
            if (f.length != 3 && f.length != 4) continue;
            try {
                out.add(new Entry(unb64(f[0]), unb64(f[1]), Long.parseLong(f[2]),
                        f.length == 4 && f[3].equals("1")));
            } catch (RuntimeException bad) {
                // skip the line
            }
        }
        return out;
    }

    /** Newest first; re-opening a file moves it to the front; capped at MAX. */
    static List<Entry> upsert(List<Entry> list, Entry e) {
        List<Entry> out = new ArrayList<>();
        out.add(e);
        for (Entry old : list) {
            if (out.size() >= MAX) break;
            if (!old.uri.equals(e.uri)) out.add(old);
        }
        return out;
    }

    static List<Entry> remove(List<Entry> list, String uri) {
        List<Entry> out = new ArrayList<>();
        for (Entry old : list) if (!old.uri.equals(uri)) out.add(old);
        return out;
    }

    // ---- Android glue ----

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("tengok", Context.MODE_PRIVATE);
    }

    public static List<Entry> list(Context ctx) {
        return decode(prefs(ctx).getString(KEY, ""));
    }

    /**
     * Remembers a just-opened file. Best-effort: a persistable grant is taken
     * when the provider allows it (the system picker does); files reached
     * through a picked folder reopen through the folder's tree grant instead.
     */
    public static void record(Context ctx, Uri uri, String name) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        boolean took = false;
        try {
            ctx.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            took = true;
        } catch (RuntimeException ignored) {
            // not every provider offers a persistable grant; tree grants still reopen
        }
        SharedPreferences p = prefs(ctx);
        List<Entry> old = decode(p.getString(KEY, ""));
        // a grant taken on an earlier open of this same Uri is still held
        for (Entry e : old) {
            if (e.uri.equals(uri.toString()) && e.granted) {
                took = true;
                break;
            }
        }
        List<Entry> l = upsert(old,
                new Entry(uri.toString(), name, System.currentTimeMillis(), took));
        p.edit().putString(KEY, encode(l)).apply();
        DiagLog.log(ctx, "RECENTS op=record n=" + l.size());
    }

    /**
     * Clears one entry. Only a grant this entry itself recorded as taken is
     * released - a folder's tree grant (the explorer's access) and anything
     * shared is never touched. The file itself is never touched either way.
     */
    public static void remove(Context ctx, String uri) {
        SharedPreferences p = prefs(ctx);
        List<Entry> old = decode(p.getString(KEY, ""));
        boolean held = false;
        for (Entry e : old) {
            if (e.uri.equals(uri) && e.granted) {
                held = true;
                break;
            }
        }
        List<Entry> l = remove(old, uri);
        p.edit().putString(KEY, encode(l)).apply();
        if (held) {
            try {
                ctx.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // nothing persisted for this Uri - fine
            }
        }
        DiagLog.log(ctx, "RECENTS op=remove n=" + l.size());
    }
}
