package com.vanipy.tengok.midi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Game-scoring ordering helpers: late entrances and unordered file events. */
public class GameScoringTest {

    private static MidiFile song(double... startSecs) {
        MidiFile m = new MidiFile();
        for (double s : startSecs) {
            MidiFile.Note n = new MidiFile.Note();
            n.track = 1;
            n.pitch = 60;
            n.velocity = 90;
            n.startSec = s;
            n.endSec = s + 0.4;
            m.notes.add(n);
        }
        return m;
    }

    @Test
    public void firstStartFindsEarliestEvenWhenFileOrderDiffers() {
        GameScoring s = new GameScoring(song(30.0, 12.0, 25.0), 1);
        assertEquals(12000.0, s.firstStartMs(), 0.001);
    }

    @Test
    public void firstStartIsMinusOneForEmptyTrack() {
        GameScoring s = new GameScoring(song(), 1);
        assertEquals(-1.0, s.firstStartMs(), 0.001);
        assertEquals(-1, s.nextPendingIndex());
    }

    @Test
    public void nextPendingPicksSoonestNotFirstInFile() {
        GameScoring s = new GameScoring(song(30.0, 12.0, 25.0), 1);
        assertEquals(1, s.nextPendingIndex()); // the 12 s note, not the 30 s one
    }

    @Test
    public void nextPendingSkipsJudgedNotes() {
        GameScoring s = new GameScoring(song(12.0, 13.0, 30.0), 1);
        s.strike(60, 12000); // perfect on the first
        assertEquals(1, s.nextPendingIndex());
        s.tick(13801); // second note's bar leaves the line -> miss
        assertEquals(2, s.nextPendingIndex());
        s.strike(60, 30000);
        assertEquals(-1, s.nextPendingIndex());
    }

    @Test
    public void otherTracksDoNotPolluteThePart() {
        MidiFile m = song(5.0);
        MidiFile.Note other = new MidiFile.Note();
        other.track = 2;
        other.pitch = 40;
        other.velocity = 80;
        other.startSec = 1.0;
        other.endSec = 1.5;
        m.notes.add(other);
        GameScoring s = new GameScoring(m, 1);
        assertEquals(1, s.total());
        assertEquals(5000.0, s.firstStartMs(), 0.001);
    }

    @Test
    public void judgedNoteAlignsWithStateIndex() {
        GameScoring s = new GameScoring(song(12.0, 13.0, 29.0), 1);
        s.strike(60, 12000);
        assertEquals(GameScoring.PERFECT, s.stateOf(0));
        assertEquals(12000.0, s.judgedNote(0).startSec * 1000.0, 0.001);
        assertEquals(29000.0, s.judgedNote(2).startSec * 1000.0, 0.001);
    }

    @Test
    public void unrankedUntilANoteIsJudged() {
        GameScoring s = new GameScoring(song(12.0, 13.0), 1);
        // zero judged notes is zero evidence: no top letter before playing,
        // and an abandoned round earns no grade either
        assertEquals(GameScoring.UNRANKED, s.rankLive());
        assertEquals(GameScoring.UNRANKED, s.rankFinal());
    }

    @Test
    public void rankFollowsJudgingAndMissesPullItDown() {
        GameScoring s = new GameScoring(song(12.0, 13.0, 14.0, 15.0), 1);
        s.strike(60, 12000); // perfect: 100% + streak 1
        assertEquals("SSS", s.rankLive());
        s.tick(13801); // the 13 s note's bar leaves: accuracy halves, streak resets
        assertEquals("C", s.rankLive());
        s.strike(60, 14000); // perfect: 66% + streak 1
        assertEquals("B", s.rankLive());
        s.tick(15801); // the last note's bar leaves: 50% + best-streak-1 bonus
        assertEquals("C", s.rankFinal());
    }

