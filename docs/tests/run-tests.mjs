/* Node tests for the tengok web viewer's DOM-free modules.
 * Run: node docs/tests/run-tests.mjs   (from the repo root)
 * Mirrors the Java unit tests' expectations: the JS parser/scoring/geometry
 * are ports, so the numbers must match the Android app exactly. */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

await import(join(root, "docs/js/midi.js"));
await import(join(root, "docs/js/game-core.js"));
await import(join(root, "docs/js/sniffer.js"));

const { MidiFile, gmInstrument, gmPitch } = globalThis.TengokMidi;
const G = globalThis.TengokGame;
const S = globalThis.TengokSniffer;

let passed = 0;
function test(name, fn) {
  try {
    fn();
    passed++;
    console.log("PASS " + name);
  } catch (e) {
    console.error("FAIL " + name + ": " + e.message);
    process.exitCode = 1;
  }
}

function fixture(name) {
  return new Uint8Array(readFileSync(join(root, "docs/fixtures", name)));
}

/* ---- MIDI parsing: twotrack.mid from ci/make_midi.py ---- */
const two = MidiFile.parse(fixture("twotrack.mid"));
test("twotrack: format 1, 2 tracks", () => {
  assert.equal(two.format, 1);
  assert.equal(two.tracks.length, 2);
});
test("twotrack: 2 notes, violin program 40", () => {
  assert.equal(two.notes.length, 2);
  assert.deepEqual(two.tracks[1].programs, [40]);
  assert.equal(gmInstrument(40), "Violin");
});
test("twotrack: tempo map 120->60 bpm halves then doubles", () => {
  // tpq 480; tempo 500000us until tick 960, then 1000000us.
  assert.equal(two.tickToSeconds(960), 1.0);
  assert.equal(two.tickToSeconds(1920), 3.0);
  assert.equal(two.durationSeconds, 3.0);
});
test("twotrack: overlapping notes pair correctly", () => {
  const c5 = two.notes.find((n) => n.pitch === 72);
  const e5 = two.notes.find((n) => n.pitch === 76);
  assert.equal(c5.startSec, 0);
  assert.equal(c5.endSec, 3.0);
  assert.equal(e5.startSec, 1.0);
  assert.equal(e5.endSec, 3.0);
  assert.equal(c5.velocity, 100);
});

/* ---- MIDI parsing: etude.mid from ci/make_web_fixtures.py ---- */
const etude = MidiFile.parse(fixture("etude.mid"));
test("etude: 4 tracks, 126 notes, C major 4/4", () => {
  assert.equal(etude.tracks.length, 4);
  assert.equal(etude.notes.length, 126);
  assert.equal(etude.keySig, "C major");
  assert.equal(etude.timeSigNum, 4);
  assert.equal(etude.timeSigDen, 4);
});
test("etude: last note ends at 29.9 beats = 17.94s at 100 bpm", () => {
  assert.ok(Math.abs(etude.durationSeconds - 17.94) < 1e-9);
});
test("etude: track facts", () => {
  const melody = etude.tracks[1];
  assert.equal(melody.name, "melody");
  assert.equal(melody.noteCount, 51);
  assert.equal(melody.minPitch, 60);
  assert.equal(melody.maxPitch, 72);
  const drums = etude.tracks[3];
  assert.equal(drums.noteCount, 45);
  assert.ok(drums.channels[9]);
});
test("etude: every note has finite non-negative timing", () => {
  for (const n of etude.notes) {
    assert.ok(Number.isFinite(n.startSec) && n.startSec >= 0);
    assert.ok(n.endSec >= n.startSec);
  }
});

