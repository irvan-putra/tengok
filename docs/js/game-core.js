/* tengok web - game rules and keyboard geometry.
 * Faithful ports of GameScoring.java, GameRank.java and KeyWindow.java.
 * No DOM, no dependencies; also loadable in node for tests. */
(function (root) {
"use strict";

/* --- GameRank: accuracy plus a streak bonus, stylish-action letters. --- */
const STREAK_CAP = 10;
const BONUS_PER_STREAK = 2;
const UNRANKED = "-";

function bonusPct(streak) {
  return Math.min(Math.max(streak, 0), STREAK_CAP) * BONUS_PER_STREAK;
}

function rank(accuracyPct, streak) {
  const v = accuracyPct + bonusPct(streak);
  if (v >= 100) return "SSS";
  if (v >= 92) return "SS";
  if (v >= 85) return "S";
  if (v >= 75) return "A";
  if (v >= 60) return "B";
  if (v >= 40) return "C";
  return "D";
}

/* --- GameScoring: every note judged on its own clock. --- */
const PERFECT_MS = 100;
/* A tap this far before the note still counts. Early taps are rare. */
const GOOD_EARLY_MS = 200;
/* A tap this far after the note still counts - the common human case.
 * The Good reach is asymmetric on purpose (same tuning as the Android
 * app): a real fingertip lands late far more often than early. A note
 * stays hittable for as long as its bar is on the hit line: from
 * GOOD_EARLY_MS before its onset until GOOD_LATE_MS after its END, so a
 * sustained pad note scores for its whole sustain instead of dying
 * silently at onset + GOOD_LATE_MS while it still looks playable. */
const GOOD_LATE_MS = 400;
const PENDING = 0;
const PERFECT = 1;
const GOOD = 2;
const MISS = 3;

class GameScoring {
  constructor(midi, track) {
    this.midi = midi;
    this.track = track;
    this.noteIdx = [];
    for (let j = 0; j < midi.notes.length; j++) {
      if (midi.notes[j].track === track) this.noteIdx.push(j);
    }
    this.state = new Array(this.noteIdx.length).fill(PENDING);
    this.perfect = 0;
    this.good = 0;
    this.miss = 0;
    this.streak = 0;
    this.bestStreak = 0;
    this.judged = 0;
  }

  total() { return this.noteIdx.length; }
  judgedNote(i) { return this.midi.notes[this.noteIdx[i]]; }
  stateOf(i) { return this.state[i]; }
  score() { return this.perfect * 100 + this.good * 50; }

  rankLive() {
    if (this.judged === 0) return UNRANKED;
    return rank(this.accuracy(), this.streak);
  }

  rankFinal() {
    if (this.judged === 0) return UNRANKED;
    return rank(this.accuracy(), this.bestStreak);
  }

  accuracy() {
    if (this.judged === 0) return 100;
    return Math.floor((this.perfect * 100 + this.good * 50) / this.judged);
  }

  finished() { return this.judged === this.noteIdx.length; }

  /* A key struck at timeMs (song clock). Grades the nearest still-pending
   * note of this pitch whose bar is on the hit line, if any. */
  strike(pitch, timeMs) {
    let best = -1;
    let bestAbs = Infinity;
    for (let i = 0; i < this.noteIdx.length; i++) {
      if (this.state[i] !== PENDING) continue;
      const n = this.midi.notes[this.noteIdx[i]];
      if (n.pitch !== pitch) continue;
      const signed = timeMs - n.startSec * 1000.0; // +: late, -: early
      if (signed < -GOOD_EARLY_MS) continue; // not reached yet
      if (timeMs > n.endSec * 1000.0 + GOOD_LATE_MS) continue; // bar gone
      const d = Math.abs(signed);
      if (d < bestAbs) {
        bestAbs = d;
        best = i;
      }
    }
    if (best < 0) return PENDING;
    const grade = bestAbs <= PERFECT_MS ? PERFECT : GOOD;
    this.state[best] = grade;
    if (grade === PERFECT) this.perfect++; else this.good++;
    this.streak++;
    if (this.streak > this.bestStreak) this.bestStreak = this.streak;
    this.judged++;
    return grade;
  }

  /* Advances the clock: pending notes whose bar has left the line become misses. */
  tick(nowMs) {
    for (let i = 0; i < this.noteIdx.length; i++) {
      if (this.state[i] !== PENDING) continue;
      const n = this.midi.notes[this.noteIdx[i]];
      if (n.endSec * 1000.0 + GOOD_LATE_MS < nowMs) {
        this.state[i] = MISS;
        this.miss++;
        this.streak = 0;
        this.judged++;
      }
    }
  }

  noteStartMs(i) {
    if (i < 0 || i >= this.noteIdx.length) return -1;
    return this.midi.notes[this.noteIdx[i]].startSec * 1000.0;
  }

  firstStartMs() {
    let best = -1;
    for (let i = 0; i < this.noteIdx.length; i++) {
      const s = this.noteStartMs(i);
      if (best < 0 || s < best) best = s;
    }
    return best;
  }

  lastStartMs() {
    let last = -1;
    for (let i = 0; i < this.noteIdx.length; i++) {
      const s = this.noteStartMs(i);
      if (s > last) last = s;
    }
    return last;
  }

  /* Index of the still-pending note that starts soonest; -1 when done. */
  nextPendingIndex() {
    let best = -1;
    let bestMs = 0;
    for (let i = 0; i < this.noteIdx.length; i++) {
      if (this.state[i] !== PENDING) continue;
      const s = this.noteStartMs(i);
      if (best < 0 || s < bestMs) {
        bestMs = s;
        best = i;
      }
    }
    return best;
  }

  notePitch(i) {
    if (i < 0 || i >= this.noteIdx.length) return -1;
    return this.midi.notes[this.noteIdx[i]].pitch;
  }
}

/* --- KeyWindow: white keys never shrink below a usable width; the board
 * pans instead of squashing. Coordinates are CSS pixels on the web, so the
 * minimum is 44px (the Android minimum is 44dp). --- */
const MIN_WHITE_PX = 44;
const BLACK_STEPS = [1, 3, 6, 8, 10];

function isBlack(pitch) {
  return BLACK_STEPS.includes(pitch % 12);
}

function whitesUpTo(lo, pitchInclusive) {
  let c = 0;
  for (let p = lo; p <= pitchInclusive; p++) if (!isBlack(p)) c++;
  return c;
}

class KeyWindow {
  constructor(lo, hi, viewW) {
    this.lo = lo;
    this.hi = hi;
    this.viewW = viewW;
    const fit = viewW > 0 ? viewW / this.whiteCount() : 0;
    this.whiteW = Math.max(fit, MIN_WHITE_PX);
  }

  whiteCount() { return whitesUpTo(this.lo, this.hi); }
  contentW() { return this.whiteW * this.whiteCount(); }
  maxViewport() { return Math.max(0, this.contentW() - this.viewW); }
  clampViewport(vx) { return Math.min(Math.max(vx, 0), this.maxViewport()); }

  leftPx(pitch) {
    if (!isBlack(pitch)) return (whitesUpTo(this.lo, pitch) - 1) * this.whiteW;
    return this.centerPx(pitch) - this.blackW() / 2;
  }

  rightPx(pitch) {
    if (!isBlack(pitch)) return whitesUpTo(this.lo, pitch) * this.whiteW;
    return this.centerPx(pitch) + this.blackW() / 2;
  }

  centerPx(pitch) {
    if (!isBlack(pitch)) return (whitesUpTo(this.lo, pitch) - 0.5) * this.whiteW;
    return whitesUpTo(this.lo, pitch - 1) * this.whiteW;
  }

  blackW() { return this.whiteW * 0.62; }

  rangeVisible(loPitch, hiPitch, vx) {
    return this.leftPx(loPitch) - vx >= 0 && this.rightPx(hiPitch) - vx <= this.viewW;
  }

  viewportCentering(loPitch, hiPitch) {
    const mid = (this.leftPx(loPitch) + this.rightPx(hiPitch)) / 2;
    return this.clampViewport(mid - this.viewW / 2);
  }

  /* Pitch at a board x position, or -1 when between/outside keys. */
  pitchAt(x) {
    if (x < 0 || x >= this.contentW()) return -1;
    for (let p = this.lo; p <= this.hi; p++) {
      if (isBlack(p) && x >= this.leftPx(p) && x <= this.rightPx(p)) return p;
    }
    for (let p = this.lo; p <= this.hi; p++) {
      if (!isBlack(p) && x >= this.leftPx(p) && x < this.rightPx(p)) return p;
    }
    return -1;
  }
}

root.TengokGame = {
  STREAK_CAP: STREAK_CAP,
  BONUS_PER_STREAK: BONUS_PER_STREAK,
  UNRANKED: UNRANKED,
  bonusPct: bonusPct,
  rank: rank,
  PERFECT_MS: PERFECT_MS,
  GOOD_EARLY_MS: GOOD_EARLY_MS,
  GOOD_LATE_MS: GOOD_LATE_MS,
  PENDING: PENDING,
  PERFECT: PERFECT,
  GOOD: GOOD,
  MISS: MISS,
  GameScoring: GameScoring,
  MIN_WHITE_PX: MIN_WHITE_PX,
  isBlack: isBlack,
  whitesUpTo: whitesUpTo,
  KeyWindow: KeyWindow,
};
})(typeof window !== "undefined" ? window : globalThis);
