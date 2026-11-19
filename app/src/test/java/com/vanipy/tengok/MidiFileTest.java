package com.vanipy.tengok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.vanipy.tengok.midi.GmNames;
import com.vanipy.tengok.midi.MidiFile;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Parser tests against synthetic bytes written by an independent tiny writer. */
public class MidiFileTest {

    // --- the same minimal SMF writer shape as the debug fixtures, kept separate ---
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

    static void meta(ByteArrayOutputStream out, long delta, int type, byte[] payload) throws IOException {
        varlen(out, delta);
        out.write(0xFF);
        out.write(type);
        varlen(out, payload.length);
        out.write(payload);
    }

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

    static byte[] scaleSmf() throws IOException {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        meta(t, 0, 0x03, "scale".getBytes("UTF-8"));
        meta(t, 0, 0x51, new byte[]{0x07, (byte) 0xA1, 0x20}); // 500000 us = 120 bpm
        meta(t, 0, 0x58, new byte[]{4, 2, 24, 8});
        event(t, 0, 0xC0, 0, -1);
        int[] pitches = {60, 62, 64, 65, 67, 69, 71, 72};
        for (int p : pitches) {
            event(t, 0, 0x90, p, 96);
            event(t, 480, 0x80, p, 0);
        }
        meta(t, 0, 0x2F, new byte[0]);
        return smf(0, 480, new byte[][]{t.toByteArray()});
    }

    @Test
    public void scaleParsesFully() throws Exception {
        MidiFile m = MidiFile.parse(scaleSmf());
        assertEquals(0, m.format);
        assertEquals(1, m.tracks.size());
        assertEquals(480, m.ticksPerQuarter);
        assertEquals(8, m.notes.size());
        MidiFile.Track t = m.tracks.get(0);
        assertEquals("scale", t.name);
        assertEquals(8, t.noteCount);
        assertEquals(60, t.minPitch);
        assertEquals(72, t.maxPitch);
        assertTrue(t.programs.contains(0));
        assertEquals("Acoustic Grand Piano", GmNames.instrument(t.programs.get(0)));
        assertEquals(4, m.timeSigNum);
        assertEquals(4, m.timeSigDen);
        // 8 quarter notes, sequential, at 120 bpm => 8 * 0.5 s = 4 s
        assertEquals(8 * 480, m.durationTicks);
        assertEquals(4.0, m.durationSeconds, 1e-6);
        // first note starts at 0, second at 0.5 s
        assertEquals(0.0, m.notes.get(0).startSec, 1e-9);
        assertEquals(0.5, m.notes.get(1).startSec, 1e-6);
    }

    @Test
    public void tempoChangeMovesSeconds() throws Exception {
        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        meta(t0, 0, 0x51, new byte[]{0x07, (byte) 0xA1, 0x20});   // 120 bpm
        meta(t0, 960, 0x51, new byte[]{0x0F, 0x42, 0x40});         // 60 bpm at tick 960
        meta(t0, 0, 0x2F, new byte[0]);
        ByteArrayOutputStream t1 = new ByteArrayOutputStream();
        event(t1, 0, 0x90, 60, 96);
        event(t1, 1920, 0x80, 60, 0); // note covers the tempo change
        meta(t1, 0, 0x2F, new byte[0]);
        MidiFile m = MidiFile.parse(smf(1, 480, new byte[][]{t0.toByteArray(), t1.toByteArray()}));
        assertEquals(1, m.format);
        // first 960 ticks at 0.5 s/quarter = 1 s, next 960 at 1 s/quarter = 2 s -> 3 s
        assertEquals(3.0, m.tickToSeconds(1920), 1e-6);
        assertEquals(3.0, m.notes.get(0).endSec, 1e-6);
    }

    @Test
    public void runningStatusParses() throws Exception {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        varlen(t, 0);
        t.write(0x90);
        t.write(60);
        t.write(100);
        // three running-status note-ons without repeating the status byte
        varlen(t, 240);
        t.write(64);
        t.write(100);
        varlen(t, 240);
        t.write(67);
        t.write(100);
        varlen(t, 0);
        t.write(0x80);
        t.write(60);
        t.write(0);
        varlen(t, 0);
        t.write(64); // running status note-off
        t.write(0);
        varlen(t, 0);
        t.write(67);
        t.write(0);
        meta(t, 0, 0x2F, new byte[0]);
        MidiFile m = MidiFile.parse(smf(0, 480, new byte[][]{t.toByteArray()}));
        assertEquals(3, m.notes.size());
    }

    @Test
    public void unclosedNoteRingsToTrackEnd() throws Exception {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        event(t, 0, 0x90, 60, 100);
        event(t, 960, 0xB0, 7, 100); // some later event, no note-off
        meta(t, 0, 0x2F, new byte[0]);
        MidiFile m = MidiFile.parse(smf(0, 480, new byte[][]{t.toByteArray()}));
        assertEquals(1, m.notes.size());
        assertEquals(0, m.notes.get(0).startTick);
        assertEquals(960, m.notes.get(0).endTick);
    }

    @Test
    public void corruptFilesFailCleanly() throws Exception {
        expectParseFailure(new byte[0]);
        expectParseFailure(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14});
        expectParseFailure(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0}); // truncated header
        // header fine, no track at all
        expectParseFailure(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0, 1, 1, (byte) 0xE0, 0, 0});
        // truncated track body
        expectParseFailure(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 1, (byte) 0xE0,
                'M', 'T', 'r', 'k', 0, 0, 0, 100, 0, (byte) 0x90, 60});
        // five-byte varlen is out of spec
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F});
        meta(t, 0, 0x2F, new byte[0]);
        expectParseFailure(smf(0, 480, new byte[][]{t.toByteArray()}));
        // a thousand random bytes must fail, not crash the parser
        byte[] junk = new byte[1000];
        int x = 42;
        for (int i = 0; i < junk.length; i++) {
            x = (x * 1103515245 + 12345) & 0x7FFFFFFF;
            junk[i] = (byte) x;
        }
        expectParseFailure(junk);
    }

    private static void expectParseFailure(byte[] data) throws IOException {
        try {
            MidiFile.parse(data);
            fail("expected ParseException for " + data.length + " bytes");
        } catch (MidiFile.ParseException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void sysexAndMetaAreSkipped() throws Exception {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        varlen(t, 0);
        t.write(0xF0);
        varlen(t, 5);
        t.write(new byte[]{0x7E, 0x7F, 0x09, 0x01, (byte) 0xF7});
        meta(t, 0, 0x06, "marker text".getBytes("UTF-8"));
        event(t, 0, 0x90, 60, 90);
        event(t, 480, 0x80, 60, 0);
        meta(t, 0, 0x2F, new byte[0]);
        MidiFile m = MidiFile.parse(smf(0, 480, new byte[][]{t.toByteArray()}));
        assertEquals(1, m.notes.size());
    }
}