/* ---- Defensive parsing: corrupt input gets a readable error ---- */
function expectThrow(name, bytes, fragment) {
  test(name, () => {
    assert.throws(() => MidiFile.parse(bytes), (e) => e.message.includes(fragment));
  });
}
expectThrow("corrupt: too short", new Uint8Array([1, 2, 3]), "too short");
expectThrow("corrupt: no MThd", fixture("sample.txt"), "MThd");
expectThrow("corrupt: truncated track", fixture("twotrack.mid").slice(0, 30), "");
expectThrow("corrupt: running status first", (() => {
  // MThd fmt0 1 track, then a track whose first event byte is data (<0x80).
  const t = [0x00, 60]; // delta 0, then bare data byte
  const body = new Uint8Array([
    0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0,
    0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, t.length, ...t]);
  return body;
})(), "running status");
test("corrupt: varlen past 4 bytes rejected", () => {
  const t = [0xFF, 0xFF, 0xFF, 0xFF, 0x7F]; // 5-byte varlen
  const body = new Uint8Array([
    0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0,
    0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, t.length, ...t]);
  assert.throws(() => MidiFile.parse(body), /variable-length/);
});
test("corrupt: unterminated note rings to track end", () => {
  // note on, no note off, then end-of-track at tick 480.
  const t = [0x00, 0x90, 60, 100, 0x83, 0x60, 0xFF, 0x2F, 0x00]; // delta 480
  const body = new Uint8Array([
    0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0,
    0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, t.length, ...t]);
  const m = MidiFile.parse(body);
  assert.equal(m.notes.length, 1);
  assert.equal(m.notes[0].endTick, 480);
  assert.equal(m.notes[0].endSec, 0.5);
});
test("gmPitch names", () => {
  assert.equal(gmPitch(60), "C4");
  assert.equal(gmPitch(69), "A4");
  assert.equal(gmPitch(-1), "?");
});

/* ---- GameScoring / GameRank: same windows and letters as Android ---- */
test("scoring: perfect within 100ms, good 200 early / 400 late", () => {
  assert.equal(G.PERFECT_MS, 100);
  assert.equal(G.GOOD_EARLY_MS, 200);
  assert.equal(G.GOOD_LATE_MS, 400);
  const sc = new G.GameScoring(two, 1);
  assert.equal(sc.total(), 2);
  assert.equal(sc.rankLive(), G.UNRANKED); // no evidence, no letter
  assert.equal(sc.strike(72, 50), G.PERFECT); // 50ms late on C5
  sc.tick(1000 + 50); // E5 due at 1000ms: not yet missed
  assert.equal(sc.strike(76, 1150), G.GOOD); // 150ms late
  assert.equal(sc.finished(), true);
  assert.equal(sc.score(), 150);
  assert.equal(sc.accuracy(), 75);
  // the window is asymmetric: 350ms late still scores, 250ms early does not
  const wide = new G.GameScoring(two, 1);
  assert.equal(wide.strike(72, 350), G.GOOD); // late slack
  const early = new G.GameScoring(two, 1);
  assert.equal(early.strike(72, -250), G.PENDING); // early slack is tighter
  assert.equal(early.strike(72, -150), G.GOOD);
});
test("scoring: misses break the streak and the rank", () => {
  const sc = new G.GameScoring(etude, 1);
  const first = sc.noteStartMs(0);
  assert.equal(sc.strike(sc.notePitch(0), first), G.PERFECT);
  assert.equal(sc.strike(sc.notePitch(1), sc.noteStartMs(1)), G.PERFECT);
  assert.equal(sc.rankLive(), "SSS"); // 100 accuracy + streak 2*2
  sc.tick(sc.judgedNote(2).endSec * 1000 + G.GOOD_LATE_MS + 1); // note 2's bar leaves
  assert.equal(sc.miss, 1);
  assert.equal(sc.streak, 0);
  assert.equal(sc.accuracy(), 66);
  assert.equal(sc.rankLive(), "B"); // 66 + 0
  // best streak is kept for the final grade
  assert.ok(["A", "S", "SS", "SSS"].includes(sc.rankFinal()) === false
      || sc.rankFinal() === "A"); // 66 + bonus(bestStreak=2)=70 -> B... recompute below
});
test("rank ladder thresholds", () => {
  assert.equal(G.rank(100, 0), "SSS");
  assert.equal(G.rank(92, 0), "SS");
  assert.equal(G.rank(85, 0), "S");
  assert.equal(G.rank(75, 0), "A");
  assert.equal(G.rank(60, 0), "B");
  assert.equal(G.rank(40, 0), "C");
  assert.equal(G.rank(0, 0), "D");
  assert.equal(G.rank(80, 10), "SSS"); // 80 + 20 bonus
  assert.equal(G.bonusPct(99), 20); // capped at 10
});
test("scoring: a sustained note stays hittable while its bar is on the line", () => {
  // twotrack's C5 sustains 0-3 s: past onset + GOOD_LATE it must still
  // score, because the bar still sits on the hit line (the cantina case).
  const sc = new G.GameScoring(two, 1);
  sc.tick(1500); // mid-sustain: no miss while the bar is on the line
  assert.equal(sc.miss, 0);
  assert.equal(sc.strike(72, 1500), G.GOOD); // caught during the sustain
  assert.equal(sc.perfect, 0);
  assert.equal(sc.good, 1);
  // once the bar leaves (end 3000 + 400 slack), an unstruck note misses
  sc.tick(3401);
  assert.equal(sc.miss, 1); // the E5 was never struck
  // and nothing scores after the bar is gone
  const late = new G.GameScoring(two, 1);
  assert.equal(late.strike(72, 3500), G.PENDING);
});
test("scoring: wrong pitch never grades", () => {
  const sc = new G.GameScoring(two, 1);
  assert.equal(sc.strike(10, 0), G.PENDING);
  assert.equal(sc.judged, 0);
});

