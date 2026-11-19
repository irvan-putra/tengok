package com.vanipy.tengok.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard MIDI File (SMF) reader. Understands format 0/1/2, running status,
 * SysEx and the common meta events, pairs note-on/note-off into notes, and
 * converts ticks to seconds through the tempo map. Everything is bounded:
 * the byte array is already size-capped by the caller, and every length read
 * is checked against the remaining bytes before it is trusted.
 */
public final class MidiFile {

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    public static final class TempoChange {
        public final long tick;
        public final int usPerQuarter;

        TempoChange(long tick, int usPerQuarter) {
            this.tick = tick;
            this.usPerQuarter = usPerQuarter;
        }
    }

    public static final class Note {
        public long startTick;
        public long endTick;
        public double startSec;
        public double endSec;
        public int pitch;
        public int velocity;
        public int channel;
        public int track;
    }

    public static final class Track {
        public int index;
        public String name = "";
        public int noteCount;
        public int minPitch = -1;
        public int maxPitch = -1;
        public long endTick;
        public final List<Integer> programs = new ArrayList<>();
        public final boolean[] channels = new boolean[16];

        public boolean usesChannel(int ch) {
            return channels[ch];
        }
    }

    /** A channel-mode event (program change, control change, pitch bend, pressure)
     *  kept verbatim so a filtered copy of the song can be re-written losslessly. */
    public static final class ChannelEvent {
        public long tick;
        public int track;
        public byte[] bytes; // status byte followed by its data bytes

        ChannelEvent(long tick, int track, byte[] bytes) {
            this.tick = tick;
            this.track = track;
            this.bytes = bytes;
        }
    }

    public int format;
    public int ticksPerQuarter = 480;
    public boolean smpte;
    public double smpteTicksPerSecond;
    public int timeSigNum = 4;
    public int timeSigDen = 4;
    public String keySig = "";
    public final List<TempoChange> tempos = new ArrayList<>();
    public final List<Note> notes = new ArrayList<>();
    public final List<ChannelEvent> channelEvents = new ArrayList<>();
    public final List<Track> tracks = new ArrayList<>();
    public long durationTicks;
    public double durationSeconds;

    private static final int MAX_EVENTS = 2_000_000;
    private static final String[] KEY_NAMES = {
            "Cb major", "Gb major", "Db major", "Ab major", "Eb major", "Bb major", "F major",
            "C major", "G major", "D major", "A major", "E major", "B major", "F# major", "C# major"};

    public static MidiFile parse(byte[] d) throws ParseException {
        if (d == null || d.length < 14) throw new ParseException("too short to be a MIDI file");
        MidiFile m = new MidiFile();
        Reader r = new Reader(d);
        if (r.u8() != 'M' || r.u8() != 'T' || r.u8() != 'h' || r.u8() != 'd') {
            throw new ParseException("missing the MThd header");
        }
        long hlen = r.u32();
        if (hlen < 6) throw new ParseException("header chunk is too short");
        if (hlen > 1024) throw new ParseException("header chunk is oddly long");
        m.format = r.u16();
        int ntracks = r.u16();
        int division = r.u16();
        r.skip((int) (hlen - 6));
        if (m.format > 2) throw new ParseException("unknown MIDI format " + m.format);
        if (ntracks == 0) throw new ParseException("the file has no tracks");
        if ((division & 0x8000) != 0) {
            m.smpte = true;
            int fps = 256 - ((division >> 8) & 0xFF);
            int tpf = division & 0xFF;
            if (tpf == 0) throw new ParseException("bad SMPTE division");
            m.smpteTicksPerSecond = fps * (double) tpf;
        } else {
            m.ticksPerQuarter = division == 0 ? 480 : division;
        }
        int totalEvents = 0;
        for (int t = 0; t < ntracks; t++) {
            totalEvents += m.parseTrack(r, t);
            if (totalEvents > MAX_EVENTS) throw new ParseException("too many events");
        }
        m.finishTiming();
        return m;
    }

