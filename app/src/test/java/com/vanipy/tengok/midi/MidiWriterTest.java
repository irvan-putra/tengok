package com.vanipy.tengok.midi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Round-trip fidelity for MidiWriter and hit-window behavior for GameScoring.
 * The embedded fixture carries expression on purpose: program change, CC7
 * volume, CC64 sustain, pitch bend mid-note, an immediate same-pitch repeat,
 * a zero-velocity note-on as note-off, and a second track on another channel.
 */
public class MidiWriterTest {

    private static final byte[] EXPRESSIVE = new byte[]{
            77, 84, 104, 100, 0, 0, 0, 6, 0, 1, 0, 3, 1, -32, 77, 84, 114, 107, 0, 0, 0, 19, 0, -1, 81, 3, 7, -95, 32, 0, -1, 88, 4, 4, 2, 24, 8, 0, -1, 47, 0, 77, 84, 114, 107, 0, 0, 0, 53, 0, -64, 40, 0, -80, 7, 100, 0, -32, 0, 64, 0, -112, 60, 96, -127, 112, -32, 0, 96, -127, 112, -128, 60, 0, 0, -112, 60, 110, 120, -80, 64, 127, 120, 96, 0, 0, -112, 64, 100, -127, 112, -80, 64, 0, 0, -128, 64, 0, 0, -1, 47, 0, 77, 84, 114, 107, 0, 0, 0, 16, 0, -63, 32, 0, -111, 36, 80, -121, 64, -127, 36, 0, 0, -1, 47, 0
    };

    private static MidiFile parse(byte[] d) throws Exception {
        return MidiFile.parse(d);
    }

    private static String noteKey(MidiFile.Note n) {
        return n.pitch + "/" + n.startTick + "/" + n.endTick + "/" + n.channel + "/" + n.velocity;
    }

    private static Map<String, Integer> noteMultiset(MidiFile m, int dropTrack) {
        Map<String, Integer> out = new HashMap<>();
        for (MidiFile.Note n : m.notes) {
            if (n.track == dropTrack) continue;
            out.merge(noteKey(n), 1, Integer::sum);
        }
        return out;
    }

    private static Map<String, Integer> exprMultiset(MidiFile m, int dropTrack) {
        Map<String, Integer> out = new HashMap<>();
        for (MidiFile.ChannelEvent e : m.channelEvents) {
            if (e.track == dropTrack) continue;
            StringBuilder sb = new StringBuilder().append(e.tick);
            for (byte b : e.bytes) sb.append('/').append(b & 0xFF);
            out.merge(sb.toString(), 1, Integer::sum);
        }
        return out;
    }


