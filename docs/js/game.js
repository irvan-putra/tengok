/* tengok web - play-along game: notes fall toward a pannable keyboard, taps
 * / letter keys / a Web MIDI keyboard strike them, and a live rank climbs
 * or falls with timing, accuracy and streak (same rules as Android). */
(function (root) {
"use strict";

const V = root.TengokViewers;
const M = root.TengokMidi;
const G = root.TengokGame;

const FALL_AHEAD_SEC = 2.5;
const LEAD_IN_SEC = 1.5;
const KEY_H = 132;
const STATE_COLORS = { 1: "#FFB300", 2: "#3DA35D", 3: "#9AA0B0" }; // perfect/good/miss
const WHITE_KEYS = ["a", "s", "d", "f", "g", "h", "j", "k"];
const BLACK_KEYS = ["w", "e", "t", "y", "u"];

function fmtCounts(sc) {
  return sc.perfect + " perfect · " + sc.good + " good · " + sc.miss + " miss"
      + " · streak " + sc.streak + " · score " + sc.score();
}

function startGame(viewArea, midi, ctx) {
  const el = V.el;
  viewArea.replaceChildren();

  const pickCard = el("div", "card");
  pickCard.appendChild(el("div", "card-title", "Play along: pick your track"));
  pickCard.appendChild(el("div", "muted",
      "You play this track; the other tracks keep playing with you."));
  const playable = midi.tracks.filter((t) => t.noteCount > 0);
  for (const t of playable) {
    const b = el("button", "btn track-pick",
        "Track " + (t.index + 1) + (t.name ? " · " + t.name : "")
        + " · " + t.noteCount + " notes · "
        + M.gmPitch(t.minPitch) + " to " + M.gmPitch(t.maxPitch));
    b.addEventListener("click", () => runGame(viewArea, midi, t.index, ctx));
    pickCard.appendChild(b);
  }
  const back = el("button", "btn", "Back");
  back.addEventListener("click", () => ctx.showCurrentFile());
  pickCard.appendChild(back);
  viewArea.appendChild(pickCard);
}

function runGame(viewArea, midi, trackIndex, ctx) {
  const el = V.el;
  viewArea.replaceChildren();

  const scoring = new G.GameScoring(midi, trackIndex);
  const judged = midi.tracks[trackIndex];
  const accomp = midi.notes.filter((n) => n.track !== trackIndex);
  const judgedNotes = midi.notes.filter((n) => n.track === trackIndex);
  const programs = root.TengokSynth.Synth.programMap(midi);
  const judgedProgram = (() => {
    for (const ch of [0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15]) {
      const p = programs[trackIndex + ":" + ch];
      if (p !== undefined) return p;
    }
    return 0;
  })();

  /* HUD */
  const hud = el("div", "card hud");
  const rankEl = el("span", "rank", G.UNRANKED);
  const countsEl = el("span", "muted hud-counts", fmtCounts(scoring));
  hud.appendChild(rankEl);
  hud.appendChild(countsEl);
  viewArea.appendChild(hud);

  const bar = el("div", "toolbar");
  const playBtn = el("button", "btn primary", "▶ Start");
  const againBtn = el("button", "btn", "↺ Restart");
  const backBtn = el("button", "btn", "Back");
  bar.appendChild(playBtn);
  bar.appendChild(againBtn);
  bar.appendChild(backBtn);
  viewArea.appendChild(bar);

  const midiLine = el("div", "muted midi-status", "");
  viewArea.appendChild(midiLine);

  const wrap = el("div", "game-wrap");
  const canvas = el("canvas", "game-canvas");
  wrap.appendChild(canvas);
  viewArea.appendChild(wrap);

  const hint = el("div", "muted",
      "Tap or click keys; drag sideways to pan the board. Letter keys play too: "
      + "A S D F G H J K are white keys from the left edge, W E T Y U black.");
  viewArea.appendChild(hint);

  /* Geometry: song range plus breathing room, clamped to the 88-key board. */
  let lo = Math.max(21, judged.minPitch - 3);
  let hi = Math.min(108, judged.maxPitch + 3);
  let kw = new G.KeyWindow(lo, hi, 320);
  let vx = 0;
  let lastPanAt = -1e9;

  /* Song clock */
  const firstStart = Math.max(0, scoring.firstStartMs() / 1000 - LEAD_IN_SEC);
  let clockStart = 0; // performance.now() ms when (re)started
  let offsetSec = firstStart;
  let running = false;
  let playback = null;
  let ended = false;
  let raf = 0;
  const flashes = new Map(); // pitch -> { t: performance.now(), grade }
  const pressed = new Set(); // pitches physically held right now
  /* Judged-hit sparks: a short burst just above the hit line, where the
   * striking finger does not cover it. Only judged hits (Perfect/Good)
   * spark; a plain registered tap keeps the quiet dim/grey flash, so
   * sparks always mean "that one scored". */
  const SPARK_MS = 480;
  const sparks = []; // { x, y, vx, vy, t, color }

  function nowSec() {
    return running ? offsetSec + (performance.now() - clockStart) / 1000 : offsetSec;
  }

  function startPlayback() {
    if (ended) return;
    running = true;
    clockStart = performance.now();
    playBtn.textContent = "⏸ Pause";
    playback = ctx.synth.play(accomp, offsetSec, midi.durationSeconds, programs,
        null, null);
  }

  function stopPlayback() {
    if (playback) { playback.stop(); playback = null; }
    if (running) offsetSec = nowSec();
    running = false;
    playBtn.textContent = "▶ Resume";
  }

  playBtn.addEventListener("click", () => (running ? stopPlayback() : startPlayback()));
  againBtn.addEventListener("click", () => {
    stopAll();
    runGame(viewArea, midi, trackIndex, ctx);
  });
  backBtn.addEventListener("click", () => {
    stopAll();
    ctx.showCurrentFile();
  });

  function strike(pitch, ageMs = 0) {
    if (!running || ended) return;
    // ageMs anchors the judgment to when the finger landed, not when the
    // gesture was recognized: a held key scores at its press time.
    const nowMs = nowSec() * 1000 - ageMs;
    const grade = scoring.strike(pitch, nowMs);
    ctx.synth.strike(pitch, 100, judgedProgram);
    flashes.set(pitch, { t: performance.now(), grade });
    if (grade === G.PERFECT || grade === G.GOOD) {
      spawnSparks(pitch, grade, 12);
      if (pressed.has(pitch)) {
        // still held: keep sparkling until the finger lifts
        heldSpark = { pitch, grade, lastEmit: performance.now() };
      }
      updateHud();
    }
  }

  /* While a judged hit's key stays held, fresh sparks re-emit at this pace. */
  const HELD_SPARK_MS = 110;
  let heldSpark = null;

  function spawnSparks(pitch, grade, count) {
    const x = (kw.leftPx(pitch) - vx)
        + (G.isBlack(pitch) ? kw.blackW() : kw.whiteW) / 2;
    const y = canvas.height - KEY_H - 8; // clear of the hit line and the finger
    const color = grade === G.PERFECT ? "255,216,77" : "155,225,93";
    for (let i = 0; i < count; i++) {
      const a = (-165 + Math.random() * 150) * Math.PI / 180; // upward fan
      const v = 0.22 + Math.random() * 0.38; // px per ms
      sparks.push({ x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v,
          t: performance.now(), color });
    }
    while (sparks.length > 72) sparks.shift();
  }

  function updateHud() {
    const r = scoring.rankLive();
    rankEl.textContent = r;
    rankEl.style.color = r === G.UNRANKED ? "#8A8FA3"
        : r.startsWith("SS") ? "#B07800" : r === "S" ? "#3E6FB0"
        : r === "A" ? "#3DA35D" : "#5A6072";
    countsEl.textContent = fmtCounts(scoring);
  }

  /* Pointer input: tap strikes, horizontal drag pans. A press held 90 ms
   * without turning into a pan strikes in place, so the judgment and sparks
   * land while the finger is still down; a drag past the slop first cancels
   * the pending strike, so a real look-around drag still never strikes. */
  const STRIKE_GRACE_MS = 90;
  const pointers = new Map();
  canvas.addEventListener("pointerdown", (e) => {
    canvas.setPointerCapture(e.pointerId);
    const rec = { x: e.offsetX, y: e.offsetY, startX: e.offsetX, panning: false, pitch: -1,
        struck: false, downAt: performance.now(), graceTimer: 0 };
    const rect = canvas.getBoundingClientRect();
    if (e.clientY - rect.top >= canvas.clientHeight - KEY_H) {
      rec.pitch = kw.pitchAt(e.clientX - rect.left + vx);
      if (rec.pitch >= 0) {
        pressed.add(rec.pitch); // immediate "registered"
        rec.graceTimer = setTimeout(() => {
          if (!rec.struck && rec.pitch >= 0 && !rec.panning) {
            rec.struck = true;
            strike(rec.pitch, performance.now() - rec.downAt);
          }
        }, STRIKE_GRACE_MS);
      }
    }
    pointers.set(e.pointerId, rec);
  });
  canvas.addEventListener("pointermove", (e) => {
    const p = pointers.get(e.pointerId);
    if (!p) return;
    const rect = canvas.getBoundingClientRect();
    const y = e.clientY - rect.top;
    if (y >= canvas.clientHeight - KEY_H) {
      const dx = e.clientX - rect.left - p.x;
      if (p.panning) {
        vx = kw.clampViewport(vx - dx);
        lastPanAt = performance.now();
      } else if (Math.abs(e.clientX - rect.left - p.startX) > 10) {
        p.panning = true; // a deliberate drag is not a tap
        clearTimeout(p.graceTimer); // and a real drag never strikes
        if (p.pitch >= 0) { pressed.delete(p.pitch); p.pitch = -1; }
      }
      p.x = e.clientX - rect.left;
    }
  });
  canvas.addEventListener("pointerup", (e) => {
    const p = pointers.get(e.pointerId);
    pointers.delete(e.pointerId);
    if (!p) return;
    clearTimeout(p.graceTimer);
    if (p.pitch >= 0) pressed.delete(p.pitch);
    if (p.panning || p.struck) return;
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    if (y >= canvas.clientHeight - KEY_H) {
      const pitch = kw.pitchAt(x + vx);
      if (pitch >= 0) strike(pitch, performance.now() - p.downAt);
    }
  });
  canvas.addEventListener("pointercancel", (e) => {
    const p = pointers.get(e.pointerId);
    if (p) {
      clearTimeout(p.graceTimer);
      if (p.pitch >= 0) pressed.delete(p.pitch);
    }
    pointers.delete(e.pointerId);
  });

  /* Letter keys from the leftmost fully visible white key. */
  function keyDown(e) {
    if (e.repeat || e.metaKey || e.ctrlKey || e.altKey) return;
    const k = e.key.toLowerCase();
    if (k === " ") {
      e.preventDefault();
      running ? stopPlayback() : startPlayback();
      return;
    }
    let firstWhite = lo;
    while (firstWhite <= hi && kw.leftPx(firstWhite) < vx - 0.5) firstWhite++;
    if (firstWhite > hi || G.isBlack(firstWhite)) {
      // firstWhite may be black if it pokes in; step to next white
      while (firstWhite <= hi && (G.isBlack(firstWhite) || kw.leftPx(firstWhite) < vx - 0.5)) firstWhite++;
    }
    const held = (p) => { heldKeys.set(k, p); pressed.add(p); strike(p); };
    let wi = WHITE_KEYS.indexOf(k);
    if (wi >= 0) {
      let count = 0;
      for (let p = firstWhite; p <= hi; p++) {
        if (G.isBlack(p)) continue;
        if (count === wi) { held(p); return; }
        count++;
      }
      return;
    }
    let bi = BLACK_KEYS.indexOf(k);
    if (bi >= 0) {
      let count = 0;
      for (let p = firstWhite; p <= hi; p++) {
        if (!G.isBlack(p)) continue;
        if (kw.centerPx(p) < vx) continue;
        if (count === bi) { held(p); return; }
        count++;
      }
    }
  }
  window.addEventListener("keydown", keyDown);
  const heldKeys = new Map(); // letter key -> pitch it struck
  function keyUp(e) {
    const p = heldKeys.get(e.key.toLowerCase());
    if (p !== undefined) {
      heldKeys.delete(e.key.toLowerCase());
      pressed.delete(p);
    }
  }
  window.addEventListener("keyup", keyUp);

  /* Web MIDI where the browser has it (Chrome/Edge; not Firefox/Safari). */
  if (navigator.requestMIDIAccess) {
    navigator.requestMIDIAccess().then((access) => {
      const names = [];
      for (const input of access.inputs.values()) {
        names.push(input.name || "MIDI keyboard");
        input.onmidimessage = (msg) => {
          const [st, pitch, vel] = msg.data;
          if ((st & 0xF0) === 0x90 && vel > 0) { pressed.add(pitch); strike(pitch); }
          else if ((st & 0xF0) === 0x80 || ((st & 0xF0) === 0x90 && vel === 0)) pressed.delete(pitch);
        };
      }
      midiLine.textContent = names.length
          ? "MIDI keyboard: " + names.join(", ")
          : "Web MIDI is available but no keyboard is plugged in.";
    }).catch(() => {
      midiLine.textContent = "MIDI keyboard access was not granted.";
    });
  } else {
    midiLine.textContent = "This browser has no Web MIDI (Firefox and Safari do not); "
        + "the on-screen keys and letter keys still work.";
  }

  /* Rendering */
  function resize() {
    const w = wrap.clientWidth;
    const h = Math.min(520, Math.max(360, Math.round(root.innerHeight * 0.55)));
    const dpr = Math.min(2, root.devicePixelRatio || 1);
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + "px";
    canvas.style.height = h + "px";
    kw = new G.KeyWindow(lo, hi, w);
    vx = kw.clampViewport(vx);
  }
  window.addEventListener("resize", resize);
  resize();

  function draw() {
    const dpr = Math.min(2, root.devicePixelRatio || 1);
    const g = canvas.getContext("2d");
    const w = canvas.width / dpr;
    const h = canvas.height / dpr;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const now = nowSec();
    const fallH = h - KEY_H;
    const strikeY = fallH;

    g.fillStyle = "#FBFBFE";
    g.fillRect(0, 0, w, h);

    /* falling notes of every track; judged track notes are the targets */
    const speed = fallH / FALL_AHEAD_SEC;
    for (const n of midi.notes) {
      const yTop = strikeY - (n.startSec - now) * speed;
      const yBot = strikeY - (n.endSec - now) * speed;
      if (yBot < -4 || yTop > strikeY) continue;
      const x1 = kw.leftPx(n.pitch) - vx;
      const x2 = kw.rightPx(n.pitch) - vx;
      if (x2 < 0 || x1 > w) continue;
      let color = root.TengokMidiView.trackColor(n.track);
      if (n.track === trackIndex) {
        // judged notes tint by their judged state
        for (let i = 0; i < scoring.total(); i++) {
          if (scoring.judgedNote(i) === n) {
            const st = scoring.stateOf(i);
            if (st !== G.PENDING) color = STATE_COLORS[st];
            break;
          }
        }
      } else {
        g.globalAlpha = 0.35;
      }
      g.fillStyle = color;
      const top = Math.min(yTop, strikeY - 2);
      const bottom = Math.min(yBot, strikeY - 2);
      g.fillRect(x1 + 1, bottom, Math.max(2, x2 - x1 - 2), Math.max(3, top - bottom));
      g.globalAlpha = 1;
    }

    /* strike line */
    g.fillStyle = "#1F2A5C";
    g.fillRect(0, strikeY - 1, w, 2);

    /* keyboard */
    const kbY = strikeY;
    g.fillStyle = "#FFFFFF";
    g.fillRect(0, kbY, w, KEY_H);
    for (let p = lo; p <= hi; p++) {
      if (G.isBlack(p)) continue;
      const x = kw.leftPx(p) - vx;
      if (x > w || x + kw.whiteW < 0) continue;
      /* a judged strike flashes its key 350ms: green Perfect, amber Good,
       * grey when the tap registered but matched no pending note */
      const f = flashes.get(p);
      if (f) {
        const age = performance.now() - f.t;
        if (age < 350) {
          const rgb = f.grade === G.PERFECT ? "61,163,93"
              : f.grade === G.GOOD ? "255,179,0" : "138,144,162";
          g.fillStyle = `rgba(${rgb},${(0.43 * (1 - age / 350)).toFixed(3)})`;
          g.fillRect(x, kbY, kw.whiteW, KEY_H);
        }
      }
      if (pressed.has(p)) { // the key under the finger: "registered"
        g.fillStyle = "rgba(0,0,0,0.20)";
        g.fillRect(x, kbY, kw.whiteW, KEY_H);
      }
      g.strokeStyle = "#B9BECB";
      g.lineWidth = 1;
      g.strokeRect(x + 0.5, kbY + 0.5, kw.whiteW - 1, KEY_H - 1);
    }
    for (let p = lo; p <= hi; p++) {
      if (!G.isBlack(p)) continue;
      const x = kw.leftPx(p) - vx;
      const bw = kw.blackW();
      if (x > w || x + bw < 0) continue;
      g.fillStyle = "#22242E";
      g.fillRect(x, kbY, bw, KEY_H * 0.62);
      const fb = flashes.get(p);
      if (fb) {
        const age = performance.now() - fb.t;
        if (age < 350) {
          const rgb = fb.grade === G.PERFECT ? "61,163,93"
              : fb.grade === G.GOOD ? "255,179,0" : "138,144,162";
          g.fillStyle = `rgba(${rgb},${(0.43 * (1 - age / 350)).toFixed(3)})`;
          g.fillRect(x, kbY, bw, KEY_H * 0.62);
        }
      }
      if (pressed.has(p)) {
        g.fillStyle = "rgba(255,255,255,0.40)";
        g.fillRect(x, kbY, bw, KEY_H * 0.62);
      }
    }

    /* judged-hit sparks above the hit line; physics integrate on the wall
     * clock so frame rate changes smoothness, never positions */
    for (let i = sparks.length - 1; i >= 0; i--) {
      const s = sparks[i];
      const age = performance.now() - s.t;
      if (age > SPARK_MS) { sparks.splice(i, 1); continue; }
      const t = age / SPARK_MS;
      const sx = s.x + s.vx * age;
      const sy = s.y + s.vy * age + 0.0005 * age * age;
      g.fillStyle = `rgba(${s.color},${(1 - t).toFixed(3)})`;
      g.beginPath();
      g.arc(sx, sy, 3.4 - 1.4 * t, 0, Math.PI * 2);
      g.fill();
    }

    /* a held judged key keeps a gentle sparkle going until release */
    if (heldSpark) {
      if (!pressed.has(heldSpark.pitch)) {
        heldSpark = null;
      } else {
        const nowT = performance.now();
        if (nowT - heldSpark.lastEmit >= HELD_SPARK_MS) {
          heldSpark.lastEmit = nowT;
          spawnSparks(heldSpark.pitch, heldSpark.grade, 3);
        }
      }
    }

    /* pan hint arrows when the board extends past an edge */
    g.fillStyle = "#8A90A2";
    g.font = "12px sans-serif";
    if (vx > 0.5) g.fillText("◀ more keys", 6, kbY + KEY_H - 10);
    if (vx < kw.maxViewport() - 0.5) {
      const label = "more keys ▶";
      g.fillText(label, w - g.measureText(label).width - 6, kbY + KEY_H - 10);
    }
  }

  function autoFollow() {
    if (performance.now() - lastPanAt < 2500 || !running) return;
    let loP = -1, hiP = -1;
    const now = nowSec();
    for (let i = 0; i < scoring.total(); i++) {
      if (scoring.stateOf(i) !== G.PENDING) continue;
      const s = scoring.noteStartMs(i) / 1000;
      if (s > now + FALL_AHEAD_SEC) break;
      if (s + 0.3 < now) continue;
      const p = scoring.notePitch(i);
      if (loP < 0 || p < loP) loP = p;
      if (hiP < 0 || p > hiP) hiP = p;
    }
    if (loP < 0) {
      const ni = scoring.nextPendingIndex();
      if (ni < 0) return;
      loP = hiP = scoring.notePitch(ni);
    }
    // Stay still while the cluster sits comfortably inside the board; glide
    // early, with a time-based ease and a speed cap, instead of jumping.
    const margin = 24;
    if (kw.leftPx(loP) - vx >= margin && kw.rightPx(hiP) - vx <= canvas.clientWidth - margin) return;
    const target = kw.viewportCentering(Math.max(lo, loP - 2), Math.min(hi, hiP + 2));
    const diff = target - vx;
    if (Math.abs(diff) < 1) { vx = target; return; }
    const t = performance.now();
    const dt = Math.min(0.1, lastFollowT ? (t - lastFollowT) / 1000 : 0.016);
    lastFollowT = t;
    let step = diff * 4 * dt;
    const cap = canvas.clientWidth * 1.5 * dt;
    step = Math.max(-cap, Math.min(cap, step));
    vx = Math.abs(step) >= Math.abs(diff) ? target : vx + step;
  }
  let lastFollowT = 0;

  function frame() {
    if (ended) return;
    const now = nowSec();
    scoring.tick(now * 1000);
    updateHud();
    autoFollow();
    draw();
    if (running && scoring.finished()
        && now * 1000 > scoring.lastStartMs() + LEAD_IN_SEC * 1000) {
      finish();
      return;
    }
    raf = requestAnimationFrame(frame);
  }

  function finish() {
    ended = true;
    stopPlayback();
    const card = el("div", "card final");
    card.appendChild(el("div", "card-title", "Song finished"));
    const grade = el("div", "rank big", scoring.rankFinal());
    card.appendChild(grade);
    card.appendChild(el("div", "muted",
        "accuracy " + scoring.accuracy() + "% · best streak " + scoring.bestStreak));
    card.appendChild(el("div", "muted", fmtCounts(scoring)));
    const again = el("button", "btn primary", "Play again");
    again.addEventListener("click", () => { stopAll(); runGame(viewArea, midi, trackIndex, ctx); });
    const back = el("button", "btn", "Back to the viewer");
    back.addEventListener("click", () => { stopAll(); ctx.showCurrentFile(); });
    card.appendChild(again);
    card.appendChild(back);
    viewArea.appendChild(card);
    card.scrollIntoView({ block: "nearest" });
  }

  function stopAll() {
    cancelAnimationFrame(raf);
    if (playback) playback.stop();
    ctx.synth.stop();
    window.removeEventListener("keydown", keyDown);
    window.removeEventListener("keyup", keyUp);
    window.removeEventListener("resize", resize);
  }
  ctx.onCleanup(stopAll);

  updateHud();
  frame();
}

root.TengokGameUi = { startGame: startGame };
})(typeof window !== "undefined" ? window : globalThis);
