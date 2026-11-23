/* tengok web - the image, audio, text and hex viewers plus the details card.
 * Mirrors the Android panels: image with zoom, a simple audio player,
 * monospace text with a Latin-1 fallback, and a paged hex view with the
 * file's facts (declared type, detected type, size, SHA-256). */
(function (root) {
"use strict";

const MAX_TEXT_BYTES = 32 * 1024 * 1024; // mirrors the Android cap
const MAX_HASH_BYTES = 64 * 1024 * 1024; // browser hashing is one-shot
const HEX_PAGE = 4096;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function humanSize(size) {
  if (size < 0) return "unknown size";
  if (size < 1024) return size + " B";
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + " KB";
  if (size < 1024 * 1024 * 1024) return (size / 1048576).toFixed(1) + " MB";
  return (size / 1073741824).toFixed(2) + " GB";
}

async function sha256Hex(buf) {
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* The file's facts: name, declared type, detected kind, size, SHA-256. */
function detailsCard(file, kindLabel, headBytes) {
  const card = el("div", "card");
  card.appendChild(el("div", "card-title", "Details"));
  const rows = [
    ["Name", file.name || "(unnamed)"],
    ["Size", humanSize(file.size)],
    ["Declared type", file.type || "none"],
    ["Detected as", kindLabel],
  ];
  for (const [k, v] of rows) {
    const line = el("div", "fact");
    line.appendChild(el("span", "fact-key", k));
    line.appendChild(el("span", "fact-val", v));
    card.appendChild(line);
  }
  const hashLine = el("div", "fact");
  hashLine.appendChild(el("span", "fact-key", "SHA-256"));
  const hashVal = el("span", "fact-val mono small", "computing...");
  hashLine.appendChild(hashVal);
  card.appendChild(hashLine);
  if (file.size > MAX_HASH_BYTES) {
    hashVal.textContent = "skipped: hashing loads the whole file, capped at 64 MB here";
  } else {
    file.arrayBuffer().then((buf) => sha256Hex(buf)).then((hex) => {
      hashVal.textContent = hex;
    }).catch(() => {
      hashVal.textContent = "unavailable";
    });
  }
  return card;
}

/* Error card with an offer of the hex view, mirroring the app's contract. */
function errorCard(message, onHex) {
  const card = el("div", "card error");
  card.appendChild(el("div", "card-title", "Could not open it"));
  card.appendChild(el("div", "muted", message));
  if (onHex) {
    const btn = el("button", "btn", "Show the raw bytes instead");
    btn.addEventListener("click", onHex);
    card.appendChild(btn);
  }
  return card;
}

function showImage(container, file, ctx) {
  const card = el("div", "card");
  card.appendChild(el("div", "card-title", "Image"));
  const stage = el("div", "image-stage");
  const img = el("img", "image-view");
  img.alt = file.name || "image";
  const url = URL.createObjectURL(file);
  img.src = url;
  img.addEventListener("error", () => {
    stage.replaceChildren(errorCard(
      "This browser cannot decode that image format (HEIC and AVIF only work "
      + "in browsers that ship those decoders).", ctx.onHex));
  });
  let scale = 0; // 0 = fit
  const apply = () => {
    if (scale === 0) {
      img.style.width = "100%";
      img.style.height = "auto";
    } else {
      img.style.width = (img.naturalWidth * scale) + "px";
      img.style.height = "auto";
    }
  };
  img.addEventListener("load", apply);
  const bar = el("div", "toolbar");
  const mkBtn = (label, fn) => {
    const b = el("button", "btn", label);
    b.addEventListener("click", fn);
    bar.appendChild(b);
  };
  mkBtn("Fit", () => { scale = 0; apply(); });
  mkBtn("100%", () => { scale = 1; apply(); });
  mkBtn("+", () => { scale = (scale || 1) * 1.5; apply(); });
  mkBtn("-", () => { scale = Math.max(0.05, (scale || 1) / 1.5); apply(); });
  card.appendChild(bar);
  stage.appendChild(img);
  card.appendChild(stage);
  container.appendChild(card);
  container.appendChild(detailsCard(file, "image", null));
}

function showAudio(container, file, ctx) {
  const card = el("div", "card");
  card.appendChild(el("div", "card-title", "Audio"));
  const audio = el("audio");
  audio.controls = true;
  audio.src = URL.createObjectURL(file);
  audio.addEventListener("error", () => {
    card.replaceChildren(errorCard(
      "This browser has no decoder for that audio format.", ctx.onHex));
  });
  card.appendChild(audio);
  card.appendChild(el("div", "muted",
      "Plays with this browser's own decoders; the file never leaves the tab."));
  container.appendChild(card);
  container.appendChild(detailsCard(file, "audio", null));
}

/* Video plays through the browser's own decoder stack, which reads the
 * MP4 sample table wherever the muxer wrote it and honors each frame's
 * own timestamp - so variable frame-rate recordings (like Bidik's
 * wall-clock screen captures, index at the end of the file, sound on its
 * own offset, or no sound at all) play at the speed they were recorded. */
function showVideo(container, file, ctx) {
  const card = el("div", "card");
  card.appendChild(el("div", "card-title", "Video"));
  const video = el("video", "video-view");
  video.controls = true;
  video.playsInline = true;
  video.src = URL.createObjectURL(file);
  video.addEventListener("error", () => {
    card.replaceChildren(errorCard(
      "This browser has no decoder for that video format.", ctx.onHex));
  });
  card.appendChild(video);
  card.appendChild(el("div", "muted",
      "Plays with this browser's own decoders; the file never leaves the tab."));
  container.appendChild(card);
  container.appendChild(detailsCard(file, "video", null));
}

function showText(container, file, ctx) {
  if (file.size > MAX_TEXT_BYTES) {
    container.appendChild(errorCard(
      "Text view is capped at 32 MB (same as the Android app); this file is "
      + humanSize(file.size) + ".", ctx.onHex));
    container.appendChild(detailsCard(file, "text", null));
    return;
  }
  return file.arrayBuffer().then((buf) => {
    let text;
    let encoding = "UTF-8";
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(buf);
    } catch (e) {
      text = new TextDecoder("iso-8859-1").decode(buf); // Latin-1 fallback
      encoding = "Latin-1";
    }
    const card = el("div", "card");
    card.appendChild(el("div", "card-title", "Text"));
    card.appendChild(el("div", "muted", encoding + " · " + humanSize(file.size)));
    card.appendChild(el("pre", "text-view", text));
    container.appendChild(card);
    container.appendChild(detailsCard(file, "text", null));
  });
}

async function hexPage(file, offset) {
  const slice = file.slice(offset, Math.min(file.size, offset + HEX_PAGE));
  const buf = new Uint8Array(await slice.arrayBuffer());
  const lines = [];
  for (let row = 0; row < buf.length; row += 16) {
    const addr = (offset + row).toString(16).padStart(8, "0");
    let hex = "";
    let ascii = "";
    for (let i = 0; i < 16; i++) {
      const j = row + i;
      if (j < buf.length) {
        hex += buf[j].toString(16).padStart(2, "0") + (i === 7 ? "  " : " ");
        ascii += buf[j] >= 0x20 && buf[j] < 0x7F ? String.fromCharCode(buf[j]) : ".";
      } else {
        hex += "   ";
      }
    }
    lines.push(addr + "  " + hex + " |" + ascii + "|");
  }
  return lines.join("\n");
}

function showHex(container, file, ctx, kindLabel) {
  const card = el("div", "card");
  card.appendChild(el("div", "card-title", "Hex"));
  const muted = el("div", "muted",
      "No dedicated viewer for this yet: these are the file's raw bytes.");
  card.appendChild(muted);
  const bar = el("div", "toolbar");
  const pre = el("pre", "hex-view", "loading...");
  let offset = 0;
  const label = el("span", "muted");
  const render = () => {
    hexPage(file, offset).then((text) => {
      pre.textContent = text;
      const end = Math.min(file.size, offset + HEX_PAGE);
      label.textContent = "bytes " + offset.toLocaleString() + " - " + end.toLocaleString()
          + " of " + file.size.toLocaleString();
    });
  };
  const mkBtn = (text, fn, disabled) => {
    const b = el("button", "btn", text);
    b.addEventListener("click", fn);
    bar.appendChild(b);
    return b;
  };
  mkBtn("◀ Prev", () => { offset = Math.max(0, offset - HEX_PAGE); render(); });
  mkBtn("Next ▶", () => {
    offset = Math.min(Math.max(0, file.size - 1), offset + HEX_PAGE);
    offset = Math.floor(offset / HEX_PAGE) * HEX_PAGE;
    render();
  });
  bar.appendChild(label);
  card.appendChild(bar);
  card.appendChild(pre);
  container.appendChild(card);
  container.appendChild(detailsCard(file, kindLabel || "data", null));
  render();
}

root.TengokViewers = {
  el: el,
  humanSize: humanSize,
  detailsCard: detailsCard,
  errorCard: errorCard,
  showImage: showImage,
  showAudio: showAudio,
  showVideo: showVideo,
  showText: showText,
  showHex: showHex,
  MAX_TEXT_BYTES: MAX_TEXT_BYTES,
};
})(typeof window !== "undefined" ? window : globalThis);
