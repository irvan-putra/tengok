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
        s.tick(13221); // second note's good window closes -> miss
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
        s.tick(13221); // the 13 s note's window closes: accuracy halves, streak resets
        assertEquals("C", s.rankLive());
        s.strike(60, 14000); // perfect: 66% + streak 1
        assertEquals("B", s.rankLive());
        s.tick(15221); // the last note slips: 50% + best-streak-1 bonus
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
}
