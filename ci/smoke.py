"""Synthetic-data smoke test for tengok on an emulator.

Drives the real UI over adb and saves screenshots into ci/shots. Covers:
home, the fixture viewers (MIDI scale, two-track song, corrupt MIDI, text,
hex, painted image), playback with an advancing position, the details sheet,
the SAF picker opening, and a real VIEW intent carrying an independently
generated MIDI file.
"""
import re, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.vanipy.tengok"
OUT = "ci/shots"
subprocess.run(["mkdir", "-p", OUT])


def adb(*a, **k):
    return subprocess.run(["adb", *a], capture_output=True, **k)


def shot(name):
    time.sleep(1.0)
    data = adb("exec-out", "screencap", "-p").stdout
    open(f"{OUT}/{name}.png", "wb").write(data)


def nodes():
    last = ""
    for _ in range(5):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        dump = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        xml = raw.stdout.decode("utf-8", "replace")
        try:
            if dump.returncode or raw.returncode:
                raise ET.ParseError("UI dump command failed")
            return [n.attrib for n in ET.fromstring(xml).iter("node")]
        except ET.ParseError:
            last = repr(xml) + "\n" + dump.stdout.decode("utf-8", "replace") + dump.stderr.decode("utf-8", "replace")
            time.sleep(1)
    open(f"{OUT}/ui-dump-failure.txt", "w").write(last)
    shot("FAILED-ui-dump")
    raise RuntimeError("No valid UIAutomator XML after five attempts; raw diagnostic saved")


def center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text, exact=False):
    for n in nodes():
        t = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).strip().lower()
        if (t == text.lower()) if exact else (text.lower() in t):
            return n
    return None


def present(text, exact=False):
    return find(text, exact) is not None


def tap(text, exact=False, tries=6):
    for _ in range(tries):
        n = find(text, exact)
        if n:
            x, y = center(n["bounds"])
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(0.8)
            return
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "300")
        time.sleep(1)
    shot("FAILED-" + re.sub(r"\W+", "_", text)[:30])
    print("NOT FOUND:", text)
    sys.exit(1)


def need(text, exact=False):
    for i in range(6):
        if present(text, exact):
            return
        if i >= 2:
            adb("shell", "input", "swipe", "540", "700", "540", "1800", "300")
        time.sleep(1)
    shot("FAILED-need-" + re.sub(r"\W+", "_", text)[:30])
    print("EXPECTED ON SCREEN:", text)
    sys.exit(1)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)


def logcat_has(marker):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    return marker in out


adb("shell", "logcat", "-c")
adb("shell", "pm", "clear", PKG)

# ---- home ----
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(2)
need("Open a file")
need("no permissions")
shot("01-home")

# ---- about / honest limits ----
tap("What tengok can open")
need("MIDI music")
need("Not yet, honestly")
need("Share diagnostics")
shot("02-about")
back()
need("Open a file")

# ---- fixture screen ----
adb("shell", "am", "start", "-n", f"{PKG}/.FixtureActivity")
time.sleep(2)
need("MIDI: C major scale")

# ---- MIDI scale: summary, instrument, piano roll, playback ----
tap("MIDI: C major scale")
need("MIDI format 0")
need("Acoustic Grand Piano")
need("8 notes")
shot("03-midi-scale")
need("Plays on this phone")
tap("Play", exact=True)
time.sleep(2.5)
if not logcat_has("TENGOK-MIDI-PLAYING"):
    shot("FAILED-midi-play")
    print("MIDI playback did not start (no TENGOK-MIDI-PLAYING marker)")
    sys.exit(1)
