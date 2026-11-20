/* tengok web - the MIDI screen: summary, instruments per track, a piano
 * roll with an auto-following playback line, and transport controls. */
(function (root) {
"use strict";

const V = root.TengokViewers;
const M = root.TengokMidi;
const PALETTE = [
  "#3E6FB0", "#4CAF50", "#E8A33D", "#AB47BC",
  "#EF5350", "#26A69A", "#8D6E63", "#78909C",
  "#D4E157", "#5C6BC0", "#FF7043", "#9CCC65",
  "#7E57C2", "#26C6DA", "#EC407A", "#A1887F"];

function fmtTime(sec) {
  const s = Math.max(0, Math.floor(sec));
  return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
}

function trackColor(trackIndex) {
  return PALETTE[trackIndex % PALETTE.length];
}

function showMidi(container, file, midi, ctx) {
  const el = V.el;

  /* Summary card, same lines as the Android panel. */
  const summary = el("div", "card");
  summary.appendChild(el("div", "card-title",
      "MIDI format " + midi.format + " · " + midi.tracks.length
      + (midi.tracks.length === 1 ? " track" : " tracks") + " · " + fmtTime(midi.durationSeconds)));
  let meta = midi.smpte ? "SMPTE timing" : midi.ticksPerQuarter + " ticks per quarter note";
  if (midi.tempos.length > 0) {
    const bpm = 60000000 / midi.tempos[0].usPerQuarter;
    meta += " · " + (Math.round(bpm * 10) / 10) + " bpm";
    if (midi.tempos.length > 2) meta += " (tempo changes)";
  }
  meta += " · " + midi.timeSigNum + "/" + midi.timeSigDen;
  if (midi.keySig) meta += " · " + midi.keySig;
  summary.appendChild(el("div", "muted", meta));
  summary.appendChild(el("div", "muted",
      midi.notes.length + (midi.notes.length === 1 ? " note" : " notes")));
  container.appendChild(summary);

  /* Track cards with General MIDI instrument names. */
  for (const t of midi.tracks) {
    if (t.noteCount === 0 && t.programs.length === 0 && !t.name) continue;
    const card = el("div", "card");
    card.appendChild(el("div", "card-title",
        "Track " + (t.index + 1) + (t.name ? " · " + t.name : "")));
    let line;
    if (t.programs.length === 0) {
      line = t.channels[9] ? "Percussion" : "no instrument changes";
    } else {
      line = t.programs.map((p) => M.gmInstrument(p)).join(", ");
    }
    card.appendChild(el("div", "muted", line));
    if (t.noteCount > 0) {
      card.appendChild(el("div", "muted",
          t.noteCount + " notes · " + M.gmPitch(t.minPitch) + " to " + M.gmPitch(t.maxPitch)));
    }
    container.appendChild(card);
  }

  if (midi.notes.length === 0) {
    const card = el("div", "card");
    card.appendChild(el("div", "muted", "No notes in this file."));
    container.appendChild(card);
    return;
  }

  /* Piano roll. */
  const rollCard = el("div", "card");
  rollCard.appendChild(el("div", "card-title", "Piano roll"));
  const scroll = el("div", "roll-scroll");
  const canvas = el("canvas", "roll-canvas");
  scroll.appendChild(canvas);
  rollCard.appendChild(scroll);
  container.appendChild(rollCard);

  let minPitch = 127, maxPitch = 0;
  for (const n of midi.notes) {
    if (n.pitch < minPitch) minPitch = n.pitch;
    if (n.pitch > maxPitch) maxPitch = n.pitch;
  }
  minPitch = Math.max(0, minPitch - 1);
  maxPitch = Math.min(127, maxPitch + 1);

  const PX_PER_SEC = 120;
  const ROW_H = 10;
  const w = Math.max(320, Math.ceil(midi.durationSeconds * PX_PER_SEC) + 20);
  const rows = maxPitch - minPitch + 1;
  const h = rows * ROW_H;
  const dpr = Math.min(2, root.devicePixelRatio || 1);
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  canvas.style.width = w + "px";
  canvas.style.height = h + "px";
  const g = canvas.getContext("2d");
  g.scale(dpr, dpr);

  function pitchY(p) {
    return (maxPitch - p) * ROW_H;
  }

  function drawRoll(playheadSec) {
    g.fillStyle = "#FFFFFF";
    g.fillRect(0, 0, w, h);
    for (let p = minPitch; p <= maxPitch; p++) {
      const y = pitchY(p);
      if (root.TengokGame.isBlack(p)) {
        g.fillStyle = "#E4E6EF";
      } else {
        g.fillStyle = p % 12 === 0 ? "#E0E4F0" : "#F4F5F9";
      }
      g.fillRect(0, y, w, ROW_H);
    }
    for (const n of midi.notes) {
      const x = n.startSec * PX_PER_SEC;
      const nw = Math.max(2, (n.endSec - n.startSec) * PX_PER_SEC);
      g.globalAlpha = 0.35 + 0.65 * (n.velocity / 127);
      g.fillStyle = trackColor(n.track);
      g.fillRect(x, pitchY(n.pitch) + 1, nw, ROW_H - 2);
    }
    g.globalAlpha = 1;
    if (playheadSec !== undefined && playheadSec !== null) {
      g.fillStyle = "#1F2A5C";
      g.fillRect(playheadSec * PX_PER_SEC, 0, 2, h);
    }
  }
  drawRoll(null);

  /* Transport. */
  const transport = el("div", "card");
  const bar = el("div", "toolbar");
  const playBtn = el("button", "btn primary", "▶ Play");
  const clock = el("span", "muted", "0:00 / " + fmtTime(midi.durationSeconds));
  const seek = el("input", "seek");
  seek.type = "range";
  seek.min = "0";
  seek.max = String(Math.ceil(midi.durationSeconds * 1000));
  seek.value = "0";
  bar.appendChild(playBtn);
  bar.appendChild(seek);
  bar.appendChild(clock);
  transport.appendChild(bar);
  transport.appendChild(el("div", "muted",
      "Played through tengok web's simple synth: browsers ship no General MIDI "
      + "sounds, so instruments are approximate. Pitch bends are not rendered."));
  container.appendChild(transport);

  const programs = root.TengokSynth.Synth ? root.TengokSynth.Synth.programMap(midi) : null;
  let playing = null;

  function followCursor(songSec) {
    clock.textContent = fmtTime(songSec) + " / " + fmtTime(midi.durationSeconds);
    if (!seek.matches(":active")) seek.value = String(Math.floor(songSec * 1000));
    drawRoll(songSec);
    const target = songSec * PX_PER_SEC - scroll.clientWidth * 0.25;
    scroll.scrollLeft = Math.max(0, target);
  }

  function stopPlayback(showEnd) {
    if (playing) { playing.stop(); playing = null; }
    playBtn.textContent = "▶ Play";
    if (showEnd) {
      seek.value = "0";
      drawRoll(null);
      scroll.scrollLeft = 0;
      clock.textContent = "0:00 / " + fmtTime(midi.durationSeconds);
    }
  }

  function startPlayback(fromSec) {
    stopPlayback(false);
    playBtn.textContent = "⏸ Pause";
    playing = ctx.synth.play(midi.notes, fromSec, midi.durationSeconds, programs,
        followCursor, () => stopPlayback(true));
    if (!playing) {
      playBtn.textContent = "▶ Play";
      transport.appendChild(V.errorCard("This browser has no Web Audio support.", null));
    }
  }

  playBtn.addEventListener("click", () => {
    if (playing) {
      stopPlayback(false);
    } else {
      startPlayback(parseInt(seek.value, 10) / 1000);
    }
  });
  seek.addEventListener("change", () => {
    if (playing) startPlayback(parseInt(seek.value, 10) / 1000);
    else followCursor(parseInt(seek.value, 10) / 1000);
  });

  /* Play along: the game. */
  const gameCard = el("div", "card");
  gameCard.appendChild(el("div", "card-title", "Play along"));
  gameCard.appendChild(el("div", "muted",
      "Notes fall toward the keyboard; strike them on time. Taps, the letter "
      + "keys, or a MIDI keyboard where the browser supports Web MIDI."));
  const goBtn = el("button", "btn primary", "Start the game");
  goBtn.addEventListener("click", () => {
    stopPlayback(false);
    root.TengokGameUi.startGame(ctx.viewArea, midi, ctx);
  });
  gameCard.appendChild(goBtn);
  container.appendChild(gameCard);

  ctx.onCleanup(() => stopPlayback(false));
}

root.TengokMidiView = { showMidi: showMidi, fmtTime: fmtTime, trackColor: trackColor };
})(typeof window !== "undefined" ? window : globalThis);
