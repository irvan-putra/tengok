package com.vanipy.tengok.midi;

/**
 * The show-off letter: a live rank that rises as you play well and drops when
 * notes slip past, in the spirit of stylish-action game ranks. It combines
 * accuracy (perfect counts full, good half, a miss nothing) with a streak
 * bonus of 2 points per consecutive hit, capped at 10 - so a miss hurts
 * twice: accuracy falls and the bonus resets. Pure rules, no Android.
 */
public final class GameRank {
    public static final int STREAK_CAP = 10;
    public static final int BONUS_PER_STREAK = 2;

    private GameRank() {
    }

    public static int bonusPct(int streak) {
        return Math.min(Math.max(streak, 0), STREAK_CAP) * BONUS_PER_STREAK;
    }

    /** accuracyPct is 0..100 as GameScoring.accuracy() reports it. */
    public static String rank(int accuracyPct, int streak) {
        int v = accuracyPct + bonusPct(streak);
        if (v >= 100) return "SSS";
        if (v >= 92) return "SS";
        if (v >= 85) return "S";
        if (v >= 75) return "A";
        if (v >= 60) return "B";
        if (v >= 40) return "C";
        return "D";
    }
}
