package com.vanipy.tengok.midi;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes a playable Standard MIDI File from a parsed one, optionally dropping
 * chosen tracks (the "your part" in game mode) and scaling the tempo map
 * (playback speed). Everything the parser kept is re-emitted verbatim:
 * tempo map, time signature, program changes, control changes (sustain
 * pedal, volume, ...), pitch bend and every note with its original velocity
 * and timing. No quantizing, no flattening: dynamics survive.
 */
public final class MidiWriter {

    private static final class Ev {
        final long tick;
        final int order; // channel events first, then note-offs, then note-ons
        final byte[] bytes;

        Ev(long tick, int order, byte[] bytes) {
            this.tick = tick;
            this.order = order;
            this.bytes = bytes;
        }
    }

    /**
     * @param midi       parsed song
     * @param dropTracks track indexes to leave out (their notes AND their channel events)
     * @param speed      playback speed factor: 1.0 = as written, 0.5 = half speed
     * @return a format-1 SMF containing the kept tracks
     */
    public static byte[] write(MidiFile midi, Set<Integer> dropTracks, double speed) {
        if (midi.smpte) throw new IllegalArgumentException("SMPTE-timed songs are not supported");
        if (speed <= 0.05 || speed > 4.0) throw new IllegalArgumentException("speed out of range");

        Set<Integer> kept = new TreeSet<>();
        for (MidiFile.Note n : midi.notes) {
            if (!dropTracks.contains(n.track)) kept.add(n.track);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1,
                (byte) ((kept.size() + 1) >> 8), (byte) (kept.size() + 1),
                (byte) (midi.ticksPerQuarter >> 8), (byte) midi.ticksPerQuarter});

        // Track 0: tempo map (scaled for speed) + time signature.
        List<Ev> conductor = new ArrayList<>();
        for (MidiFile.TempoChange t : midi.tempos) {
            int us = Math.max(1, (int) Math.round(t.usPerQuarter / speed));
            conductor.add(new Ev(t.tick, 0, new byte[]{(byte) 0xFF, 0x51, 0x03,
                    (byte) (us >> 16), (byte) (us >> 8), (byte) us}));
        }
        conductor.add(new Ev(0, 1, new byte[]{(byte) 0xFF, 0x58, 0x04,
                (byte) midi.timeSigNum, (byte) log2(midi.timeSigDen), 24, 8}));
        out.writeBytes(chunk(conductor, midi.durationTicks));

        for (int ti : kept) {
            List<Ev> evs = new ArrayList<>();
            for (MidiFile.ChannelEvent e : midi.channelEvents) {
                if (e.track == ti) evs.add(new Ev(e.tick, 0, e.bytes));
            }
            for (MidiFile.Note n : midi.notes) {
                if (n.track != ti) continue;
                evs.add(new Ev(n.startTick, 2,
                        new byte[]{(byte) (0x90 | n.channel), (byte) n.pitch, (byte) n.velocity}));
                evs.add(new Ev(n.endTick, 1,
                        new byte[]{(byte) (0x80 | n.channel), (byte) n.pitch, 0}));
            }
            out.writeBytes(chunk(evs, midi.durationTicks));
        }
        return out.toByteArray();
    }

    private static int log2(int v) {
        int r = 0;
        while ((1 << r) < v) r++;
        return r;
    }

    private static byte[] chunk(List<Ev> evs, long endTick) {
        evs.sort(Comparator.comparingLong((Ev e) -> e.tick).thenComparingInt(e -> e.order));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long prev = 0;
        for (Ev e : evs) {
            body.writeBytes(varint(e.tick - prev));
            body.writeBytes(e.bytes);
            prev = e.tick;
        }
        body.writeBytes(varint(Math.max(0, endTick - prev)));
        body.writeBytes(new byte[]{(byte) 0xFF, 0x2F, 0x00});
        byte[] b = body.toByteArray();
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.writeBytes(new byte[]{'M', 'T', 'r', 'k',
                (byte) (b.length >> 24), (byte) (b.length >> 16), (byte) (b.length >> 8), (byte) b.length});
        c.writeBytes(b);
        return c.toByteArray();
    }

    private static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
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
        return o.toByteArray();
    }
}
