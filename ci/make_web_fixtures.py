#!/usr/bin/env python3
"""Synthetic fixtures for the tengok web viewer's self-test and sample buttons.

Everything here is generated: no real user files, no copyrighted material.
The expected note/track counts are printed so the JS tests can pin them.
"""
import math
import os
import struct
import sys
import zlib


def varlen(v):
    out = [v & 0x7F]
    v >>= 7
    while v:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    return bytes(reversed(out))


def track(body):
    return b"MTrk" + struct.pack(">I", len(body)) + body


class TrackBuilder:
    def __init__(self):
        self.events = []  # (tick, bytes)
        self.tick = 0

    def add(self, tick, data):
        self.events.append((tick, data))

    def build(self):
        self.events.sort(key=lambda e: e[0])
        out = b""
        last = 0
        for tick, data in self.events:
            out += varlen(tick - last) + data
            last = tick
        out += varlen(0) + bytes([0xFF, 0x2F, 0x00])
        return track(out)


def make_etude(path):
    """A ~24 second, three-track synthetic etude: piano melody, bass, drums."""
    tpq = 480
    us_per_quarter = 600000  # 100 bpm
    beat = tpq

    header = b"MThd" + struct.pack(">IHHH", 6, 1, 4, tpq)

    t0 = varlen(0) + bytes([0xFF, 0x51, 0x03]) + struct.pack(">I", us_per_quarter)[1:]
    t0 += varlen(0) + bytes([0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08])  # 4/4
    t0 += varlen(0) + bytes([0xFF, 0x59, 0x02, 0x00, 0x00])  # C major
    t0 += varlen(0) + bytes([0xFF, 0x2F, 0x00])
    conductor = track(t0)

    # Melody: an 8-beat phrase (16 notes, C4..A5 plus a rolled chord), x3.
    melody = TrackBuilder()
    melody.add(0, bytes([0xFF, 0x03]) + varlen(6) + b"melody")
    melody.add(0, bytes([0xC0, 0]))  # acoustic grand piano
    phrase = [  # (beat_offset, pitch, length_beats, velocity)
        (0.0, 60, 0.5, 96), (0.5, 62, 0.5, 88), (1.0, 64, 0.5, 100), (1.5, 65, 0.5, 80),
        (2.0, 67, 1.0, 104), (3.0, 69, 0.5, 92), (3.5, 67, 0.5, 84),
        (4.0, 65, 0.75, 96), (4.75, 64, 0.25, 76), (5.0, 62, 1.0, 100),
        (6.0, 60, 0.5, 90), (6.5, 64, 0.5, 86), (7.0, 67, 0.5, 98), (7.5, 72, 0.5, 108),
        # rolled chord to finish: human players never land exactly together
        (8.0, 60, 1.5, 100), (8.06, 64, 1.44, 94), (8.11, 67, 1.39, 90),
    ]
    melody_notes = 0
    for rep in range(3):
        base = rep * 10 * beat
        for off, pitch, length, vel in phrase:
            on = int(base + off * beat)
            off_t = int(base + (off + length) * beat)
            melody.add(on, bytes([0x90, pitch, min(127, vel + rep * 6)]))
            melody.add(off_t, bytes([0x80, pitch, 0]))
            melody_notes += 1
    melody_track = melody.build()

    # Bass: one note per beat for 30 beats.
    bass = TrackBuilder()
    bass.add(0, bytes([0xFF, 0x03]) + varlen(4) + b"bass")
    bass.add(0, bytes([0xC0, 32]))  # acoustic bass
    bassline = [36, 36, 41, 41, 43, 43, 41, 38]
    bass_notes = 0
    for i in range(30):
        pitch = bassline[i % len(bassline)] + 12  # C3 region
        bass.add(i * beat, bytes([0x90, pitch, 84]))
        bass.add(i * beat + int(0.9 * beat), bytes([0x80, pitch, 0]))
        bass_notes += 1
    bass_track = bass.build()

    # Drums on channel 9: kick every beat, snare on 2 and 4, for 30 beats.
    drums = TrackBuilder()
    drums.add(0, bytes([0xFF, 0x03]) + varlen(5) + b"drums")
    drum_notes = 0
    for i in range(30):
        drums.add(i * beat, bytes([0x99, 36, 100]))
        drums.add(i * beat + int(0.25 * beat), bytes([0x89, 36, 0]))
        drum_notes += 1
        if i % 4 in (1, 3):
            drums.add(i * beat, bytes([0x99, 38, 96]))
            drums.add(i * beat + int(0.2 * beat), bytes([0x89, 38, 0]))
            drum_notes += 1
    drums_track = drums.build()

    data = header + conductor + melody_track + bass_track + drums_track
    with open(path, "wb") as f:
        f.write(data)
    total = melody_notes + bass_notes + drum_notes
    last_beat = 29 + 0.9  # the bass line's final note-off is the last event
    print(f"etude: tracks=4 notes={total} (melody={melody_notes} bass={bass_notes} drums={drum_notes}) "
          f"duration={last_beat * us_per_quarter / 1e6:.2f}s bytes={len(data)}")


def make_pixel_png(path):
    """A 64x64 RGB gradient PNG, written from scratch (no image libraries)."""
    w = h = 64
    rows = b""
    for y in range(h):
        rows += b"\x00"
        for x in range(w):
            rows += bytes([(x * 4) % 256, (y * 4) % 256, ((x + y) * 2) % 256])

    def chunk(kind, payload):
        c = struct.pack(">I", len(payload)) + kind + payload
        return c + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(rows))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print(f"pixel.png: {w}x{h} bytes={len(png)}")


def make_tone_wav(path):
    """Two seconds of a soft 440 Hz sine, 22050 Hz mono 16-bit."""
    rate = 22050
    n = rate * 2
    frames = bytearray()
    for i in range(n):
        v = int(math.sin(2 * math.pi * 440 * i / rate) * 12000
                * min(1.0, i / 500) * min(1.0, (n - i) / 2000))
        frames += struct.pack("<h", v)
    data = bytes(frames)
    wav = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
    wav += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    wav += b"data" + struct.pack("<I", len(data)) + data
    with open(path, "wb") as f:
        f.write(wav)
    print(f"tone.wav: {rate} Hz mono bytes={len(wav)}")


def make_sample_txt(path):
    text = ("tengok sample text\n"
            "==================\n\n"
            "This file is generated by ci/make_web_fixtures.py.\n"
            "It exercises the web viewer's text mode: UTF-8 reading,\n"
            "monospace layout, and ordinary punctuation like \"quotes\",\n"
            "apostrophes, and a dash - nothing more.\n\n"
            "1. one\n2. two\n3. three\n")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"sample.txt: bytes={len(text.encode('utf-8'))}")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/fixtures"
    os.makedirs(out, exist_ok=True)
    make_etude(os.path.join(out, "etude.mid"))
    make_pixel_png(os.path.join(out, "pixel.png"))
    make_tone_wav(os.path.join(out, "tone.wav"))
    make_sample_txt(os.path.join(out, "sample.txt"))


if __name__ == "__main__":
    main()
