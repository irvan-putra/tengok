/* tengok web - file kind sniffer.
 * Faithful port of Sniffer.java: leading bytes first, then declared MIME,
 * then extension, then a text heuristic. Detection is a hint for picking a
 * viewer, never a security boundary. No DOM; loadable in node for tests. */
(function (root) {
"use strict";

const MIDI = "MIDI";
const IMAGE = "IMAGE";
const AUDIO = "AUDIO";
const VIDEO = "VIDEO";
const TEXT = "TEXT";
const HEX = "HEX";
const ZIP = "ZIP";

function byMagic(h) {
  if (!h || h.length < 4) return null;
  if (h[0] === 0x50 && h[1] === 0x4B && ((h[2] === 3 && h[3] === 4) || (h[2] === 5 && h[3] === 6) || (h[2] === 7 && h[3] === 8))) return ZIP;
  if (h[0] === 0x4D && h[1] === 0x54 && h[2] === 0x68 && h[3] === 0x64) return MIDI; // MThd
  if (h[0] === 0x89 && h[1] === 0x50 && h[2] === 0x4E && h[3] === 0x47) return IMAGE; // PNG
  if (h[0] === 0xFF && h[1] === 0xD8) return IMAGE; // jpeg
  if (h[0] === 0x47 && h[1] === 0x49 && h[2] === 0x46 && h[3] === 0x38) return IMAGE; // GIF8
  if (h[0] === 0x42 && h[1] === 0x4D) return IMAGE; // BM
  if (h.length >= 12 && h[0] === 0x52 && h[1] === 0x49 && h[2] === 0x46 && h[3] === 0x46
      && h[8] === 0x57 && h[9] === 0x45 && h[10] === 0x42 && h[11] === 0x50) return IMAGE; // WEBP
  if (h.length >= 12 && h[0] === 0x52 && h[1] === 0x49 && h[2] === 0x46 && h[3] === 0x46
      && h[8] === 0x57 && h[9] === 0x41 && h[10] === 0x56 && h[11] === 0x45) return AUDIO; // WAVE
  if (h.length >= 12 && h[4] === 0x66 && h[5] === 0x74 && h[6] === 0x79 && h[7] === 0x70) {
    const brand = String.fromCharCode(h[8], h[9], h[10], h[11]);
    if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("hevc")
        || brand.startsWith("mif1") || brand.startsWith("msf1") || brand.startsWith("avif")
        || brand.startsWith("avis")) return IMAGE;
    if (brand.startsWith("M4A ") || brand.startsWith("M4B ")) return AUDIO;
    return VIDEO; // mp4/mov/3gp family
  }
  if (h[0] === 0x1A && h[1] === 0x45 && h[2] === 0xDF && h[3] === 0xA3) return VIDEO; // EBML: mkv/webm
  if (h[0] === 0x4F && h[1] === 0x67 && h[2] === 0x67 && h[3] === 0x53) return AUDIO; // OggS
  if (h[0] === 0x66 && h[1] === 0x4C && h[2] === 0x61 && h[3] === 0x43) return AUDIO; // fLaC
  if (h[0] === 0x49 && h[1] === 0x44 && h[2] === 0x33) return AUDIO; // ID3
  if (h[0] === 0xFF && (h[1] & 0xE0) === 0xE0) return AUDIO; // mp3 frame sync
  return null;
}

function byMime(mime) {
  if (!mime) return null;
  const m = mime.toLowerCase();
  if (m === "audio/midi" || m === "audio/x-midi" || m === "audio/mid"
      || m === "application/x-midi") return MIDI;
  if (m === "application/zip" || m === "application/x-zip-compressed") return ZIP;
  if (m.startsWith("image/")) return IMAGE;
  if (m === "audio/mpeg" || m === "audio/mp4" || m === "audio/ogg"
      || m === "audio/flac" || m === "audio/x-wav" || m === "audio/wav"
      || m === "audio/aac" || m === "audio/opus" || m === "audio/3gpp") return AUDIO;
  if (m.startsWith("video/")) return VIDEO;
  if (m.startsWith("text/")) return TEXT;
  if (m === "application/json" || m === "application/xml") return TEXT;
  return null;
}

const EXT = {
  zip: ZIP,
  mid: MIDI, midi: MIDI, rmi: MIDI, xmf: MIDI, mxmf: MIDI,
  png: IMAGE, jpg: IMAGE, jpeg: IMAGE, webp: IMAGE, gif: IMAGE, bmp: IMAGE,
  heic: IMAGE, heif: IMAGE, avif: IMAGE, wbmp: IMAGE,
  mp3: AUDIO, ogg: AUDIO, oga: AUDIO, flac: AUDIO, wav: AUDIO, m4a: AUDIO,
  aac: AUDIO, opus: AUDIO, amr: AUDIO, "3ga": AUDIO,
  mp4: VIDEO, m4v: VIDEO, mov: VIDEO, mkv: VIDEO, webm: VIDEO, "3gp": VIDEO,
  txt: TEXT, log: TEXT, md: TEXT, markdown: TEXT, json: TEXT, xml: TEXT,
  csv: TEXT, tsv: TEXT, yaml: TEXT, yml: TEXT, ini: TEXT, cfg: TEXT,
  conf: TEXT, properties: TEXT, sh: TEXT, py: TEXT, java: TEXT, kt: TEXT,
  js: TEXT, css: TEXT, html: TEXT, htm: TEXT, c: TEXT, cpp: TEXT, h: TEXT,
  sql: TEXT, srt: TEXT,
};

function byExtension(name) {
  if (!name) return null;
  const dot = name.lastIndexOf(".");
  if (dot < 0 || dot === name.length - 1) return null;
  return EXT[name.substring(dot + 1).toLowerCase()] || null;
}

/* Bytes with no NULs and almost all printable/whitespace are shown as text. */
function looksLikeText(h) {
  if (!h || h.length === 0) return false;
  let bad = 0;
  for (let i = 0; i < h.length; i++) {
    const v = h[i];
    if (v === 0) return false;
    const printable = v >= 0x20 && v < 0x7F;
    const space = v === 0x0A || v === 0x0D || v === 0x09 || v === 0x0C;
    const utf8Lead = v >= 0xC0; // crude but fine for a hint
    if (!printable && !space && !utf8Lead) bad++;
  }
  return bad * 25 < h.length; // under 4% odd bytes
}

/* name: file name, declaredMime: browser-reported type, head: leading bytes
 * (Uint8Array, a few KB is plenty). */
function sniff(name, declaredMime, head) {
  return byMagic(head) || byMime(declaredMime) || byExtension(name)
      || (looksLikeText(head) ? TEXT : HEX);
}

root.TengokSniffer = {
  ZIP: ZIP, MIDI: MIDI, IMAGE: IMAGE, AUDIO: AUDIO, VIDEO: VIDEO, TEXT: TEXT, HEX: HEX,
  byMagic: byMagic, byMime: byMime, byExtension: byExtension,
  looksLikeText: looksLikeText, sniff: sniff,
};
})(typeof window !== "undefined" ? window : globalThis);