    private int parseTrack(Reader r, int index) throws ParseException {
        if (r.remaining() < 8) throw new ParseException("track " + (index + 1) + " is missing");
        if (r.u8() != 'M' || r.u8() != 'T' || r.u8() != 'r' || r.u8() != 'k') {
            throw new ParseException("track " + (index + 1) + " has no MTrk header");
        }
        long len = r.u32();
        if (len > r.remaining()) throw new ParseException("track " + (index + 1) + " is truncated");
        Reader tr = r.subReader((int) len);
        r.skip((int) len);

        Track track = new Track();
        track.index = index;
        Map<Long, List<long[]>> open = new HashMap<>(); // (channel,pitch) -> stack of (startTick, velocity)
        long tick = 0;
        int status = 0;
        int events = 0;
        boolean ended = false;

        while (tr.remaining() > 0 && !ended) {
            long delta = tr.varlen();
            tick += delta;
            int b = tr.u8();
            if (b < 0x80) {
                if (status == 0) throw new ParseException("running status with nothing running");
                tr.pushBack();
                b = status;
            } else if (b < 0xF0) {
                status = b;
            }
            events++;
            if (events > MAX_EVENTS) throw new ParseException("too many events in one track");

            int type = b & 0xF0;
            int ch = b & 0x0F;
            switch (b) {
                case 0xFF: {
                    int meta = tr.u8();
                    long mlen = tr.varlen();
                    if (mlen > tr.remaining()) throw new ParseException("meta event is truncated");
                    switch (meta) {
                        case 0x2F:
                            ended = true;
                            tr.skip((int) mlen);
                            break;
                        case 0x51:
                            if (mlen != 3) throw new ParseException("bad tempo event");
                            int us = (tr.u8() << 16) | (tr.u8() << 8) | tr.u8();
                            tempos.add(new TempoChange(tick, us));
                            break;
                        case 0x58:
                            if (mlen >= 2) {
                                timeSigNum = tr.u8();
                                int denPow = tr.u8();
                                timeSigDen = 1 << Math.min(denPow, 6);
                                tr.skip((int) mlen - 2);
                            } else {
                                tr.skip((int) mlen);
                            }
                            break;
                        case 0x59:
                            if (mlen >= 1) {
                                int sf = tr.u8();
                                if (sf >= 128) sf -= 256;
                                int minor = mlen >= 2 ? tr.u8() : 0;
                                if (mlen > 2) tr.skip((int) mlen - 2);
                                keySig = keyName(sf, minor);
                            } else {
                                tr.skip((int) mlen);
                            }
                            break;
                        case 0x03:
                            track.name = tr.text((int) Math.min(mlen, 256));
                            if (mlen > 256) tr.skip((int) (mlen - 256));
                            break;
                        default:
                            tr.skip((int) mlen);
                    }
                    break;
                }
                case 0xF0:
                case 0xF7: {
                    long slen = tr.varlen();
                    if (slen > tr.remaining()) throw new ParseException("SysEx event is truncated");
                    tr.skip((int) slen);
                    break;
                }
                default: {
                    switch (type) {
                        case 0x80:
                        case 0x90: {
                            int pitch = tr.u8();
                            int vel = tr.u8();
                            boolean on = type == 0x90 && vel > 0;
                            track.channels[ch] = true;
                            long key = (long) ch * 128 + pitch;
                            if (on) {
                                open.computeIfAbsent(key, k -> new ArrayList<>())
                                        .add(new long[]{(tick << 8) | vel, 0});
                            } else {
                                List<long[]> stack = open.get(key);
                                if (stack != null && !stack.isEmpty()) {
                                    long[] started = stack.remove(stack.size() - 1);
                                    Note n = new Note();
                                    n.track = index;
                                    n.channel = ch;
                                    n.pitch = pitch;
                                    n.startTick = started[0] >> 8;
                                    n.velocity = (int) (started[0] & 0xFF);
                                    n.endTick = Math.max(tick, n.startTick);
                                    notes.add(n);
                                    track.noteCount++;
                                    if (track.minPitch < 0 || pitch < track.minPitch) track.minPitch = pitch;
                                    if (pitch > track.maxPitch) track.maxPitch = pitch;
                                }
                            }
                            break;
                        }
                        case 0xA0:
                        case 0xB0:
                        case 0xE0: {
                            int d1 = tr.u8();
                            int d2 = tr.u8();
                            channelEvents.add(new ChannelEvent(tick, index,
                                    new byte[]{(byte) b, (byte) d1, (byte) d2}));
                            track.channels[ch] = true;
                            break;
                        }
                        case 0xC0: {
                            int program = tr.u8();
                            channelEvents.add(new ChannelEvent(tick, index,
                                    new byte[]{(byte) b, (byte) program}));
                            track.channels[ch] = true;
                            if (!track.programs.contains(program)) track.programs.add(program);
                            break;
                        }
                        case 0xD0: {
                            int d1 = tr.u8();
                            channelEvents.add(new ChannelEvent(tick, index,
                                    new byte[]{(byte) b, (byte) d1}));
                            track.channels[ch] = true;
                            break;
                        }
                        default:
                            throw new ParseException("unknown event byte 0x" + Integer.toHexString(b));
                    }
                }
            }
        }
        // Notes never switched off ring until the track's last tick.
        for (Map.Entry<Long, List<long[]>> e : open.entrySet()) {
            for (long[] started : e.getValue()) {
                Note n = new Note();
                n.track = index;
                n.channel = (int) (e.getKey() / 128);
                n.pitch = (int) (e.getKey() % 128);
                n.startTick = started[0] >> 8;
                n.velocity = (int) (started[0] & 0xFF);
                n.endTick = tick;
                notes.add(n);
                track.noteCount++;
                if (track.minPitch < 0 || n.pitch < track.minPitch) track.minPitch = n.pitch;
                if (n.pitch > track.maxPitch) track.maxPitch = n.pitch;
            }
        }
        track.endTick = tick;
        if (tick > durationTicks) durationTicks = tick;
        tracks.add(track);
        return events;
    }

