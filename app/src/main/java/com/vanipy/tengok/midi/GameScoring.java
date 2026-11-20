package com.vanipy.tengok.midi;

import java.util.List;

/**
 * Game-mode hit judging. Every note in the player's track is judged on its
 * own clock: a tap (or a MIDI keyboard note-on) within +-PERFECT_MS of the
 * note's start is Perfect, within +-GOOD_MS is Good, anything later is a
 * Miss. Because notes are judged independently, a chord rolled slightly
 * late finger by finger - the way people actually play - scores each note
 * on its own merit instead of demanding robotic simultaneity. Velocity
 * from the tap/source is never judged; dynamics are expression, not error.
 */
public final class GameScoring {
    public static final long PERFECT_MS = 100;
    public static final long GOOD_MS = 220;

    public static final int PENDING = 0;
    public static final int PERFECT = 1;
    public static final int GOOD = 2;
    public static final int MISS = 3;

    private final MidiFile midi;
    private final int track;
    /** indexes into midi.notes for the judged track, in file order */
    private final int[] noteIdx;
    private final int[] state;
    private int perfect;
    private int good;
    private int miss;
    private int streak;
    private int bestStreak;
    private int judged;

    public GameScoring(MidiFile midi, int track) {
        this.midi = midi;
        this.track = track;
        int count = 0;
        for (MidiFile.Note n : midi.notes) if (n.track == track) count++;
        noteIdx = new int[count];
        state = new int[count];
        int i = 0;
        for (int j = 0; j < midi.notes.size(); j++) {
            if (midi.notes.get(j).track == track) noteIdx[i++] = j;
        }
    }

    public int total() {
        return noteIdx.length;
    }

    public int stateOf(int i) {
        return state[i];
    }

    public int perfect() { return perfect; }
    public int good() { return good; }
    public int miss() { return miss; }
    public int streak() { return streak; }
    public int bestStreak() { return bestStreak; }
    public int judged() { return judged; }

    public int score() {
        return perfect * 100 + good * 50;
    }

    /** 0..100 percentage of judged notes hit (perfect counts full, good half). */
    public int accuracy() {
        if (judged == 0) return 100;
        return (perfect * 100 + good * 50) / judged;
    }

    public boolean finished() {
        return judged == noteIdx.length;
    }

    /**
     * A key was struck at timeMs (song clock). Grades the nearest still
     * pending note of this pitch within the GOOD window, if any.
     * @return PENDING (no matching note), PERFECT or GOOD.
     */
    public int strike(int pitch, double timeMs) {
        int best = -1;
        double bestAbs = GOOD_MS + 1;
        for (int i = 0; i < noteIdx.length; i++) {
            if (state[i] != PENDING) continue;
            MidiFile.Note n = midi.notes.get(noteIdx[i]);
            if (n.pitch != pitch) continue;
            double d = Math.abs(n.startSec * 1000.0 - timeMs);
            if (d <= GOOD_MS && d < bestAbs) {
                bestAbs = d;
                best = i;
            }
        }
        if (best < 0) return PENDING;
        int grade = bestAbs <= PERFECT_MS ? PERFECT : GOOD;
        state[best] = grade;
        if (grade == PERFECT) perfect++; else good++;
        streak++;
        if (streak > bestStreak) bestStreak = streak;
        judged++;
        return grade;
    }

    /** Advances the clock: pending notes whose window has closed become misses. */
    public void tick(double nowMs) {
        for (int i = 0; i < noteIdx.length; i++) {
            if (state[i] != PENDING) continue;
            MidiFile.Note n = midi.notes.get(noteIdx[i]);
            if (n.startSec * 1000.0 + GOOD_MS < nowMs) {
                state[i] = MISS;
                miss++;
                streak = 0;
                judged++;
            }
        }
    }

    /** Song-time (ms) of the i-th judged note's start; -1 when out of range. */
    public double noteStartMs(int i) {
        if (i < 0 || i >= noteIdx.length) return -1;
        return midi.notes.get(noteIdx[i]).startSec * 1000.0;
    }

    /** Song-time (ms) of the track's earliest note, wherever it sits in the song; -1 when empty. */
    public double firstStartMs() {
        double best = -1;
        for (int i = 0; i < noteIdx.length; i++) {
            double s = noteStartMs(i);
            if (best < 0 || s < best) best = s;
        }
        return best;
    }

    /**
     * Index of the still-pending note that starts soonest, regardless of file
     * order (a track's events are not guaranteed to be time-ordered); -1 when
     * every note has been judged.
     */
    public int nextPendingIndex() {
        int best = -1;
        double bestMs = 0;
        for (int i = 0; i < noteIdx.length; i++) {
            if (state[i] != PENDING) continue;
            double s = noteStartMs(i);
            if (best < 0 || s < bestMs) {
                bestMs = s;
                best = i;
            }
        }
        return best;
    }

    public int notePitch(int i) {
        if (i < 0 || i >= noteIdx.length) return -1;
        return midi.notes.get(noteIdx[i]).pitch;
    }

    public List<MidiFile.Note> allNotes() {
        return midi.notes;
    }
}
