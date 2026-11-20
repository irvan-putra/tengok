#!/usr/bin/env python3
"""Writes latepad.mid: the same late-entrance fixture the debug build
generates in FixtureActivity.latePadMid(), as a real file for the
published-APK smoke (release builds carry no fixture screen, so the
fixture must arrive as a user file).

Format 1, 480 tpq, 120 bpm: bass plays one note a second from bar one;
the pad (warm pad, program 89) enters at 12.0 s, plays at 12.5 s, then
rests until 29.0 s. Three pad notes, 32 s total.
"""
import struct
import sys


def varlen(v):
    out = [v & 0x7F]
    v >>= 7
    while v:
        out.insert(0, (v & 0x7F) | 0x80)
        v >>= 7
    return bytes(out)


def event(delta, status, d1, d2=-1):
    b = varlen(delta) + bytes([status & 0xFF, d1 & 0x7F])
    if d2 >= 0:
        b += bytes([d2 & 0x7F])
    return b


def meta_text(delta, mtype, text):
    b = text.encode("utf-8")
    return varlen(delta) + bytes([0xFF, mtype]) + varlen(len(b)) + b


def meta_tempo(delta, us_per_quarter):
    return varlen(delta) + bytes([0xFF, 0x51, 0x03]) + us_per_quarter.to_bytes(3, "big")


def meta_time_sig(delta, nn, dd):
    # SMF stores the denominator as a power of two, like the Java fixture
    p = 0
    while (1 << p) < dd:
        p += 1
    return varlen(delta) + bytes([0xFF, 0x58, 0x04, nn, p, 24, 8])


def meta_end(delta):
    return varlen(delta) + bytes([0xFF, 0x2F, 0x00])


def smf(fmt, division, tracks):
    out = b"MThd" + struct.pack(">IHHH", 6, fmt, len(tracks), division)
    for t in tracks:
        out += b"MTrk" + struct.pack(">I", len(t)) + t
    return out


def late_pad_mid():
    t0 = meta_text(0, 0x03, "tempo") + meta_tempo(0, 500000) + meta_time_sig(0, 4, 4) + meta_end(0)

    t1 = meta_text(0, 0x03, "bass") + event(0, 0xC1, 32)
    for i in range(32):  # one note a second, 0..31 s
        p = 36 + (i % 5)
        t1 += event(0, 0x91, p, 90) + event(960, 0x81, p, 0)
    t1 += meta_end(0)

    t2 = meta_text(0, 0x03, "pad") + event(0, 0xC2, 89)
    t2 += event(11520, 0x92, 60, 80) + event(480, 0x82, 60, 0)   # 12.0 s entrance
    t2 += event(0, 0x92, 64, 80) + event(480, 0x82, 64, 0)       # 12.5 s
    t2 += event(15360, 0x92, 67, 80) + event(480, 0x82, 67, 0)   # 29.0 s, 16 s rest
    t2 += meta_end(0)

    return smf(1, 480, [t0, t1, t2])


if __name__ == "__main__":
    open(sys.argv[1], "wb").write(late_pad_mid())
    print("wrote", sys.argv[1], len(late_pad_mid()), "bytes")