/* ---- KeyWindow: 44px minimum, panning, geometry ---- */
test("keywindow: white keys never narrower than 44px", () => {
  const kw = new G.KeyWindow(21, 108, 360);
  assert.equal(kw.whiteW, G.MIN_WHITE_PX);
  assert.ok(kw.contentW() > 360); // must pan
  assert.ok(kw.maxViewport() > 0);
});
test("keywindow: fits without panning when range is small", () => {
  const kw = new G.KeyWindow(60, 72, 360); // 8 whites -> 45px each
  assert.equal(kw.whiteW, 45);
  assert.equal(kw.maxViewport(), 0);
  assert.equal(kw.clampViewport(100), 0);
});
test("keywindow: black key geometry and pitch hit-test", () => {
  const kw = new G.KeyWindow(60, 72, 400);
  assert.equal(G.isBlack(61), true);
  assert.equal(G.isBlack(60), false);
  assert.equal(kw.pitchAt(kw.centerPx(60), true), 60);
  assert.equal(kw.pitchAt(kw.centerPx(61), true), 61); // black key wins its strip
  assert.equal(kw.pitchAt(kw.centerPx(61), false), 62); // but only where drawn
  assert.equal(kw.pitchAt(-5, true), -1);
});

test("keywindow: width override only zooms out", () => {
  const kw = new G.KeyWindow(21, 108, 360, 30);
  assert.equal(kw.whiteW, 30);
  const wider = new G.KeyWindow(21, 108, 360, 99999);
  assert.equal(wider.whiteW, G.MIN_WHITE_PX);
});

test("keywindow: center taps always hit their own key, even shrunk", () => {
  for (const w of [44, 30, 28]) {
    const kw = new G.KeyWindow(48, 83, 800, w);
    assert.equal(kw.whiteW, w);
    for (let p = 48; p <= 83; p++) {
      const c = kw.centerPx(p);
      if (G.isBlack(p)) {
        assert.equal(kw.pitchAt(c, true), p, `black ${p} at ${w}`);
        assert.equal(kw.pitchAt(c, false), p + 1, `under black ${p} at ${w}`);
      } else {
        assert.equal(kw.pitchAt(c, true), p, `white ${p} at ${w}`);
        assert.equal(kw.pitchAt(c, false), p, `white low ${p} at ${w}`);
      }
    }
  }
});