    /** Builds a tiny format-0 song: one track, 480tpq, tempo 500000us/qn, quarter notes. */
    private static byte[] song(int[][] notes) {
        java.io.ByteArrayOutputStream trk = new java.io.ByteArrayOutputStream();
        trk.write(0);
        trk.writeBytes(new byte[]{(byte) 0xFF, 0x51, 0x03, 0x07, (byte) 0xA1, 0x20});
        java.util.List<long[]> evs = new java.util.ArrayList<>();
        for (int[] n : notes) {
            evs.add(new long[]{n[1], 0x90, n[0], n.length > 3 ? n[3] : 90});
            evs.add(new long[]{n[2], 0x80, n[0], 0});
        }
        evs.sort(java.util.Comparator.comparingLong(e -> e[0]));
        long prev = 0;
        for (long[] e : evs) {
            writeVarint(trk, e[0] - prev);
            trk.writeBytes(new byte[]{(byte) e[1], (byte) e[2], (byte) e[3]});
            prev = e[0];
        }
        writeVarint(trk, 0);
        trk.writeBytes(new byte[]{(byte) 0xFF, 0x2F, 0x00});
        byte[] body = trk.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 1, (byte) 0xE0});
        out.writeBytes(new byte[]{'M', 'T', 'r', 'k',
                (byte) (body.length >> 24), (byte) (body.length >> 16), (byte) (body.length >> 8), (byte) body.length});
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static void writeVarint(java.io.ByteArrayOutputStream o, long v) {
        long buf = v & 0x7F;
        v >>= 7;
        while (v != 0) {
            buf = (buf << 8) | ((v & 0x7F) | 0x80);
            v >>= 7;
        }
        while (true) {
            o.write((int) (buf & 0xFF));
            if ((buf & 0x80) != 0) buf >>= 8; else break;
        }
    }

    @Test
    public void keepsEveryNoteAndEveryExpressiveEventVerbatim() throws Exception {
        MidiFile m = parse(EXPRESSIVE);
        assertEquals(3, m.tracks.size());
        Set<Integer> drop = new HashSet<>();
        drop.add(1); // the violin track becomes "your part"
        byte[] backing = MidiWriter.write(m, drop, 1.0);
        MidiFile r = parse(backing);

        assertEquals(noteMultiset(m, 1), noteMultiset(r, -1));
        assertEquals(exprMultiset(m, 1), exprMultiset(r, -1));
        assertEquals(m.tempos.size(), r.tempos.size());
        for (int i = 0; i < m.tempos.size(); i++) {
            assertEquals(m.tempos.get(i).tick, r.tempos.get(i).tick);
            assertEquals(m.tempos.get(i).usPerQuarter, r.tempos.get(i).usPerQuarter);
        }
        assertEquals(m.timeSigNum, r.timeSigNum);
        assertEquals(m.timeSigDen, r.timeSigDen);
        assertEquals(m.durationTicks, r.durationTicks);
    }

    @Test
    public void droppedTrackDisappearsCompletely() throws Exception {
        MidiFile m = parse(EXPRESSIVE);
        Set<Integer> drop = new HashSet<>();
        drop.add(1);
        MidiFile r = parse(MidiWriter.write(m, drop, 1.0));
        for (MidiFile.Note n : r.notes) {
            assertTrue("dropped track's note survived", n.pitch != 60 && n.pitch != 64);
        }
        for (MidiFile.ChannelEvent e : r.channelEvents) {
            assertTrue("dropped track's channel event survived", (e.bytes[0] & 0x0F) != 0);
        }
    }

    @Test
    public void halfSpeedDoublesEveryDuration() throws Exception {
        MidiFile m = parse(EXPRESSIVE);
        Set<Integer> drop = new HashSet<>();
        drop.add(1);
        MidiFile slow = parse(MidiWriter.write(m, drop, 0.5));
        MidiFile full = parse(MidiWriter.write(m, drop, 1.0));
        assertEquals(full.durationSeconds * 2.0, slow.durationSeconds, 0.05);
    }

    @Test(expected = IllegalArgumentException.class)
    public void smpteFilesAreRefused() throws Exception {
        MidiFile m = parse(EXPRESSIVE);
        m.smpte = true;
        MidiWriter.write(m, new HashSet<>(), 1.0);
    }

    @Test
    public void scoringJudgesEachNoteOnItsOwnClock() throws Exception {
        // A staggered "chord": C4 at 0.500s, E4 at 0.530s, G4 at 0.565s - human, not quantized.
        MidiFile m = parse(song(new int[][]{{60, 480, 720}, {64, 509, 749}, {67, 542, 782}}));
        GameScoring s = new GameScoring(m, 0);
        // times: tick 480 -> 0.5s, 509 -> 0.530s, 542 -> 0.565s (500ms per quarter = 480 ticks)
        assertEquals(GameScoring.PERFECT, s.strike(60, 505));
        assertEquals(GameScoring.PERFECT, s.strike(64, 538));
        assertEquals(GameScoring.GOOD, s.strike(67, 745)); // 180ms late still counts
        assertEquals(3, s.judged());
        assertEquals(3, s.streak());
        assertEquals(250, s.score());
        assertTrue(s.finished());
    }

    @Test
    public void scoringMissesWhatTheClockPasses() throws Exception {
        MidiFile m = parse(song(new int[][]{{60, 480, 720}}));
        GameScoring s = new GameScoring(m, 0);
        s.tick(500 + GameScoring.GOOD_MS + 1); // window closed
        assertEquals(GameScoring.MISS, s.stateOf(0));
        assertEquals(GameScoring.PENDING, s.strike(60, 900)); // too late to matter
        assertEquals(1, s.miss());
        assertEquals(0, s.streak());
    }

    @Test
    public void unrelatedPitchStrikeDoesNotScore() throws Exception {
        MidiFile m = parse(song(new int[][]{{60, 480, 720}}));
        GameScoring s = new GameScoring(m, 0);
        assertEquals(GameScoring.PENDING, s.strike(61, 500));
        assertEquals(0, s.judged());
    }
}
