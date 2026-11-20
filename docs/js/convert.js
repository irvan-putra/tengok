/* tengok web - offline file conversion.
 * Rules from the owner: the original file is never touched, the result is a
 * new download, and every pair states its quality loss honestly. Nothing is
 * uploaded; conversions run on this device's browser only.
 * The WAV and text encoders are pure functions so node can test them. */
(function (root) {
"use strict";

/* --- Pure: PCM -> 16-bit WAV (RIFF). channels: array of Float32Array. --- */
function encodeWav(channels, sampleRate) {
  const numCh = channels.length;
  const frames = channels[0].length;
  const dataBytes = frames * numCh * 2;
  const out = new Uint8Array(44 + dataBytes);
  const dv = new DataView(out.buffer);
  const writeStr = (off, s) => { for (let i = 0; i < s.length; i++) out[off + i] = s.charCodeAt(i); };
  writeStr(0, "RIFF");
  dv.setUint32(4, 36 + dataBytes, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true); // PCM
  dv.setUint16(22, numCh, true);
  dv.setUint32(24, sampleRate, true);
  dv.setUint32(28, sampleRate * numCh * 2, true);
  dv.setUint16(32, numCh * 2, true);
  dv.setUint16(34, 16, true);
  writeStr(36, "data");
  dv.setUint32(40, dataBytes, true);
  let o = 44;
  for (let i = 0; i < frames; i++) {
    for (let c = 0; c < numCh; c++) {
      let v = channels[c][i];
      v = Math.max(-1, Math.min(1, v));
      dv.setInt16(o, Math.round(v * 32767), true);
      o += 2;
    }
  }
  return out;
}

/* --- Pure: text -> Latin-1 bytes; unmappable characters become '?'. --- */
function encodeLatin1(text) {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    out[i] = c <= 0xFF ? c : 0x3F;
  }
  return out;
}

/* --- Pure: bytes -> text, UTF-8 with a Latin-1 fallback (like the viewer). --- */
function decodeText(bytes) {
  try {
    return { text: new TextDecoder("utf-8", { fatal: true }).decode(bytes), encoding: "UTF-8" };
  } catch (e) {
    return { text: new TextDecoder("iso-8859-1").decode(bytes), encoding: "Latin-1" };
  }
}

function encodeUtf8(text) {
  return new TextEncoder().encode(text);
}

/* What the current file can become. Each target names its quality story. */
function targetsFor(kind) {
  switch (kind) {
    case "IMAGE":
      return [
        { id: "png", label: "PNG image", ext: "png", mime: "image/png",
          note: "Lossless output encoding. Transparency kept; usually a bigger file. Metadata (like EXIF) is not carried over." },
        { id: "jpeg", label: "JPEG image", ext: "jpg", mime: "image/jpeg",
          note: "Lossy at 92% quality: slightly smaller, fine detail softens. Transparency becomes white." },
        { id: "webp", label: "WebP image", ext: "webp", mime: "image/webp",
          note: "Lossy at 95% quality: small with good detail. Transparency kept." },
      ];
    case "AUDIO":
      return [
        { id: "wav", label: "WAV audio", ext: "wav", mime: "audio/wav",
          note: "Uncompressed PCM, much bigger. The browser decodes and resamples the audio, which can alter the data slightly; the sample rate follows the browser's decoder. Anything the source codec already lost stays lost." },
      ];
    case "TEXT":
      return [
        { id: "utf8", label: "UTF-8 text", ext: "txt", mime: "text/plain;charset=utf-8",
          note: "Re-saved as UTF-8. Characters survive when the source encoding is detected correctly." },
        { id: "latin1", label: "Latin-1 text", ext: "txt", mime: "text/plain",
          note: "Old single-byte encoding. Characters outside Latin-1 become '?' - that loss cannot be undone in the result." },
      ];
    default:
      return [];
  }
}

/* --- Browser-only converters --- */

async function convertImage(file, target) {
  const bitmap = await createImageBitmap(file);
  try {
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const g = canvas.getContext("2d");
    if (target.id === "jpeg") {
      g.fillStyle = "#FFFFFF";
      g.fillRect(0, 0, canvas.width, canvas.height);
    }
    g.drawImage(bitmap, 0, 0);
    const quality = target.id === "jpeg" ? 0.92 : target.id === "webp" ? 0.95 : undefined;
    const blob = await new Promise((resolve, reject) => {
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("this browser cannot encode " + target.label))),
          target.mime, quality);
    });
    return blob;
  } finally {
    bitmap.close();
  }
}

async function convertAudioToWav(file, ctx) {
  const buf = await file.arrayBuffer();
  const decoded = await ctx.decodeAudioData(buf);
  const channels = [];
  for (let c = 0; c < decoded.numberOfChannels; c++) channels.push(decoded.getChannelData(c));
  const bytes = encodeWav(channels, decoded.sampleRate);
  return new Blob([bytes], { type: "audio/wav" });
}

async function convertText(file, target) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  const { text } = decodeText(bytes);
  const out = target.id === "latin1" ? encodeLatin1(text) : encodeUtf8(text);
  return new Blob([out], { type: target.mime });
}

function outName(inName, ext) {
  const base = (inName || "file").replace(/\.[^.]*$/, "") || "file";
  return base + "." + ext;
}

function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

/* The Convert card shown under a viewer. */
function convertCard(file, kind, ctx) {
  const V = root.TengokViewers;
  const el = V.el;
  const targets = targetsFor(kind);
  if (targets.length === 0) return null;
  const card = el("div", "card");
  card.appendChild(el("div", "card-title", "Convert"));
  card.appendChild(el("div", "muted",
      "Offline, right here: the original stays untouched and the result downloads as a new file."));
  const animated = (file.type === "image/gif" || /\.gif$/i.test(file.name || ""));
  if (kind === "IMAGE" && animated) {
    card.appendChild(el("div", "muted",
        "Note: this is animated; conversion keeps only the first frame."));
  }
  const status = el("div", "muted", "");
  for (const t of targets) {
    const row = el("div", "convert-row");
    const b = el("button", "btn", "Save as " + t.label);
    b.addEventListener("click", async () => {
      b.disabled = true;
      status.textContent = "Converting...";
      try {
        let blob;
        if (kind === "IMAGE") blob = await convertImage(file, t);
        else if (kind === "AUDIO") {
          if (!ctx.synth.ensure()) throw new Error("this browser has no Web Audio support");
          blob = await convertAudioToWav(file, ctx.synth.ctx);
        } else blob = await convertText(file, t);
        download(blob, outName(file.name, t.ext));
        status.textContent = "Saved " + outName(file.name, t.ext)
            + " (" + V.humanSize(blob.size) + "). Check your downloads.";
      } catch (e) {
        status.textContent = "Could not convert: " + (e && e.message ? e.message : e);
      }
      b.disabled = false;
    });
    row.appendChild(b);
    row.appendChild(el("div", "muted convert-note", t.note));
    card.appendChild(row);
  }
  card.appendChild(status);
  return card;
}

root.TengokConvert = {
  encodeWav: encodeWav,
  encodeLatin1: encodeLatin1,
  decodeText: decodeText,
  encodeUtf8: encodeUtf8,
  targetsFor: targetsFor,
  outName: outName,
  convertCard: convertCard,
};
})(typeof window !== "undefined" ? window : globalThis);