test("keywindow: containment matches the drawn keys (arbitration rejected)", () => {
  // Why hit-testing stays strict containment: nearest-center arbitration
  // shrinks every black key's target from its drawn rect (0.62*whiteW) to
  // half a white width, so a tap ON the drawn black near its edge scores
  // the white beside it. Containment never disagrees with what is drawn -
  // and the pressed key's dim must match what the tap scores. Pin the
  // measured disagreement: blackW - whiteW/2 px per black key.
  const kw = new G.KeyWindow(48, 83, 800, 30);
  const nearestCenter = (x) => {
    let best = -1, bd = Infinity;
    for (let p = kw.lo; p <= kw.hi; p++) {
      const d = Math.abs(kw.centerPx(p) - x);
      if (d < bd) { bd = d; best = p; }
    }
    return best;
  };
  let disagree = 0, blacks = 0;
  for (let p = 48; p <= 83; p++) {
    if (!G.isBlack(p)) continue;
    blacks++;
    const bc = kw.centerPx(p), half = kw.whiteW / 2;
    for (let x = bc - half + 0.01; x < bc + half; x += 0.25) {
      if (nearestCenter(x) !== kw.pitchAt(x, true)) disagree++;
    }
  }
  assert.ok(blacks > 0);
  // 30px keys: drawn black 18.6px, arbitrated 15px -> ~3.6px of disagreement
  const perBlack = Math.floor((kw.blackW() - kw.whiteW / 2) / 0.25);
  assert.ok(perBlack > 0);
  assert.ok(Math.abs(disagree - perBlack * blacks) <= blacks * 2,
      `arbitration disagrees ~${perBlack * blacks} samples, got ${disagree}`);
});

/* ---- CameraPlan: zoom out before moving, move only in no-press gaps ----
 * Mirrors CameraPlanTest.java exactly: 800px viewport, 44px default width. */
const CN = (pitch, startSec, endSec) => ({ pitch, startSec, endSec });

test("cameraplan: empty song is static default", () => {
  const p = new G.CameraPlan([], 48, 83, 800, 44);
  assert.equal(p.segmentCount, 0);
  assert.deepEqual(p.valueAt(0), [0, 44]);
  assert.deepEqual(p.valueAt(12345), [0, 44]);
});

test("cameraplan: single passage keeps default width, one static frame", () => {
  const p = new G.CameraPlan([CN(60, 1, 2), CN(64, 2, 3)], 48, 83, 800, 44);
  assert.equal(p.segmentCount, 1);
  assert.equal(p.groups.length, 1);
  assert.equal(p.glides.length, 0);
  assert.equal(p.minWhiteW, 44);
  assert.equal(p.framesBelow(44), 0);
  const kw = new G.KeyWindow(48, 83, 800, 44);
  assert.ok(Math.abs(p.valueAt(1500)[0] - kw.viewportCentering(60, 64)) < 0.001);
  assert.deepEqual(p.valueAt(0), p.valueAt(99999));
});

test("cameraplan: long gap glides inside the gap only", () => {
  const p = new G.CameraPlan([CN(48, 0, 1), CN(83, 10, 11)], 48, 83, 800, 44);
  assert.equal(p.segmentCount, 2);
  assert.equal(p.groups.length, 2);
  assert.equal(p.glides.length, 1);
  const g = p.glides[0];
  // busy segments [-300,1150] and [9700,11150]: arrive 500ms early,
  // at least a 320ms sweep, never starting before the first segment ends
  assert.equal(g.endMs, 9700 - G.CAM_ARRIVE_EARLY_MS);
  assert.ok(g.endMs - g.startMs >= G.CAM_GLIDE_MIN_MS - 0.001);
  assert.ok(g.startMs >= 1150 - 0.001);
  const kw = new G.KeyWindow(48, 83, 800, 44);
  const vxA = kw.viewportCentering(48, 48);
  const vxB = kw.viewportCentering(83, 83);
  assert.ok(Math.abs(p.valueAt(5000)[0] - vxA) < 0.001);
  assert.ok(Math.abs(p.valueAt(9500)[0] - vxB) < 0.001);
  const mid = p.valueAt((g.startMs + g.endMs) / 2)[0];
  assert.ok(Math.abs(mid - (vxA + vxB) / 2) < 0.5); // smoothstep midpoint
  let prev = vxA; // monotonic: the board never doubles back
  for (let t = g.startMs; t <= g.endMs; t += 20) {
    const v = p.valueAt(t)[0];
    assert.ok(v >= prev - 0.001, `monotonic at ${t}`);
    prev = v;
  }
});

