package com.vanipy.tengok.midi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The letter-rank rule: accuracy (perfect 100, good 50, miss 0) plus 2 points
 * per streak step, capped at 10. A miss therefore pulls the rank down twice.
 */
public class GameRankTest {
    @Test
    public void thresholds() {
        assertEquals("SSS", GameRank.rank(100, 0));
        assertEquals("SS", GameRank.rank(92, 0));
        assertEquals("S", GameRank.rank(91, 0));
        assertEquals("S", GameRank.rank(85, 0));
        assertEquals("A", GameRank.rank(84, 0));
        assertEquals("A", GameRank.rank(75, 0));
        assertEquals("B", GameRank.rank(74, 0));
        assertEquals("B", GameRank.rank(60, 0));
        assertEquals("C", GameRank.rank(59, 0));
        assertEquals("C", GameRank.rank(40, 0));
        assertEquals("D", GameRank.rank(39, 0));
        assertEquals("D", GameRank.rank(0, 0));
    }

    @Test
    public void streakLiftsTheRank() {
        assertEquals("A", GameRank.rank(70, 3));   // 70 + 6
        assertEquals("S", GameRank.rank(80, 3));   // 80 + 6
        assertEquals("SSS", GameRank.rank(85, 10)); // 85 + 20
    }

    @Test
    public void streakBonusCapsAtTen() {
        assertEquals(20, GameRank.bonusPct(10));
        assertEquals(20, GameRank.bonusPct(99));
        assertEquals(0, GameRank.bonusPct(-3));
        assertEquals(GameRank.rank(85, 10), GameRank.rank(85, 99));
    }

    @Test
    public void aMissCostsAccuracyAndTheWholeBonus() {
        // same raw accuracy: riding a streak reads two letters higher
        assertEquals("B", GameRank.rank(70, 0));
        assertEquals("S", GameRank.rank(70, 10));
    }
}