    private void finishTiming() {
        List<TempoChange> sorted = new ArrayList<>(tempos);
        sorted.sort(Comparator.comparingLong(t -> t.tick));
        tempos.clear();
        tempos.add(new TempoChange(0, 500000));
        for (TempoChange t : sorted) {
            if (t.tick == 0 && tempos.size() == 1) {
                tempos.set(0, t);
            } else {
                tempos.add(t);
            }
        }
        for (Note n : notes) {
            n.startSec = tickToSeconds(n.startTick);
            n.endSec = tickToSeconds(n.endTick);
        }
        durationSeconds = tickToSeconds(durationTicks);
    }

    public double tickToSeconds(long tick) {
        if (smpte) return tick / smpteTicksPerSecond;
        double sec = 0;
        long prevTick = 0;
        int us = 500000;
        for (TempoChange t : tempos) {
            if (t.tick >= tick) break;
            sec += (t.tick - prevTick) * (us / 1_000_000.0) / ticksPerQuarter;
            prevTick = t.tick;
            us = t.usPerQuarter;
        }
        sec += (tick - prevTick) * (us / 1_000_000.0) / ticksPerQuarter;
        return sec;
    }

    private static String keyName(int sf, int minor) {
        String base;
        if (sf >= -7 && sf <= 7) {
            base = KEY_NAMES[sf + 7];
        } else {
            return "";
        }
        if (minor == 1) {
            base = base.replace(" major", " minor");
        }
        return base;
    }

    /** Bounded big-endian reader over the byte array. */
    static final class Reader {
        private final byte[] d;
        private final int end;
        private int pos;

        Reader(byte[] d) {
            this(d, 0, d.length);
        }

        Reader(byte[] d, int start, int end) {
            this.d = d;
            this.pos = start;
            this.end = end;
        }

        int remaining() {
            return end - pos;
        }

        void pushBack() {
            if (pos <= 0) throw new IllegalStateException("pushback at start");
            pos--;
        }

        int u8() throws ParseException {
            if (pos >= end) throw new ParseException("unexpected end of file");
            return d[pos++] & 0xFF;
        }

        int u16() throws ParseException {
            return (u8() << 8) | u8();
        }

        long u32() throws ParseException {
            return ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | u8();
        }

        long varlen() throws ParseException {
            long v = 0;
            for (int i = 0; i < 4; i++) {
                int b = u8();
                v = (v << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) return v;
            }
            throw new ParseException("variable-length number runs past 4 bytes");
        }

        void skip(int n) throws ParseException {
            if (n < 0 || n > end - pos) throw new ParseException("unexpected end of file");
            pos += n;
        }

        String text(int n) throws ParseException {
            if (n > end - pos) throw new ParseException("unexpected end of file");
            String s = new String(d, pos, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            pos += n;
            return s;
        }

        Reader subReader(int len) throws ParseException {
            if (len > end - pos) throw new ParseException("unexpected end of file");
            return new Reader(d, pos, pos + len);
        }
    }
}
