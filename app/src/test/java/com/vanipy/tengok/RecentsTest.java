package com.vanipy.tengok;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The recent-files list codec: survives real file names (tabs, newlines,
 * unicode), keeps newest first, caps at Recents.MAX, never dies on a
 * truncated line.
 */
public class RecentsTest {
    private static Recents.Entry e(String uri, String name, long ts) {
        return new Recents.Entry(uri, name, ts);
    }

    @Test
    public void roundTripKeepsOrderAndTrickyNames() {
        List<Recents.Entry> in = new ArrayList<>();
        in.add(new Recents.Entry("content://a/1", "song\twith\ttabs.mid", 200, true));
        in.add(e("content://a/2", "new\nline cântina 🎵.mid", 100));
        List<Recents.Entry> out = Recents.decode(Recents.encode(in));
        assertEquals(2, out.size());
        assertEquals("content://a/1", out.get(0).uri);
        assertEquals("song\twith\ttabs.mid", out.get(0).name);
        assertEquals(200, out.get(0).ts);
        assertTrue(out.get(0).granted);
        assertEquals("new\nline cântina 🎵.mid", out.get(1).name);
        assertTrue(!out.get(1).granted);
    }

    @Test
    public void legacyThreeFieldLinesDecodeWithoutAGrant() {
        // 0.3.2's first codec had no grant flag; those entries must never be
        // treated as owning a releasable grant
        List<Recents.Entry> in = new ArrayList<>();
        in.add(e("content://a/7", "seven", 7));
        StringBuilder legacy = new StringBuilder();
        for (String line : Recents.encode(in).split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t");
            legacy.append(f[0]).append('\t').append(f[1]).append('\t').append(f[2]).append('\n');
        }
        List<Recents.Entry> out = Recents.decode(legacy.toString());
        assertEquals(1, out.size());
        assertEquals("seven", out.get(0).name);
        assertTrue(!out.get(0).granted);
    }

    @Test
    public void upsertMovesToFrontWithoutDuplicating() {
        List<Recents.Entry> l = new ArrayList<>();
        l = Recents.upsert(l, e("content://a/1", "one", 1));
        l = Recents.upsert(l, e("content://a/2", "two", 2));
        l = Recents.upsert(l, e("content://a/1", "one", 3));
        assertEquals(2, l.size());
        assertEquals("content://a/1", l.get(0).uri);
        assertEquals(3, l.get(0).ts);
        assertEquals("content://a/2", l.get(1).uri);
    }

    @Test
    public void upsertCapsAtMax() {
        List<Recents.Entry> l = new ArrayList<>();
        for (int i = 0; i < Recents.MAX + 3; i++) {
            l = Recents.upsert(l, e("content://a/" + i, "f" + i, i));
        }
        assertEquals(Recents.MAX, l.size());
        assertEquals("content://a/" + (Recents.MAX + 2), l.get(0).uri);
        // the oldest fell off the end
        for (Recents.Entry en : l) {
            assertTrue(!en.uri.equals("content://a/0"));
        }
    }

    @Test
    public void removeDropsOnlyThatUri() {
        List<Recents.Entry> l = new ArrayList<>();
        l.add(e("content://a/1", "one", 1));
        l.add(e("content://a/2", "two", 2));
        l = Recents.remove(l, "content://a/1");
        assertEquals(1, l.size());
        assertEquals("content://a/2", l.get(0).uri);
    }

    @Test
    public void decodeToleratesGarbage() {
        List<Recents.Entry> good = new ArrayList<>();
        good.add(e("content://a/9", "nine", 9));
        String mixed = "not an entry\n" + Recents.encode(good) + "x\ty\tz\na\tb\n";
        List<Recents.Entry> out = Recents.decode(mixed);
        assertEquals(1, out.size());
        assertEquals("nine", out.get(0).name);
        assertTrue(Recents.decode("").isEmpty());
        assertTrue(Recents.decode(null).isEmpty());
    }
}