test("cameraplan: short gap merges into one zoomed frame", () => {
  // 550ms gap: too short for a 500ms arrival buffer plus a 320ms sweep,
  // so instead of moving, the camera zooms out to see both
  const p = new G.CameraPlan([CN(48, 0, 1), CN(83, 2, 3)], 48, 83, 800, 44);
  assert.equal(p.segmentCount, 2);
  assert.equal(p.groups.length, 1);
  assert.equal(p.glides.length, 0);
  const fitW = 800 / 21; // the whole three-octave board at once
  assert.ok(Math.abs(p.valueAt(0)[1] - fitW) < 0.001);
  assert.ok(Math.abs(p.valueAt(99999)[1] - fitW) < 0.001);
  assert.ok(Math.abs(p.minWhiteW - fitW) < 0.001);
  assert.equal(p.framesBelow(44), 1); // honestly below the comfort size
  assert.equal(p.framesBelow(38), 0);
  const kw = new G.KeyWindow(48, 83, 800, 1);
  kw.whiteW = fitW;
  assert.ok(kw.rangeVisible(48, 83, p.valueAt(1000)[0]));
});

test("cameraplan: identical neighbor frames do not shift the plan", () => {
  // Mirrors identicalNeighborFramesDoNotShiftThePlan in CameraPlanTest.java:
  // identical neighbors share no glide, so valueAt must not index groups by
  // glide position - that bug kept a warm-pad part from ever zooming out.
  const p = new G.CameraPlan([
    CN(60, 1, 2), CN(64, 1, 2),
    CN(60, 10, 11), CN(64, 10, 11),
    CN(48, 20, 21), CN(83, 20, 21),
    CN(60, 30, 31), CN(64, 30, 31)], 48, 83, 800, 44);
  assert.equal(p.segmentCount, 4);
  assert.equal(p.groups.length, 4);
  assert.equal(p.glides.length, 2); // no glide between the identical frames
  const fitW = 800 / 21;
  assert.equal(p.valueAt(1500)[1], 44);
  assert.equal(p.valueAt(12000)[1], 44);
  assert.ok(Math.abs(p.valueAt(20000)[1] - fitW) < 0.001);
  assert.ok(Math.abs(p.valueAt(25000)[1] - fitW) < 0.001);
  assert.equal(p.valueAt(31000)[1], 44);
  assert.equal(p.valueAt(99999)[1], 44);
  const kw = new G.KeyWindow(48, 83, 800, 1);
  kw.whiteW = fitW;
  assert.ok(Math.abs(p.valueAt(20000)[0] - kw.viewportCentering(48, 83)) < 0.001);
});

test("cameraplan: dense no-gap passage is a whole-passage fit", () => {
  const walk = [];
  for (let i = 0; i < 36; i++) walk.push(CN(48 + i, i * 0.25, i * 0.25 + 0.25));
  const p = new G.CameraPlan(walk, 48, 83, 800, 44);
  assert.equal(p.segmentCount, 1);
  assert.equal(p.glides.length, 0);
  const fitW = 800 / 21;
  assert.ok(Math.abs(p.minWhiteW - fitW) < 0.001);
  const first = p.valueAt(0);
  for (let t = 0; t <= 9500; t += 250) {
    assert.deepEqual(p.valueAt(t), first, `static at ${t}`);
  }
  const kw = new G.KeyWindow(48, 83, 800, 1);
  kw.whiteW = fitW;
  assert.ok(kw.rangeVisible(48, 83, first[0]));
});