    @Test
    public void lastStartAndBadTimesDescribeThePart() {
        GameScoring s = new GameScoring(song(12.0, 29.0, 12.5), 1);
        assertEquals(29000.0, s.lastStartMs(), 0.001);
        assertEquals(0, s.badTimeCount());
        MidiFile m = song(3.0);
        MidiFile.Note broken = new MidiFile.Note();
        broken.track = 1;
        broken.pitch = 60;
        broken.startSec = Double.NaN;
        broken.endSec = Double.NaN;
        m.notes.add(broken);
        GameScoring t = new GameScoring(m, 1);
        assertEquals(1, t.badTimeCount());
    }

    @Test
    public void lateTapWithinTheWidenedWindowStillScores() {
        GameScoring s = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.GOOD, s.strike(60, 12000 + 350)); // late, human
    }

    @Test
    public void lateTapAtTheWindowEdgeStillScores() {
        GameScoring s = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.GOOD, s.strike(60, 12000 + GameScoring.GOOD_LATE_MS));
    }

    @Test
    public void tapDuringTheSustainStillScores() {
        // 401 ms past the onset the Good-onset window has closed, but the
        // bar (end 12.4 s) is still on the line: the note must score.
        GameScoring s = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.GOOD, s.strike(60, 12000 + GameScoring.GOOD_LATE_MS + 1));
    }

    @Test
    public void tapAfterTheBarIsGoneFindsNoNote() {
        GameScoring s = new GameScoring(song(12.0), 1); // end 12.4 s
        assertEquals(GameScoring.PENDING,
                s.strike(60, 12400 + GameScoring.GOOD_LATE_MS + 1));
    }

    @Test
    public void longNoteStaysHittableForItsWholeSustain() {
        MidiFile m = new MidiFile();
        MidiFile.Note pad = new MidiFile.Note();
        pad.track = 1;
        pad.pitch = 60;
        pad.velocity = 90;
        pad.startSec = 12.0;
        pad.endSec = 20.0; // an 8 s warm pad, the cantina case
        m.notes.add(pad);
        GameScoring s = new GameScoring(m, 1);
        s.tick(15000); // 3 s into the sustain: not a miss while the bar sits on the line
        assertEquals(0, s.miss());
        assertEquals(GameScoring.GOOD, s.strike(60, 15000)); // caught mid-sustain
        assertEquals(0, s.perfect());
        assertEquals(1, s.good());
        GameScoring untouched = new GameScoring(m, 1);
        untouched.tick(20400); // end + GOOD_LATE_MS, edge: still pending
        assertEquals(0, untouched.miss());
        untouched.tick(20401); // bar gone: now it misses
        assertEquals(1, untouched.miss());
        assertEquals(GameScoring.PENDING, untouched.strike(60, 20401));
    }

    @Test
    public void earlyTapUsesTheTighterEarlyWindow() {
        GameScoring s = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.GOOD, s.strike(60, 12000 - GameScoring.GOOD_EARLY_MS));
        GameScoring s2 = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.PENDING, s2.strike(60, 12000 - GameScoring.GOOD_EARLY_MS - 50));
    }

    @Test
    public void perfectWindowIsUnchangedAndSymmetric() {
        GameScoring s = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.PERFECT, s.strike(60, 12000 + GameScoring.PERFECT_MS));
        GameScoring s2 = new GameScoring(song(12.0), 1);
        assertEquals(GameScoring.PERFECT, s2.strike(60, 12000 - GameScoring.PERFECT_MS));
    }

    @Test
    public void missClosesOnlyAfterTheBarLeaves() {
        GameScoring s = new GameScoring(song(12.0), 1); // end 12.4 s
        s.tick(12000 + GameScoring.GOOD_LATE_MS);
        assertEquals(0, s.miss()); // mid-sustain: the bar is still on the line
        s.tick(12400 + GameScoring.GOOD_LATE_MS);
        assertEquals(0, s.miss()); // still hittable at the very edge
        s.tick(12400 + GameScoring.GOOD_LATE_MS + 1);
        assertEquals(1, s.miss());
    }
}