positions = re.findall(r"TENGOK-MIDI-POS ms=(\d+)",
                       adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace"))
positions = [int(p) for p in positions]
if len(positions) < 2 or positions[-1] <= positions[0]:
    shot("FAILED-midi-progress")
    print("MIDI position did not advance:", positions)
    sys.exit(1)
print("MIDI positions:", positions)
shot("04-midi-playing")
back()

# ---- two-track song with a tempo change ----
tap("MIDI: two-track song, tempo change")
need("MIDI format 1")
need("120 bpm then 60 bpm")
need("Violin")
need("Acoustic Bass")
shot("05-midi-song")
back()

# ---- long song: the piano roll follows the playback cursor ----
tap("MIDI: long walk, auto-follow")
need("MIDI format 0")
need("80 notes")
adb("shell", "logcat", "-c")
tap("Play", exact=True)
time.sleep(8)
positions = [int(p) for p in re.findall(r"TENGOK-MIDI-POS ms=(\d+)",
             adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace"))]
print("long-walk positions:", positions)
if len(positions) < 2 or positions[-1] < 6000:
    shot("FAILED-midi-long-progress")
    print("long walk did not play past the first viewport:", positions)
    sys.exit(1)
follows = [(int(a), int(b), int(c)) for a, b, c in re.findall(
    r"TENGOK-MIDI-FOLLOW sx=(\d+) cx=(\d+) vw=(\d+)",
    adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace"))]
print("follow corrections (sx, cx, vw):", follows)
if len(follows) < 2:
    shot("FAILED-midi-follow")
    print("piano roll did not keep scrolling with the cursor:", follows)
    sys.exit(1)
if not all(b[0] > a[0] for a, b in zip(follows, follows[1:])):
    shot("FAILED-midi-follow")
    print("viewport scroll did not track the cursor forward:", follows)
    sys.exit(1)
if not all(0 <= cx - sx <= vw for sx, cx, vw in follows):
    shot("FAILED-midi-follow")
    print("playhead placed outside the viewport:", follows)
    sys.exit(1)
shot("05b-midi-follow")
back()

# ---- corrupt MIDI fails with a readable error, hex offered ----
tap("MIDI: corrupt file")
need("can't read this MIDI file")
need("Show as hex instead")
shot("06-midi-corrupt")
tap("Show as hex instead")
need("hex view")
need("4d 54 68 64")  # the corrupt file really shows its MThd bytes
shot("07-corrupt-hex")
back()

# ---- text ----
tap("Text: hello")
need("UTF-8")
need("quick brown fox")
shot("08-text")
back()

# ---- hex with paging ----
tap("Hex: random blob")
need("hex view")
need("page 1 / 1")
shot("09-hex")
back()

# ---- image ----
tap("Image: painted PNG")
need("480 × 320")
shot("10-image")
back()

# ---- ground implicit "open with" resolution before testing the handler ----
for test_uri in (f"file:///data/data/{PKG}/files/smoke.mid",
                 "file:///sdcard/Download/smoke.mid",
                 "content://media/external/file/1"):
    rr = adb("shell", "cmd", "package", "resolve-activity", "--brief",
             "-a", "android.intent.action.VIEW", "-t", "audio/midi",
             "-d", test_uri)
    print("RESOLVER", test_uri, "->",
          rr.stdout.decode("utf-8", "replace").strip().replace("\n", " | "),
          rr.stderr.decode("utf-8", "replace").strip())

# pm clear at the start wiped the app files dir, so re-deliver the
# independent MIDI now, after the clear (same run-as path as run.sh).
adb("push", "/tmp/smoke.mid", "/data/local/tmp/smoke.mid")
adb("shell", "chmod", "644", "/data/local/tmp/smoke.mid")
adb("shell", "run-as", PKG, "mkdir", "-p", "files")
adb("shell", "run-as", PKG, "cp", "/data/local/tmp/smoke.mid", "files/smoke.mid")

# ---- real implicit VIEW via a content URI, like a file manager would send ----
rr = adb("shell", "am", "start",
         "-a", "android.intent.action.VIEW",
         "-t", "audio/midi",
         "--grant-read-uri-permission",
         "-d", f"content://{PKG}.fixtureprovider/smoke.mid")
print("am start implicit content VIEW:",
      rr.stdout.decode("utf-8", "replace").strip(),
      rr.stderr.decode("utf-8", "replace").strip())
# watch up to 8s: either the file opens directly, or the system chooser
# appears and we pick tengok in it the way a user would. No swipes in this
# loop - a downward swipe would dismiss the bottom-sheet chooser.
opened = False
for _ in range(8):
    time.sleep(1)
    if present("MIDI format 1"):
        opened = True
        break
    n = find("tengok", exact=True)
    if n:
        x, y = center(n["bounds"])
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(2)
        if present("MIDI format 1"):
            opened = True
            break
if not opened:
    shot("FAILED-implicit-view-state")
    print("EXPECTED ON SCREEN: MIDI format 1 (implicit content VIEW route)")
    sys.exit(1)
need("Violin")
need("melody")
shot("11-implicit-content-view")
back()

# ---- details sheet over a real VIEW intent with an independent file ----
r = adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity",
        "-a", "android.intent.action.VIEW",
        "-t", "audio/midi",
        "-d", f"file:///data/data/{PKG}/files/smoke.mid")
print("am start VIEW:", r.stdout.decode("utf-8", "replace").strip(),
      r.stderr.decode("utf-8", "replace").strip())
time.sleep(2.5)
need("MIDI format 1")
need("Violin")
need("melody")
shot("11-view-intent-independent-midi")
tap("Details")
time.sleep(2.5)
need("SHA-256")
need("audio/midi")
shot("12-details")
adb("shell", "input", "keyevent", "4")  # close dialog
time.sleep(1)
back()

# ---- Details must survive a runtime failure on the hash re-read ----
# Phone report: tapping Details killed the app on a big multi-track file.
# The hardening is exercised by forcing a SecurityException on the re-read;
# the dialog must show the failure text and the app must stay alive.
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
# ViewerActivity is not exported; route through MainActivity's VIEW handling,
# which forwards the debug-only failure flag to the viewer
rr = adb("shell", "am", "start",
         "-a", "android.intent.action.VIEW",
         "-n", f"{PKG}/.MainActivity",
         "-t", "audio/midi",
         "--ez", "fail_details_hash", "true",
         "-d", f"content://{PKG}.fixtureprovider/smoke.mid")
print("am start viewer fail_details_hash:", rr.stdout.decode("utf-8", "replace").strip(),
      rr.stderr.decode("utf-8", "replace").strip())
time.sleep(2.5)
need("MIDI format 1")
tap("Details")
time.sleep(2.5)
need("File details")
need("couldn't compute (SecurityException: simulated grant failure)")
# capture before probing the viewer behind the dialog: need()'s swipe
# fallback would dismiss the dialog and the shot would miss it
shot("12b-details-hash-failure")
adb("shell", "input", "keyevent", "4")  # close dialog
time.sleep(1)
need("MIDI format 1")  # app alive after the failure, dialog dismissed
back()
log = adb("shell", "run-as", PKG, "cat", "files/diag.log").stdout.decode("utf-8", "replace")
if "DETAILS-ERR hash: SecurityException: simulated grant failure" not in log:
    print("EXPECTED IN diag.log: DETAILS-ERR hash: SecurityException: simulated grant failure")
    print(log[-2000:])
    sys.exit(1)

# ---- details hash over a content:// grant comes from buffered bytes ----
# Phone trial: the provider refused the hash re-read while MediaPlayer held
# the same Uri (SecurityException). The hash must come from the bytes the
# viewer already loaded, so no second provider read happens.
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
rr = adb("shell", "am", "start",
         "-a", "android.intent.action.VIEW",
         "-n", f"{PKG}/.MainActivity",
         "-t", "audio/midi",
         "-d", f"content://{PKG}.fixtureprovider/smoke.mid")
print("am start viewer content hash:", rr.stdout.decode("utf-8", "replace").strip(),
      rr.stderr.decode("utf-8", "replace").strip())
time.sleep(2.5)
need("MIDI format 1")
tap("Details")
time.sleep(2.5)
need("80c6b8bce952cee0d5bd88")  # pinned hash of the deterministic fixture
shot("12d-details-content-hash")
adb("shell", "input", "keyevent", "4")  # close dialog
time.sleep(1)
back()
log = adb("shell", "run-as", PKG, "cat", "files/diag.log").stdout.decode("utf-8", "replace")
if "DETAILS-OK sha256=80c6b8bce952" not in log:
    print("EXPECTED IN diag.log: DETAILS-OK sha256=80c6b8bce952")
    print(log[-2000:])
    sys.exit(1)

# ---- crash capture: a real uncaught exception leaves a shareable report ----
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
r = adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity",
        "--ez", "debug_crash_test", "true")
print("am start crash test:", r.stdout.decode("utf-8", "replace").strip(),
      r.stderr.decode("utf-8", "replace").strip())
time.sleep(2)
# relaunch: the home screen must offer the shareable crash report
rr = adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(2.5)
need("tengok crashed last time")
need("Share crash details")
need("tengok debug crash test")  # preview carries the exception, sanitized
shot("12c-crash-card")
# the same events must be in the on-device diagnostic log (debug build: run-as)
time.sleep(1)
log = adb("shell", "run-as", PKG, "cat", "files/diag.log").stdout.decode("utf-8", "replace")
for wanted in ("APP-START version", "VIEW kind=midi", "CRASH java.lang.IllegalStateException"):
    if wanted not in log:
        print("EXPECTED IN diag.log:", wanted)
        print(log[-2000:])
        sys.exit(1)
if "/sdcard" in log or "content://" in log:
    print("diag.log leaked a path or URI")
    sys.exit(1)
if "smoke.mid" in log:
    print("diag.log leaked the file's display name")
    sys.exit(1)
tap("Dismiss")
time.sleep(1)
need("Open a file")  # card gone, clean home

# ---- SAF picker opens from home ----
# force-stop for a clean, deterministic task state after the intent chains
adb("shell", "am", "force-stop", PKG)
rr = adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
print("am start home:", rr.stdout.decode("utf-8", "replace").strip(),
      rr.stderr.decode("utf-8", "replace").strip())
home = False
for _ in range(8):
    time.sleep(1)
    if present("Open a file"):
        home = True
        break
if not home:
    shot("FAILED-home-state")
    print("EXPECTED ON SCREEN: Open a file (home after force-stop)")
    sys.exit(1)
tap("Open a file")
time.sleep(2)
shot("13-saf-picker")
back()

# ---- launcher icon ----
adb("shell", "input", "keyevent", "3")
time.sleep(2)
adb("shell", "input", "swipe", "540", "1900", "540", "600", "300")
time.sleep(2)
shot("14-launcher")

print("SMOKE OK")