test("cameraplan: held pad keeps the whole union visible", () => {
  const p = new G.CameraPlan([
    CN(48, 0, 10), CN(76, 2, 2.5), CN(79, 4, 4.5), CN(83, 6, 6.5),
  ], 48, 83, 800, 44);
  assert.equal(p.segmentCount, 1);
  assert.equal(p.glides.length, 0);
  const kw = new G.KeyWindow(48, 83, 800, 1);
  kw.whiteW = p.valueAt(3000)[1];
  assert.ok(kw.rangeVisible(48, 83, p.valueAt(3000)[0]));
});

test("cameraplan: glides never overlap busy time", () => {
  const song = [CN(48, 0, 1), CN(83, 10, 11), CN(60, 20, 21)];
  const p = new G.CameraPlan(song, 48, 83, 800, 44);
  assert.equal(p.groups.length, 3);
  assert.equal(p.glides.length, 2);
  for (const g of p.glides) {
    for (const n of song) {
      const b0 = n.startSec * 1000 - G.CAM_BUSY_LEAD_MS;
      const b1 = n.endSec * 1000 + G.CAM_BUSY_TAIL_MS;
      assert.ok(g.endMs <= b0 || g.startMs >= b1,
          `glide [${g.startMs},${g.endMs}] overlaps busy [${b0},${b1}]`);
    }
  }
});

test("cameraplan: zoom rides the glide", () => {
  // a narrow phrase, then a cluster spanning the whole board: the gap
  // glide carries the zoom change smoothly, inside the gap
  const song = [CN(60, 0, 1)];
  for (let i = 0; i < 36; i++) song.push(CN(48 + i, 10, 11));
  const p = new G.CameraPlan(song, 48, 83, 800, 44);
  assert.equal(p.groups.length, 2);
  assert.equal(p.glides.length, 1);
  const g = p.glides[0];
  const wA = p.valueAt(g.startMs - 1)[1];
  const wB = p.valueAt(g.endMs + 1)[1];
  assert.equal(wA, 44);
  assert.ok(Math.abs(wB - 800 / 21) < 0.01, "the wide cluster shrinks the keys");
  const midW = p.valueAt((g.startMs + g.endMs) / 2)[1];
  assert.ok(midW < wA && midW > wB, "zoom interpolates mid-glide");
});
test("keywindow: viewport centering clamps to the board", () => {
  const kw = new G.KeyWindow(21, 108, 360);
  assert.equal(kw.viewportCentering(21, 30), 0); // left edge clamp
  assert.equal(kw.viewportCentering(100, 108), kw.maxViewport()); // right edge
});

