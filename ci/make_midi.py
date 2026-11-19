#!/usr/bin/env python3
"""Independent synthetic MIDI writer for the smoke test.

This shares no code with the app's Java parser or its fixture writer, so a
file that parses proves interop, not self-agreement. Writes a format-1 file:
tempo track (120 bpm -> 60 bpm halfway) plus a two-note melody track.
"""
import struct, sys


def varlen(v):
    out = [v & 0x7F]
    v >>= 7
    while v:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    return bytes(reversed(out))


def track(body):
    return b"MTrk" + struct.pack(">I", len(body)) + body


def main(path):
    t0 = b""
    t0 += varlen(0) + bytes([0xFF, 0x51, 0x03]) + struct.pack(">I", 500000)[1:]
    t0 += varlen(960) + bytes([0xFF, 0x51, 0x03]) + struct.pack(">I", 1000000)[1:]
    t0 += varlen(0) + bytes([0xFF, 0x2F, 0x00])

    t1 = varlen(0) + bytes([0xFF, 0x03]) + varlen(6) + b"melody"
    t1 += varlen(0) + bytes([0xC0, 40])            # violin
    t1 += varlen(0) + bytes([0x90, 72, 100])       # C5 on
    t1 += varlen(960) + bytes([0x90, 76, 100])     # E5 on (C5 still ringing)
    t1 += varlen(960) + bytes([0x80, 72, 0])       # C5 off
    t1 += varlen(0) + bytes([0x80, 76, 0])         # E5 off
    t1 += varlen(0) + bytes([0xFF, 0x2F, 0x00])

    smf = b"MThd" + struct.pack(">IHHH", 6, 1, 2, 480) + track(t0) + track(t1)
    with open(path, "wb") as f:
        f.write(smf)
    print("wrote", path, len(smf), "bytes")


if __name__ == "__main__":
    main(sys.argv[1])
