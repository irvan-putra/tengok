# tengok

**tengok** (Indonesian for "take a look") is an Android app for peeking into
files, whatever they are. Tap a file in any app, choose tengok, and see what
is inside: a MIDI song with its notes laid out, a photo, a text log, or at
the very least the raw bytes and the file's facts. Nothing is uploaded,
ever: tengok asks for **zero Android permissions**, has no accounts and no
network code at all.

GPL-3.0. Package `com.vanipy.tengok`. Direct APK download is on the
[releases page](../../releases); each release is signed with the tengok key.
The trusted checksum is GitHub's per-asset digest on each release asset;
older releases also carry a `.sha256` sidecar, kept but no longer published
for new releases. Also listed in the [kaji gallery](https://vanipy.com/kaji/apk/).

## What it opens today (v0.8.0)

| Kind | What you get |
| --- | --- |
| MIDI (`.mid`, `.midi`, `.rmi`) | Format/tracks/timing summary, instruments per track (General MIDI names), a piano roll of every note, and playback through the phone's built-in synth |
| Images (`.png`, `.jpg`, `.webp`, `.gif`, `.bmp`, `.heic`/`.avif` on Android versions that ship those decoders) | Pinch-zoom viewer; animated GIFs play |
| Audio (`.mp3`, `.ogg`, `.flac`, `.wav`, `.m4a`, `.aac`) | Simple on-device player |
| Text (`.txt`, `.log`, `.md`, `.json`, `.csv`, source code, and other text) | Monospace reading view, UTF-8 with a Latin-1 fallback |
| PDF (`.pdf`) | Page by page on Android's own PDF engine, with previous/next and zoom steps. Page images only: no text selection, search, forms or annotations. Password-protected PDFs are refused |
| SVG (`.svg`) | A basic drawing of shapes, paths and plain text, with pinch zoom and the source one tap away. The screen lists what it skipped (CSS, gradients, filters, clips, images, `<use>`, scripts, which never run) |
| Word, Excel, PowerPoint (`.docx`, `.xlsx`, `.pptx`) | A text preview read from the file's XML in memory: paragraphs, cell values per sheet, slide text in slide order. No layout, formatting, images, charts or comments |
| ZIP (`.zip`) | Tap entries to view in memory; paths, sizes and encrypted-entry labels |
| Everything else | Paged hex view plus a details sheet: declared type, detected type, size, SHA-256 |

Detection uses the file's actual leading bytes first, then its declared type,
then its extension, so a `.txt` that is really a MIDI file still opens as
music.

## Honest limits

- ZIP listing is capped at 32 MB archives and 2,000 entries. Open stored/deflated entries in memory up to 16 MB per file and 100:1 compression, with checksum validation. No ZIP64, split ZIPs, saving or password opening. Nested ZIPs and Android archive videos use hex. Encrypted entries can be listed only if the directory is readable. Listed paths and sizes come from archive metadata; contents and checksums are not verified. No entry is inflated or written, including paths containing `../`.

- "Any file" means every file opens *somewhere* honest (special viewer, or
  hex + details), not that every format has a rich viewer yet. Older
  `.doc`/`.xls`/`.ppt`, OpenDocument files and non-ZIP archives are not built
  yet; those files open in the hex/details view today.
- PDF, SVG and Office previews (new in 0.8.0) are basic by design. A PDF
  inside a ZIP opens as bytes, because the phone's PDF engine needs a real
  file and tengok writes no temporary copies. SVG preview reads up to 2 MB
  and refuses files that declare XML entities. Office previews use the ZIP
  limits above (32 MB file, 16 MB per part), show at most 600,000 characters
  and 2,000 rows by 100 columns per sheet, and show dates as serial numbers.
  The new viewers are Android only; the web version does not have them yet. Video plays: mp4/mkv/webm/mov/3gp use the
  phone's own decoders, and variable frame-rate recordings (like Bidik's
  screen captures, whose frames carry wall-clock timestamps and whose index
  sits at the end of the file) play at the speed they were recorded.
- MIDI and audio playback use the device's own decoders/synth. Nearly every
  Android phone has a MIDI synth built in; if one does not, tengok says so
  instead of pretending to play.
- Files larger than 32 MB are not loaded into memory for MIDI/text; the hex
  view pages through files of any size.

## Design rules

- Local first: no permissions, no internet, `allowBackup=false`, files are
  streamed from the provider that granted them, never copied away.
- Defensive parsing: every viewer treats its input as hostile; corrupt or
  truncated files get a readable error and an offer of the hex view. Unit
  tests feed the parsers corrupt and adversarial bytes.
- Plain Java; Media3 (conversions) is the only runtime library and the PDF, SVG and
  Office previews add none; no home-grown cryptography (SHA-256 is the platform's `MessageDigest`).

## Web version

`docs/` is a zero-dependency browser build of the same viewers, served with
GitHub Pages at https://vanipy.com/tengok/ : MIDI (piano roll, playback
through a small Web Audio synth, the play-along game), image, audio, text,
and the hex/details view, driven by the same sniffing order (magic bytes,
declared type, extension, text heuristic). The MIDI parser, game rules and
keyboard geometry are line-faithful JavaScript ports of the Java originals;
`node docs/tests/run-tests.mjs` pins them against generated fixtures
(`ci/make_web_fixtures.py`, synthetic only). Browser-specific limits (no
General MIDI sounds, Web MIDI availability, no folder access) are stated on
the page itself.

## Conversion ideas (recorded, not built)

Recorded 2026-11-20, to revisit: text rendered to an image; OCR (image
to text); music to PDF as sheet notes (two ideas: printing notation from a
note format like MIDI, and the much harder transcribing of raw audio);
voice transcribed to a PDF. Conversion itself is in scope for web and
Android; supported pairs are always offline, originals untouched, quality
loss stated per target.

## Building and checking

```
./gradlew testDebugUnitTest   # parser/sniffer/hex unit tests
./gradlew assembleDebug       # debug APK with the fixture screen
```

CI (`.github/workflows/`):

- **qualify** runs the unit tests, assembles the debug APK, and asserts the
  package id, version and that the APK requests **no permissions**.
- **emulator smoke** boots an Android 14 emulator, opens synthetic fixture
  files (including MIDI generated by an independent Python writer, pushed
  into the app and delivered through a real VIEW intent), starts playback,
  verifies the playback position advances, and uploads screenshots.
- **release** builds the signed APK from the repository's signing secrets,
  verifies the certificate digest, package, version and zero permissions,
  and publishes a pre-release with the APK.

The debug build's fixture screen (`FixtureActivity`) exists only in
`app/src/debug`; release APKs never contain it or its synthetic files.
