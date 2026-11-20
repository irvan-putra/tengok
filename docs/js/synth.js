/* tengok web - MIDI playback through the Web Audio API.
 * Browsers ship no General MIDI synthesizer, so tengok web renders notes
 * with simple per-family synth voices: honest sound, not instrument
 * samples. Pitch bends and control changes are parsed but not rendered.
 * Accompaniment scheduling uses a lookahead window, so songs of any length
 * play without scheduling thousands of nodes at once. */
(function (root) {
"use strict";

function freq(pitch) {
  return 440 * Math.pow(2, (pitch - 69) / 12);
}

/* Voice shape per General MIDI program family. */
function voiceFor(program) {
  const p = program || 0;
  if (p < 8) return { type: "triangle", attack: 0.004, hold: 0.35, decay: 2.0, sustain: 0.0 }; // piano
  if (p < 16) return { type: "sine", attack: 0.002, hold: 0.05, decay: 0.6, sustain: 0.0 }; // mallets
  if (p < 24) return { type: "square", attack: 0.01, hold: 1e9, decay: 0, sustain: 0.8 }; // organ
  if (p < 32) return { type: "sawtooth", attack: 0.003, hold: 0.15, decay: 0.9, sustain: 0.0 }; // guitar
  if (p < 40) return { type: "triangle", attack: 0.008, hold: 1e9, decay: 0, sustain: 0.9 }; // bass
  if (p < 48) return { type: "sawtooth", attack: 0.09, hold: 1e9, decay: 0, sustain: 0.7 }; // strings
  if (p < 56) return { type: "triangle", attack: 0.15, hold: 1e9, decay: 0, sustain: 0.65 }; // choir/pad-ish
  if (p < 64) return { type: "square", attack: 0.06, hold: 1e9, decay: 0, sustain: 0.7 }; // brass
  if (p < 80) return { type: "square", attack: 0.02, hold: 1e9, decay: 0, sustain: 0.6 }; // reed/pipe
  if (p < 88) return { type: "square", attack: 0.005, hold: 1e9, decay: 0, sustain: 0.65 }; // lead
  if (p < 96) return { type: "triangle", attack: 0.3, hold: 1e9, decay: 0, sustain: 0.6 }; // pad
  if (p < 104) return { type: "sine", attack: 0.05, hold: 1e9, decay: 0, sustain: 0.5 }; // fx
  return { type: "triangle", attack: 0.01, hold: 1e9, decay: 0, sustain: 0.6 }; // ethnic + rest
}

class Synth {
  constructor() {
    this.ctx = null;
    this.master = null;
    this.active = []; // live AudioScheduledSourceNodes to stop on pause/seek
    this.timer = null;
    this.noiseBuf = null;
  }

  ensure() {
    if (!this.ctx) {
      const AC = root.AudioContext || root.webkitAudioContext;
      if (!AC) return false;
      this.attach(new AC());
    }
    if (this.ctx.state === "suspended") this.ctx.resume();
    return true;
  }

  /* Wire the synth to any BaseAudioContext (live or OfflineAudioContext). */
  attach(audioCtx) {
    this.ctx = audioCtx;
    this.master = this.ctx.createGain();
    this.master.gain.value = 0.5;
    this.master.connect(this.ctx.destination);
    const len = Math.floor(this.ctx.sampleRate * 0.25);
    this.noiseBuf = this.ctx.createBuffer(1, len, this.ctx.sampleRate);
    const data = this.noiseBuf.getChannelData(0);
    for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1;
  }

  /* One struck note right now (game input). program picks the voice. */
  strike(pitch, velocity, program) {
    if (!this.ensure()) return;
    const v = Math.max(1, Math.min(127, velocity || 100));
    this.note(pitch, v, this.ctx.currentTime, 0.5, program, false);
  }

  percussion(pitch, velocity, when) {
    const src = this.ctx.createBufferSource();
    src.buffer = this.noiseBuf;
    const g = this.ctx.createGain();
    const f = this.ctx.createBiquadFilter();
    f.type = "bandpass";
    f.frequency.value = pitch < 45 ? 120 : pitch < 55 ? 1800 : 5200;
    f.Q.value = pitch < 45 ? 0.7 : 1.2;
    const peak = 0.9 * Math.pow(velocity / 127, 1.2);
    g.gain.setValueAtTime(peak, when);
    g.gain.exponentialRampToValueAtTime(0.001, when + (pitch < 45 ? 0.22 : 0.12));
    src.connect(f); f.connect(g); g.connect(this.master);
    src.start(when);
    src.stop(when + 0.25);
    this.track(src);
  }

  note(pitch, velocity, when, durSec, program, isPerc) {
    if (isPerc) { this.percussion(pitch, velocity, when); return; }
    const v = voiceFor(program);
    const osc = this.ctx.createOscillator();
    osc.type = v.type;
    osc.frequency.value = freq(pitch);
    osc.start(when); // must precede stop(): Chrome throws otherwise
    const g = this.ctx.createGain();
    const peak = 0.32 * Math.pow(velocity / 127, 1.4);
    const end = when + Math.max(0.05, durSec);
    g.gain.setValueAtTime(0, when);
    g.gain.linearRampToValueAtTime(peak, when + v.attack);
    if (v.hold < 1e8) {
      // plucked family: decay toward silence regardless of note length
      const decayEnd = Math.min(end, when + v.attack + v.hold + v.decay);
      g.gain.setTargetAtTime(peak * 0.3, when + v.attack, v.hold / 3);
      g.gain.setTargetAtTime(0, when + v.attack + v.hold, v.decay / 3);
      osc.stop(decayEnd + 0.05);
    } else {
      g.gain.setValueAtTime(peak, Math.max(when + v.attack, end - 0.06));
      g.gain.linearRampToValueAtTime(0, end);
      osc.stop(end + 0.05);
    }
    osc.connect(g); g.connect(this.master);
    this.track(osc);
  }

  track(node) {
    this.active.push(node);
    node.onended = () => {
      const i = this.active.indexOf(node);
      if (i >= 0) this.active.splice(i, 1);
    };
  }

  /* Program per (track,channel) from the parsed channel events, so each
   * note sounds with its track's instrument. */
  static programMap(midi) {
    const map = {};
    for (const ev of midi.channelEvents) {
      const b = ev.bytes;
      if ((b[0] & 0xF0) === 0xC0) {
        const key = ev.track + ":" + (b[0] & 0x0F);
        if (!(key in map)) map[key] = b[1];
      }
    }
    return map;
  }

  /* Play notes from fromSec on the song clock. notes: MidiFile notes array
   * (already filtered by the caller, e.g. accompaniment only in game mode).
   * onTick(songSec) fires ~30x/s; onEnded fires once at the song's end. */
  play(notes, fromSec, durationSec, programs, onTick, onEnded) {
    this.stop();
    if (!this.ensure()) return null;
    const ctx = this.ctx;
    const startedAt = ctx.currentTime;
    const sorted = notes.filter((n) => n.endSec >= fromSec)
        .sort((a, b) => a.startSec - b.startSec);
    let cursor = 0;
    const LOOKAHEAD = 0.8;
    const self = this;
    let tickTimer = null;

    function scheduleWindow() {
      const songNow = ctx.currentTime - startedAt + fromSec;
      if (songNow >= durationSec) {
        self.stopTimers();
        if (onEnded) onEnded();
        return;
      }
      const horizon = songNow + LOOKAHEAD;
      try {
        while (cursor < sorted.length && sorted[cursor].startSec <= horizon) {
          const n = sorted[cursor++];
          const when = startedAt + Math.max(0, n.startSec - fromSec);
          const dur = Math.max(0.05, n.endSec - Math.max(n.startSec, fromSec));
          const prog = programs ? programs[n.track + ":" + n.channel] : undefined;
          self.note(n.pitch, n.velocity, when, dur, prog, n.channel === 9);
        }
      } catch (e) {
        // A bad note must not kill the scheduler: the song would go silent
        // while the clock runs on. Skip it and keep the chain alive.
        if (root.console) console.warn("tengok: a note could not be scheduled", e);
      }
      self.timer = setTimeout(scheduleWindow, 200);
    }

    tickTimer = setInterval(() => {
      const songNow = ctx.currentTime - startedAt + fromSec;
      if (onTick) onTick(songNow);
      if (songNow >= durationSec + 0.5) {
        // Backstop: if the scheduler chain ever stalls, the song still ends.
        self.stopTimers();
        if (onEnded) onEnded();
      }
    }, 33);
    this.tickTimer = tickTimer;
    scheduleWindow();

    return {
      positionSec: () => ctx.currentTime - startedAt + fromSec,
      stop: () => this.stop(),
    };
  }

  stopTimers() {
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = null; }
  }

  stop() {
    this.stopTimers();
    const nodes = this.active.slice();
    for (const n of nodes) {
      try { n.stop(0); } catch (e) { /* already stopped */ }
    }
    this.active = [];
  }
}

root.TengokSynth = { Synth: Synth, voiceFor: voiceFor };
})(typeof window !== "undefined" ? window : globalThis);
