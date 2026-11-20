/* tengok web - intake and routing: pick or drop files, sniff what they are,
 * open the matching viewer. Files are read locally and never uploaded. */
(function (root) {
"use strict";

const S = root.TengokSniffer;
const V = root.TengokViewers;
const M = root.TengokMidi;

const MAX_MIDI_BYTES = 32 * 1024 * 1024; // mirrors the Android cap
const HEAD_BYTES = 8192;

const synth = new root.TengokSynth.Synth();
let entries = []; // {file, kind}
let current = -1;
let cleanups = [];

const viewArea = document.getElementById("view");
const filesBar = document.getElementById("files");
const linkLine = document.getElementById("view-link");

function runCleanups() {
  const fns = cleanups;
  cleanups = [];
  for (const fn of fns) {
    try { fn(); } catch (e) { /* cleanup is best effort */ }
  }
}

const ctx = {
  synth: synth,
  viewArea: viewArea,
  onCleanup: (fn) => cleanups.push(fn),
  onHex: () => { if (current >= 0) showEntry(current, S.HEX); },
  showCurrentFile: () => { if (current >= 0) showEntry(current); },
};

async function kindOf(file) {
  const head = new Uint8Array(await file.slice(0, HEAD_BYTES).arrayBuffer());
  return S.sniff(file.name, file.type, head);
}

async function addFiles(list) {
  const files = Array.from(list);
  if (files.length === 0) return;
  for (const file of files) {
    try {
      entries.push({ file: file, kind: await kindOf(file) });
    } catch (e) {
      entries.push({ file: file, kind: S.HEX });
    }
  }
  renderFilesBar();
  showEntry(entries.length - files.length);
}

function renderFilesBar() {
  filesBar.replaceChildren();
  if (entries.length < 1) return;
  const open = V.el("button", "btn", "+ Open a file");
  open.addEventListener("click", () => document.getElementById("file-input").click());
  filesBar.appendChild(open);
  for (let i = 0; i < entries.length; i++) {
    const b = V.el("button", "btn file-tab" + (i === current ? " active" : ""),
        entries[i].file.name || "file " + (i + 1));
    b.addEventListener("click", () => showEntry(i));
    filesBar.appendChild(b);
  }
  const clear = V.el("button", "btn", "Clear all");
  clear.addEventListener("click", () => {
    entries = [];
    current = -1;
    runCleanups();
    renderFilesBar();
    viewArea.replaceChildren();
    document.getElementById("drop").style.display = "";
    document.getElementById("about").style.display = "";
    history.replaceState(null, "", location.pathname);
    linkLine.style.display = "none";
  });
  filesBar.appendChild(clear);
}

/* The shareable URL of the current sample view, in the address bar and as
 * selectable text. User-opened files have no link: their bytes never leave
 * the device, so the parameter is cleared instead. */
function showSampleLink(entry) {
  if (entry && entry.sampleId) {
    const url = location.origin + location.pathname + "?sample=" + entry.sampleId;
    history.replaceState(null, "", "?sample=" + entry.sampleId);
    linkLine.textContent = "Link to this view: " + url;
    linkLine.style.display = "";
  } else {
    history.replaceState(null, "", location.pathname);
    linkLine.style.display = "none";
  }
}

function showEntry(index, forceKind) {
  if (index < 0 || index >= entries.length) return;
  runCleanups();
  current = index;
  renderFilesBar();
  viewArea.replaceChildren();
  document.getElementById("drop").style.display = "none";
  document.getElementById("about").style.display = "none";
  showSampleLink(entries[index]);

  const { file } = entries[index];
  const kind = forceKind || entries[index].kind;
  const title = V.el("div", "view-title");
  title.appendChild(V.el("span", "view-name", file.name || "(unnamed file)"));
  title.appendChild(V.el("span", "muted", " · " + V.humanSize(file.size)));
  viewArea.appendChild(title);

  switch (kind) {
    case S.MIDI:
      if (file.size > MAX_MIDI_BYTES) {
        viewArea.appendChild(V.errorCard(
            "MIDI view is capped at 32 MB (same as the Android app); this file is "
            + V.humanSize(file.size) + ".", ctx.onHex));
        viewArea.appendChild(V.detailsCard(file, "MIDI", null));
        return;
      }
      file.arrayBuffer().then((buf) => {
        let midi;
        try {
          midi = M.MidiFile.parse(new Uint8Array(buf));
        } catch (e) {
          viewArea.appendChild(V.errorCard(
              "The MIDI parser could not read it: " + e.message, ctx.onHex));
          viewArea.appendChild(V.detailsCard(file, "MIDI", null));
          return;
        }
        M && root.TengokMidiView.showMidi(viewArea, file, midi, ctx);
      });
      break;
    case S.IMAGE:
      Promise.resolve(V.showImage(viewArea, file, ctx)).then(() => appendConvert(file, S.IMAGE));
      break;
    case S.AUDIO:
      Promise.resolve(V.showAudio(viewArea, file, ctx)).then(() => appendConvert(file, S.AUDIO));
      break;
    case S.TEXT:
      Promise.resolve(V.showText(viewArea, file, ctx)).then(() => appendConvert(file, S.TEXT));
      break;
    default:
      V.showHex(viewArea, file, ctx, "data");
  }
}

function appendConvert(file, kind) {
  if (current < 0 || entries[current].file !== file) return; // user moved on
  const card = root.TengokConvert.convertCard(file, kind, ctx);
  if (card) viewArea.appendChild(card);
}

/* Sample files generated by ci/make_web_fixtures.py (synthetic, no user data). */
const SAMPLES = [
  ["midi", "etude.mid", "MIDI song"],
  ["tinymidi", "twotrack.mid", "tiny MIDI"],
  ["image", "pixel.png", "image"],
  ["audio", "tone.wav", "audio"],
  ["text", "sample.txt", "text"],
];

/* A fixed allowlist for ?sample= deep links: only these built-in synthetic
 * fixtures can be auto-opened. Remote URLs are never fetched. */
function sampleForId(id) {
  for (const [sid, file] of SAMPLES) if (sid === id) return file;
  return null;
}

function loadSample(name) {
  fetch("fixtures/" + name)
      .then((r) => {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.blob();
      })
      .then((blob) => {
        const type = name.endsWith(".mid") ? "audio/midi"
            : name.endsWith(".png") ? "image/png"
            : name.endsWith(".wav") ? "audio/wav" : "text/plain";
        const before = entries.length;
        addFiles([new File([blob], name, { type: type })]).then(() => {
          for (const [sid, file] of SAMPLES) {
            if (file === name && entries[before]) entries[before].sampleId = sid;
          }
          if (current === before) showSampleLink(entries[before]);
        });
      })
      .catch((e) => {
        viewArea.replaceChildren(V.errorCard("Could not load the sample: " + e.message, null));
      });
}

function wire() {
  const samples = document.getElementById("samples");
  for (const [, file, label] of SAMPLES) {
    const b = V.el("button", "btn", label);
    b.addEventListener("click", () => loadSample(file));
    samples.appendChild(b);
  }

  const picker = document.getElementById("picker");
  const input = document.getElementById("file-input");
  picker.addEventListener("click", () => input.click());
  input.addEventListener("change", () => {
    addFiles(input.files);
    input.value = "";
  });

  let depth = 0;
  document.addEventListener("dragenter", (e) => {
    e.preventDefault();
    depth++;
    document.body.classList.add("dragging");
  });
  document.addEventListener("dragleave", (e) => {
    e.preventDefault();
    if (--depth <= 0) {
      depth = 0;
      document.body.classList.remove("dragging");
    }
  });
  document.addEventListener("dragover", (e) => e.preventDefault());
  document.addEventListener("drop", (e) => {
    e.preventDefault();
    depth = 0;
    document.body.classList.remove("dragging");
    if (e.dataTransfer && e.dataTransfer.files.length) addFiles(e.dataTransfer.files);
  });
}

wire();

const sampleParam = new URLSearchParams(location.search).get("sample");
if (sampleParam) {
  const f = sampleForId(sampleParam);
  if (f) loadSample(f);
}

root.TengokApp = { addFiles: addFiles, showEntry: showEntry, ctx: ctx,
    sampleForId: sampleForId };
})(typeof window !== "undefined" ? window : globalThis);