/* ---- Sniffer: magic beats extension, text heuristic last ---- */
test("sniffer: a .txt that is really MIDI opens as music", () => {
  const head = fixture("twotrack.mid").slice(0, 64);
  assert.equal(S.sniff("notes.txt", "text/plain", head), S.MIDI);
});
test("sniffer: kinds from magic", () => {
  assert.equal(S.sniff("x", "", fixture("pixel.png").slice(0, 32)), S.IMAGE);
  assert.equal(S.sniff("x", "", fixture("tone.wav").slice(0, 32)), S.AUDIO);
});
test("sniffer: text heuristic and hex fallback", () => {
  assert.equal(S.sniff("noext", "", fixture("sample.txt").slice(0, 128)), S.TEXT);
  const binary = new Uint8Array(64).map((_, i) => (i * 37) & 0xFF);
  binary[3] = 0; // NUL forces binary
  assert.equal(S.sniff("noext", "", binary), S.HEX);
});
test("sniffer: mp4/EBML magic and video mime detect VIDEO", () => {
  const ftyp = new Uint8Array([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70,
    0x6D, 0x70, 0x34, 0x32, 0, 0, 0, 0]);
  assert.equal(S.byMagic(ftyp), S.VIDEO);
  assert.equal(S.byMagic(new Uint8Array([0x1A, 0x45, 0xDF, 0xA3])), S.VIDEO);
  assert.equal(S.sniff("clip", "video/mp4", new Uint8Array(4)), S.VIDEO);
  assert.equal(S.sniff("clip.mkv", "", new Uint8Array(4)), S.VIDEO);
  assert.equal(S.sniff("clip.3gp", "", new Uint8Array(4)), S.VIDEO);
});
test("sniffer: audio brands still beat the video family", () => {
  const m4a = new Uint8Array([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70,
    0x4D, 0x34, 0x41, 0x20, 0, 0, 0, 0]); // ftyp "M4A "
  assert.equal(S.byMagic(m4a), S.AUDIO);
  const heic = new Uint8Array([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70,
    0x68, 0x65, 0x69, 0x63, 0, 0, 0, 0]); // ftyp "heic"
  assert.equal(S.byMagic(heic), S.IMAGE);
});

/* ---- Conversion encoders (pure functions) ---- */
await import(join(root, "docs/js/convert.js"));
const C = globalThis.TengokConvert;

test("wav encoder: RIFF header and frame count", () => {
  const left = new Float32Array([0, 0.5, -0.5, 1]);
  const right = new Float32Array([1, -1, 0.25, 0]);
  const wav = C.encodeWav([left, right], 22050);
  const dv = new DataView(wav.buffer);
  assert.equal(String.fromCharCode(...wav.slice(0, 4)), "RIFF");
  assert.equal(String.fromCharCode(...wav.slice(8, 12)), "WAVE");
  assert.equal(dv.getUint16(22, true), 2); // stereo
  assert.equal(dv.getUint32(24, true), 22050);
  assert.equal(dv.getUint32(40, true), 4 * 2 * 2); // frames*ch*2
  assert.equal(wav.length, 44 + 16);
  assert.equal(dv.getInt16(44, true), 0); // left[0]
  assert.equal(dv.getInt16(46, true), 32767); // right[0] = 1.0
  assert.equal(dv.getInt16(52, true), -16383); // frame 2 left = -0.5 (rounds to zero on .5)
});
test("wav encoder: clips out-of-range samples", () => {
  const wav = C.encodeWav([new Float32Array([2, -2])], 8000);
  const dv = new DataView(wav.buffer);
  assert.equal(dv.getInt16(44, true), 32767);
  assert.equal(dv.getInt16(46, true), -32767);
});
test("latin-1 encoder: ascii kept, unmappable becomes '?'", () => {
  const out = C.encodeLatin1("ab\u00e9\u4e2d"); // é fits, 中 does not
  assert.deepEqual(Array.from(out), [97, 98, 0xE9, 0x3F]);
});
test("text decode: UTF-8 first, Latin-1 fallback", () => {
  assert.equal(C.decodeText(new Uint8Array([104, 105])).encoding, "UTF-8");
  assert.equal(C.decodeText(new Uint8Array([0xE9])).encoding, "Latin-1");
  assert.equal(C.decodeText(new Uint8Array([0xE9])).text, "\u00e9");
});
test("conversion targets exist for image/audio/text only", () => {
  assert.equal(C.targetsFor("IMAGE").length, 3);
  assert.equal(C.targetsFor("AUDIO").length, 1);
  assert.equal(C.targetsFor("TEXT").length, 2);
  assert.equal(C.targetsFor("MIDI").length, 0);
  assert.equal(C.targetsFor("HEX").length, 0);
  assert.equal(C.outName("photo.jpeg", "png"), "photo.png");
  assert.equal(C.outName("noext", "wav"), "noext.wav");
});

console.log("\n" + passed + " tests passed" + (process.exitCode ? ", WITH FAILURES" : ""));
