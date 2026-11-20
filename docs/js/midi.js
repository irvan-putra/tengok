/* tengok web - Standard MIDI File reader.
 * A faithful port of the Android app's MidiFile.java: format 0/1/2, running
 * status, SysEx and common meta events, note-on/off pairing, tempo map to
 * seconds. Bounded like the original: every length is checked before use.
 * No DOM, no dependencies; also loadable in node for tests. */
(function (root) {
"use strict";

class ParseException extends Error {}

const MAX_EVENTS = 2000000;
const KEY_NAMES = [
  "Cb major", "Gb major", "Db major", "Ab major", "Eb major", "Bb major", "F major",
  "C major", "G major", "D major", "A major", "E major", "B major", "F# major", "C# major"];

class Reader {
  constructor(d, start, end) {
    this.d = d; // Uint8Array
    this.pos = start === undefined ? 0 : start;
    this.end = end === undefined ? d.length : end;
  }
  remaining() { return this.end - this.pos; }
  pushBack() {
    if (this.pos <= 0) throw new ParseException("pushback at start");
    this.pos--;
  }
  u8() {
    if (this.pos >= this.end) throw new ParseException("unexpected end of file");
    return this.d[this.pos++];
  }
  u16() { return (this.u8() << 8) | this.u8(); }
  u32() { return ((this.u8() << 24) | (this.u8() << 16) | (this.u8() << 8) | this.u8()) >>> 0; }
  varlen() {
    let v = 0;
    for (let i = 0; i < 4; i++) {
      const b = this.u8();
      v = (v << 7) | (b & 0x7F);
      if ((b & 0x80) === 0) return v;
    }
    throw new ParseException("variable-length number runs past 4 bytes");
  }
  skip(n) {
    if (n < 0 || n > this.end - this.pos) throw new ParseException("unexpected end of file");
    this.pos += n;
  }
  text(n) {
    if (n > this.end - this.pos) throw new ParseException("unexpected end of file");
    let s = "";
    for (let i = 0; i < n; i++) s += String.fromCharCode(this.d[this.pos + i]);
    this.pos += n;
    return s.trim();
  }
  subReader(len) {
    if (len > this.end - this.pos) throw new ParseException("unexpected end of file");
    return new Reader(this.d, this.pos, this.pos + len);
  }
}

function keyName(sf, minor) {
  if (sf < -7 || sf > 7) return "";
  let base = KEY_NAMES[sf + 7];
  if (minor === 1) base = base.replace(" major", " minor");
  return base;
}

class MidiFile {
  constructor() {
    this.format = 0;
    this.ticksPerQuarter = 480;
    this.smpte = false;
    this.smpteTicksPerSecond = 0;
    this.timeSigNum = 4;
    this.timeSigDen = 4;
    this.keySig = "";
    this.tempos = [];       // {tick, usPerQuarter}
    this.notes = [];        // {startTick,endTick,startSec,endSec,pitch,velocity,channel,track}
    this.channelEvents = [];// {tick, track, bytes:Uint8Array}
    this.tracks = [];       // {index,name,noteCount,minPitch,maxPitch,endTick,programs,channels}
    this.durationTicks = 0;
    this.durationSeconds = 0;
  }

  static parse(bytes) {
    const d = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    if (d.length < 14) throw new ParseException("too short to be a MIDI file");
    const m = new MidiFile();
    const r = new Reader(d);
    if (r.u8() !== 0x4D || r.u8() !== 0x54 || r.u8() !== 0x68 || r.u8() !== 0x64) {
      throw new ParseException("missing the MThd header");
    }
    const hlen = r.u32();
    if (hlen < 6) throw new ParseException("header chunk is too short");
    if (hlen > 1024) throw new ParseException("header chunk is oddly long");
    m.format = r.u16();
    const ntracks = r.u16();
    const division = r.u16();
    r.skip(hlen - 6);
    if (m.format > 2) throw new ParseException("unknown MIDI format " + m.format);
    if (ntracks === 0) throw new ParseException("the file has no tracks");
    if ((division & 0x8000) !== 0) {
      m.smpte = true;
      const fps = 256 - ((division >> 8) & 0xFF);
      const tpf = division & 0xFF;
      if (tpf === 0) throw new ParseException("bad SMPTE division");
      m.smpteTicksPerSecond = fps * tpf;
    } else {
      m.ticksPerQuarter = division === 0 ? 480 : division;
    }
    let totalEvents = 0;
    for (let t = 0; t < ntracks; t++) {
      totalEvents += m.parseTrack(r, t);
      if (totalEvents > MAX_EVENTS) throw new ParseException("too many events");
    }
    m.finishTiming();
    return m;
  }

  parseTrack(r, index) {
    if (r.remaining() < 8) throw new ParseException("track " + (index + 1) + " is missing");
    if (r.u8() !== 0x4D || r.u8() !== 0x54 || r.u8() !== 0x72 || r.u8() !== 0x6B) {
      throw new ParseException("track " + (index + 1) + " has no MTrk header");
    }
    const len = r.u32();
    if (len > r.remaining()) throw new ParseException("track " + (index + 1) + " is truncated");
    const tr = r.subReader(len);
    r.skip(len);

    const track = {
      index: index, name: "", noteCount: 0, minPitch: -1, maxPitch: -1,
      endTick: 0, programs: [], channels: new Array(16).fill(false),
    };
    const open = new Map(); // key: ch*128+pitch -> array of {startTick, velocity}
    let tick = 0;
    let status = 0;
    let events = 0;
    let ended = false;

    while (tr.remaining() > 0 && !ended) {
      const delta = tr.varlen();
      tick += delta;
      let b = tr.u8();
      if (b < 0x80) {
        if (status === 0) throw new ParseException("running status with nothing running");
        tr.pushBack();
        b = status;
      } else if (b < 0xF0) {
        status = b;
      }
      events++;
      if (events > MAX_EVENTS) throw new ParseException("too many events in one track");

      const type = b & 0xF0;
      const ch = b & 0x0F;
      if (b === 0xFF) {
        const meta = tr.u8();
        const mlen = tr.varlen();
        if (mlen > tr.remaining()) throw new ParseException("meta event is truncated");
        switch (meta) {
          case 0x2F:
            ended = true;
            tr.skip(mlen);
            break;
          case 0x51: {
            if (mlen !== 3) throw new ParseException("bad tempo event");
            const us = (tr.u8() << 16) | (tr.u8() << 8) | tr.u8();
            this.tempos.push({ tick: tick, usPerQuarter: us });
            break;
          }
          case 0x58:
            if (mlen >= 2) {
              this.timeSigNum = tr.u8();
              const denPow = tr.u8();
              this.timeSigDen = 1 << Math.min(denPow, 6);
              tr.skip(mlen - 2);
            } else {
              tr.skip(mlen);
            }
            break;
          case 0x59: {
            if (mlen >= 1) {
              let sf = tr.u8();
              if (sf >= 128) sf -= 256;
              const minor = mlen >= 2 ? tr.u8() : 0;
              if (mlen > 2) tr.skip(mlen - 2);
              this.keySig = keyName(sf, minor);
            } else {
              tr.skip(mlen);
            }
            break;
          }
          case 0x03:
            track.name = tr.text(Math.min(mlen, 256));
            if (mlen > 256) tr.skip(mlen - 256);
            break;
          default:
            tr.skip(mlen);
        }
      } else if (b === 0xF0 || b === 0xF7) {
        const slen = tr.varlen();
        if (slen > tr.remaining()) throw new ParseException("SysEx event is truncated");
        tr.skip(slen);
      } else {
        switch (type) {
          case 0x80:
          case 0x90: {
            const pitch = tr.u8();
            const vel = tr.u8();
            const on = type === 0x90 && vel > 0;
            track.channels[ch] = true;
            const key = ch * 128 + pitch;
            if (on) {
              if (!open.has(key)) open.set(key, []);
              open.get(key).push({ startTick: tick, velocity: vel });
            } else {
              const stack = open.get(key);
              if (stack && stack.length > 0) {
                const started = stack.pop();
                const n = {
                  track: index, channel: ch, pitch: pitch,
                  startTick: started.startTick, velocity: started.velocity,
                  endTick: Math.max(tick, started.startTick),
                  startSec: 0, endSec: 0,
                };
                this.notes.push(n);
                track.noteCount++;
                if (track.minPitch < 0 || pitch < track.minPitch) track.minPitch = pitch;
                if (pitch > track.maxPitch) track.maxPitch = pitch;
              }
            }
            break;
          }
          case 0xA0:
          case 0xB0:
          case 0xE0: {
            const d1 = tr.u8();
            const d2 = tr.u8();
            this.channelEvents.push({ tick: tick, track: index, bytes: new Uint8Array([b, d1, d2]) });
            track.channels[ch] = true;
            break;
          }
          case 0xC0: {
            const program = tr.u8();
            this.channelEvents.push({ tick: tick, track: index, bytes: new Uint8Array([b, program]) });
            track.channels[ch] = true;
            if (!track.programs.includes(program)) track.programs.push(program);
            break;
          }
          case 0xD0: {
            const d1 = tr.u8();
            this.channelEvents.push({ tick: tick, track: index, bytes: new Uint8Array([b, d1]) });
            track.channels[ch] = true;
            break;
          }
          default:
            throw new ParseException("unknown event byte 0x" + b.toString(16));
        }
      }
    }
    // Notes never switched off ring until the track's last tick.
    for (const [key, stack] of open) {
      for (const started of stack) {
        const n = {
          track: index, channel: Math.floor(key / 128), pitch: key % 128,
          startTick: started.startTick, velocity: started.velocity, endTick: tick,
          startSec: 0, endSec: 0,
        };
        this.notes.push(n);
        track.noteCount++;
        if (track.minPitch < 0 || n.pitch < track.minPitch) track.minPitch = n.pitch;
        if (n.pitch > track.maxPitch) track.maxPitch = n.pitch;
      }
    }
    track.endTick = tick;
    if (tick > this.durationTicks) this.durationTicks = tick;
    this.tracks.push(track);
    return events;
  }

  finishTiming() {
    const sorted = this.tempos.slice().sort((a, b) => a.tick - b.tick);
    this.tempos = [{ tick: 0, usPerQuarter: 500000 }];
    for (const t of sorted) {
      if (t.tick === 0 && this.tempos.length === 1) {
        this.tempos[0] = t;
      } else {
        this.tempos.push(t);
      }
    }
    for (const n of this.notes) {
      n.startSec = this.tickToSeconds(n.startTick);
      n.endSec = this.tickToSeconds(n.endTick);
    }
    this.durationSeconds = this.tickToSeconds(this.durationTicks);
  }

  tickToSeconds(tick) {
    if (this.smpte) return tick / this.smpteTicksPerSecond;
    let sec = 0;
    let prevTick = 0;
    let us = 500000;
    for (const t of this.tempos) {
      if (t.tick >= tick) break;
      sec += (t.tick - prevTick) * (us / 1000000.0) / this.ticksPerQuarter;
      prevTick = t.tick;
      us = t.usPerQuarter;
    }
    sec += (tick - prevTick) * (us / 1000000.0) / this.ticksPerQuarter;
    return sec;
  }
}

const GM_NAMES = [
  "Acoustic Grand Piano", "Bright Piano", "Electric Grand Piano", "Honky-tonk Piano",
  "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
  "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
  "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
  "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
  "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
  "Nylon Guitar", "Steel Guitar", "Jazz Guitar", "Clean Guitar",
  "Muted Guitar", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
  "Acoustic Bass", "Finger Bass", "Pick Bass", "Fretless Bass",
  "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
  "Violin", "Viola", "Cello", "Contrabass",
  "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
  "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
  "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
  "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
  "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
  "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
  "Oboe", "English Horn", "Bassoon", "Clarinet",
  "Piccolo", "Flute", "Recorder", "Pan Flute",
  "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
  "Square Lead", "Sawtooth Lead", "Calliope Lead", "Chiff Lead",
  "Charang Lead", "Voice Lead", "Fifths Lead", "Bass and Lead",
  "New Age Pad", "Warm Pad", "Polysynth Pad", "Choir Pad",
  "Bowed Pad", "Metallic Pad", "Halo Pad", "Sweep Pad",
  "Rain FX", "Soundtrack FX", "Crystal FX", "Atmosphere FX",
  "Brightness FX", "Goblins FX", "Echoes FX", "Sci-Fi FX",
  "Sitar", "Banjo", "Shamisen", "Koto",
  "Kalimba", "Bagpipe", "Fiddle", "Shanai",
  "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
  "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
  "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
  "Telephone", "Helicopter", "Applause", "Gunshot"];

function gmInstrument(program) {
  if (program < 0 || program > 127) return "Unknown instrument";
  return GM_NAMES[program];
}

const PITCHES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];

function gmPitch(midi) {
  if (midi < 0 || midi > 127) return "?";
  return PITCHES[midi % 12] + (Math.floor(midi / 12) - 1);
}

root.TengokMidi = {
  MidiFile: MidiFile,
  ParseException: ParseException,
  gmInstrument: gmInstrument,
  gmPitch: gmPitch,
};
})(typeof window !== "undefined" ? window : globalThis);
