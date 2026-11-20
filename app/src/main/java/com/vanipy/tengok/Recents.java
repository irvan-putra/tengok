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

        public Entry(String uri, String name, long ts) {
            this.uri = uri;
            this.name = name;
            this.ts = ts;
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
     * One entry per line: base64url(uri) TAB base64url(name) TAB openedAtMs.
     * A fourth field, if present, is ignored (an earlier format carried one).
     */
    static String encode(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(b64(e.uri)).append('\t')
                    .append(b64(e.name == null ? "file" : e.name)).append('\t')
                    .append(e.ts).append('\n');
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
                out.add(new Entry(unb64(f[0]), unb64(f[1]), Long.parseLong(f[2])));
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
     * when the provider allows it (the system picker does) and Android's
     * per-app persisted-grant budget still has room. Persisted grants are
     * never released by tengok, so they can accumulate across many opens;
     * when the budget runs out the grant simply is not persisted and the
     * file still opens for that session - only a later reopen from this
     * list would need a fresh pick. Files reached through a picked folder
     * reopen through the folder's tree grant instead.
     */
    public static void record(Context ctx, Uri uri, String name) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        try {
            ctx.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // not every provider offers a persistable grant; tree grants still reopen
        }
        SharedPreferences p = prefs(ctx);
        List<Entry> l = upsert(decode(p.getString(KEY, "")),
                new Entry(uri.toString(), name, System.currentTimeMillis()));
        p.edit().putString(KEY, encode(l)).apply();
        DiagLog.log(ctx, "RECENTS op=record n=" + l.size());
    }

    /**
     * Clears one entry from the list, nothing else: the file is never
     * touched, and read access Android already granted (a per-document
     * persisted grant or a folder's tree grant) is deliberately kept, so the
     * list can be pruned without ever costing the user access they once
     * approved.
     */
    public static void remove(Context ctx, String uri) {
        SharedPreferences p = prefs(ctx);
        List<Entry> l = remove(decode(p.getString(KEY, "")), uri);
        p.edit().putString(KEY, encode(l)).apply();
        DiagLog.log(ctx, "RECENTS op=remove n=" + l.size());
    }
}
