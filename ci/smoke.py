"""Synthetic-data smoke test for tengok on an emulator.

Drives the real UI over adb and saves screenshots into ci/shots. Covers:
home, the fixture viewers (MIDI scale, two-track song, corrupt MIDI, text,
hex, painted image), playback with an advancing position, the details sheet,
the SAF picker opening, and a real VIEW intent carrying an independently
generated MIDI file.
"""
import os, re, subprocess, sys, time
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
    # the system SAF picker (DocumentsUI) can keep the accessibility bridge
    # busy well past 5 s while its grid populates; uiautomator dump then
    # fails with no output. Give the bridge a longer, patient window.
    last = ""
    for attempt in range(15):
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
            time.sleep(2)
    open(f"{OUT}/ui-dump-failure.txt", "w").write(last)
    shot("FAILED-ui-dump")
    raise RuntimeError("No valid UIAutomator XML after fifteen attempts; raw diagnostic saved")


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


def wait_present(text, exact=False, tries=8):
    # like need() but never swipes: safe while a system dialog may appear
    for _ in range(tries):
        if present(text, exact):
            return True
        time.sleep(1)
    return False


def wait_picker(filename):
    for _ in range(12):
        if present(filename):
            return
        time.sleep(1)
    shot("FAILED-picker-" + re.sub(r"\W+", "_", filename)[:30])
    print("picker never opened for", filename)
    sys.exit(1)


def dismiss_picker(filename):
    for _ in range(8):
        if not present(filename):
            return
        back()
        time.sleep(1)
    shot("FAILED-picker-stuck-" + re.sub(r"\W+", "_", filename)[:30])
    print("picker would not dismiss for", filename)
    sys.exit(1)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)


def logcat_has(marker):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    return marker in out


# published-APK proof runs the real user path instead of the debug fixtures
if os.environ.get("RELEASE_TAG"):
    _here = os.path.dirname(os.path.abspath(__file__))
    exec(open(os.path.join(_here, "smoke_release.py")).read(), globals())

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

# The game camera is precomputed before play: zoom out instead of jumping
# sideways, and only ever move during a no-press gap. The two-phrase song
# has one real gap (phrase 1 ends ~2.5 s, phrase 2 starts 8 s), so its plan
# is 2 segments, 1 glide; each phrase is narrow, so keys stay at the 44dp
# default and the board stays pannable for the pan-vs-strike proof.
tap("MIDI: two phrases, camera glide")
need("MIDI format 0")

# ---- game mode: falling notes, wait-for-me freeze, tap the right key ----
def keymap_from(out):
    # Key rectangles left of the viewport have negative left/right; the song
    # range is wider than the board, so the leading entries are usually
    # negative and a \d+-only pattern never matches.
    km = {}
    keys = re.findall(r"TENGOK-GAME-KEYS((?: -?\d+:-?\d+,-?\d+,\d+)+)", out)
    if keys:
        for mm in re.finditer(r"(-?\d+):(-?\d+),(-?\d+),(\d+)", keys[-1]):
            km[int(mm.group(1))] = ((int(mm.group(2)) + int(mm.group(3))) // 2, int(mm.group(4)))
    return km

def vxkwd_from(out):
    # screen width, viewport offset, white-key width in dp: freshest geometry line
    m = re.findall(r"\| w=(\d+) h=\d+ loc=\d+,\d+ kb=\d+ lo=\d+ hi=\d+ vx=(-?\d+) kwd=(\d+)", out)
    return (int(m[-1][2]), int(m[-1][1]), int(m[-1][0])) if m else None

def camera_from(out):
    # the precomputed camera plan: (segments, groups, glides, minWhiteDp,
    # framesBelow44, framesBelow32) from the freshest CAMERA line
    m = re.findall(
        r"TENGOK-GAME-CAMERA segments=(\d+) groups=(\d+) glides=(\d+) "
        r"minWhiteDp=(\d+) below44dp=(\d+) below32dp=(\d+)", out)
    return tuple(int(v) for v in m[-1]) if m else None

def pan_vs_strike_proof(pitch, keymap, failshot):
    # Phone report: keys squeezed to a few millimetres. Two proofs in one:
    # a sideways drag on the keys pans the board (vx moves) and NEVER strikes
    # a note (no TAP/STRIKE), then follow resumes and re-centers the note.
    gk = None
    for _ in range(10):
        gk = vxkwd_from(adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace"))
        if gk is not None:
            break
        time.sleep(0.5)  # the app re-emits geometry when the wait engages
    if gk is None or gk[0] < 44:
        shot(failshot)
        print("white key width below the 44dp finger minimum:", gk)
        sys.exit(1)
    x, y = keymap[pitch]
    adb("shell", "logcat", "-c")
    adb("shell", "input", "swipe", "800", str(y), "300", str(y), "300")
    time.sleep(1)
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    if "TENGOK-GAME-TAP" in out or "TENGOK-GAME-STRIKE" in out:
        shot(failshot)
        print("a drag on the keys struck a note")
        sys.exit(1)
    moved = vxkwd_from(out)
    if moved is None or moved[1] == gk[1]:
        shot(failshot)
        print("drag on the keys did not pan the board:", gk, moved)
        sys.exit(1)
    print("white keys", gk[0], "dp; drag panned vx", gk[1], "->", moved[1], "with no strike")
    time.sleep(5)  # outlast the 2.5s manual hold + the resync blend
    km = keymap_from(adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace"))
    if km:
        keymap = km
    x, y = keymap[pitch]
    if not (0 < x < gk[2]):
        shot(failshot)
        print("follow did not bring the waiting key back on screen:", x)
        sys.exit(1)
    return keymap

tap("Game", exact=True)
time.sleep(2)  # let the game view reach its final layout before reading key geometry
ready = None
keymap = {}
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    m = re.search(r"TENGOK-GAME-READY track=(\d+)", out)
    km = keymap_from(out)
    if km:
        keymap = km
    if m:
        ready = m.group(1)
        if keymap:
            break
    time.sleep(0.5)
if ready is None or not keymap:
    shot("FAILED-midi-game")
    print("game never became ready or keyboard geometry missing:", ready, len(keymap))
    sys.exit(1)
print("game ready, track", ready, "keys:", len(keymap))
cam = camera_from(out)
if not cam or cam[:3] != (6, 2, 1) or cam[3] != 44 or cam[4] != 0:
    shot("FAILED-midi-game-camera")
    print("camera plan for the two-phrase song wrong "
          "(want segments=6 (per-note, 50ms gaps stay separate) "
          "groups=2 glides=1 minWhiteDp=44 below44dp=0):", cam)
    sys.exit(1)
print("camera plan: 2 phrases, 1 gap glide, keys at the 44dp default:", cam)
# zero judged notes is zero evidence: the score line must start unranked,
# not award the top letter for nothing
need("perfect 0")
need("rank -")
tap("wait for me: off", exact=True)  # toggles wait mode ON
adb("shell", "logcat", "-c")
tap("play", exact=True)
pitch = None
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    km = keymap_from(out)  # geometry re-logs on change; prefer the freshest
    if km:
        keymap = km
    m = re.search(r"TENGOK-GAME-WAIT pitch=(\d+)", out)
    if m:
        pitch = int(m.group(1))
        break
    time.sleep(0.5)
if pitch is None or pitch not in keymap:
    shot("FAILED-midi-game")
    print("game never waited for the first note, pitch:", pitch)
    sys.exit(1)
keymap = pan_vs_strike_proof(pitch, keymap, "FAILED-midi-game-pan")
# Physical-phone report (0.3.2): taps almost never struck. A real finger
# rolls a little, vertically and sideways; a scrolling parent can steal a
# rolling tap (ACTION_CANCEL) and a sideways wobble was eaten as a pan. The
# scoring tap must therefore arrive through real imperfect input. In wait
# mode the clock is frozen, so gesture length cannot tax the grade.
GESTURES = [
    ("jittery tap (40px vertical roll)",
     lambda x, y: adb("shell", "input", "swipe", str(x), str(y), str(x + 2), str(y + 40), "120")),
    ("wobbly tap (30px horizontal)",
     lambda x, y: adb("shell", "input", "swipe", str(x), str(y), str(x + 30), str(y + 3), "120")),
    ("stationary tap",
     lambda x, y: adb("shell", "input", "tap", str(x), str(y))),
]
# Every gesture must independently strike and score. (A first-hit break once
# let an easy gesture mask a real regression: the vertical roll succeeds even
# on pre-fix code, so the wobble - the gesture that actually distinguishes
# old from new - never ran.)
failed_gestures = []
for gi, (label, gesture) in enumerate(GESTURES):
    if gi > 0:
        # The previous strike advanced the song: wait for the next note's
        # wait state and re-read geometry instead of tapping stale coords.
        # The next freeze can fire fractions of a second after the strike,
        # so clearing logcat first would erase it: instead anchor on the
        # strike itself and take the first WAIT logged after it.
        pitch = None
        for _ in range(40):
            out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
            km = keymap_from(out)
            if km:
                keymap = km
            events = list(re.finditer(r"TENGOK-GAME-(WAIT|STRIKE) pitch=(\d+)", out))
            last_strike = max((i for i, e in enumerate(events)
                               if e.group(1) == "STRIKE"), default=-1)
            after = [e for e in events[last_strike + 1:] if e.group(1) == "WAIT"]
            if after:
                pitch = int(after[0].group(2))
                break
            time.sleep(0.5)
        if pitch is None or pitch not in keymap:
            shot("FAILED-midi-game")
            print("game never waited for another note before", label, "- pitch:", pitch)
            sys.exit(1)
    x, y = keymap[pitch]
    adb("shell", "logcat", "-c")
    gesture(x, y)
    time.sleep(1.2)
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    taps = re.findall(r"TENGOK-GAME-TAP pitch=(\d+)", out)
    if not any(int(p_) == pitch for p_ in taps):
        shot("FAILED-midi-game-tap")
        print(label, "never reached the view as a tap on pitch", pitch,
              "- physical-phone tap regression. taps seen:", taps)
        sys.exit(1)
    strikes = re.findall(r"TENGOK-GAME-STRIKE pitch=(\d+) grade=(\d+)", out)
    if any(int(p_) == pitch and int(g) in (1, 2) for p_, g in strikes):
        print(label, "struck and scored:", strikes)
    else:
        failed_gestures.append(label)
        print(label, "reached the view but never struck:", strikes)
    km = keymap_from(out)  # fresh geometry may have landed after the wait began
    if km:
        keymap = km
    time.sleep(3)  # outlast any pan hold before the next gesture
if failed_gestures:
    shot("FAILED-midi-game")
    print("gestures that failed to score on the waiting key:", failed_gestures)
    sys.exit(1)
time.sleep(3)  # outlast any manual pan hold; follow re-centers the next note
keymap = keymap_from(adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")) or keymap
# Grade split is NOT asserted: Perfect is +/-100 ms, tighter than emulator
# gesture travel time, so taps that a human lands Perfect can grade Good
# here. What the round must show is every strike judged, nothing missed,
# streak alive - parsed from the live score line, not a guessed grade.
score = None
for _ in range(6):
    for n in nodes():
        m = re.match(
            r"perfect (\d+) · good (\d+) · miss (\d+) · streak (\d+) · rank (\S+)",
            n.get("text") or "")
        if m:
            score = m
            break
    if score:
        break
    time.sleep(1)
if (not score or int(score.group(1)) + int(score.group(2)) != 3
        or score.group(3) != "0" or score.group(4) != "3"):
    shot("FAILED-midi-game-score")
    print("EXPECTED score line: 3 judged strikes, 0 miss, streak 3; got:",
          score.group(0) if score else None)
    sys.exit(1)
print("game round judged all three strikes:", score.group(0))
# Gap-glide proof: after the last phrase-1 strike the clock plays through
# the ~5 s rest. The camera must move ONLY inside that gap and land with
# the next phrase's first key on screen (vx settles at the phrase-2 frame).
pitch = None
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    km = keymap_from(out)
    if km:
        keymap = km
    if re.search(r"TENGOK-GAME-WAIT pitch=79\b", out):
        pitch = 79
        break
    time.sleep(0.5)
if pitch != 79 or 79 not in keymap:
    shot("FAILED-midi-game-glide")
    print("game never waited for the phrase-2 entrance (pitch 79):", pitch)
    sys.exit(1)
gk = vxkwd_from(out)
x, y = keymap[79]
if gk is None or gk[1] < 100 or not (0 < x < gk[2]):
    shot("FAILED-midi-game-glide")
    print("camera did not glide to phrase 2 during the gap:",
          "vx:", gk[1] if gk else None, "pitch-79 key x:", x, "screen w:", gk[2] if gk else None)
    sys.exit(1)
print("camera glided inside the gap: vx settled at", gk[1],
      "with the phrase-2 key on screen at x =", x)
shot("05c-midi-game")
tap("exit game", exact=True)
need("MIDI format 0")
back()

# ---- game mode: two fingers strike a chord together ----
# Phone report: simultaneous notes only registered one key - the game view
# tracked a single finger and ignored the rest. The CI emulator image
# accepts no kernel-level multitouch injection (sendevent MT streams on
# all 11 MT-capable devices never reached the view), so this drives the
# same MotionEvent sequence through the app's DEBUG-ONLY dispatch seam
# (GameView.debugInjectChord, triggered by an adb broadcast): both
# pointers down together, the second lifts while the first still holds,
# and BOTH keys must tap, strike and score. This is a synthetic dispatch,
# not real hardware input - it proves the per-pointer runtime path, not
# the touchscreen driver.

tap("MIDI: chord song")
need("MIDI format 0")
tap("Game", exact=True)
time.sleep(2)
ready = None
keymap = {}
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    m = re.search(r"TENGOK-GAME-READY track=(\d+)", out)
    km = keymap_from(out)
    if km:
        keymap = km
    if m:
        ready = m.group(1)
        if keymap:
            break
    time.sleep(0.5)
if ready is None or not keymap:
    shot("FAILED-midi-game-chord")
    print("chord game never became ready:", ready, len(keymap))
    sys.exit(1)
cam = camera_from(out)
if not cam or cam[:3] != (1, 1, 0) or cam[3] != 44:
    shot("FAILED-midi-game-chord")
    print("camera plan for the chord song wrong "
          "(want segments=1 groups=1 glides=0 minWhiteDp=44):", cam)
    sys.exit(1)
print("chord camera plan: one dense segment, no glides:", cam)
tap("wait for me: off", exact=True)  # toggles wait mode ON
adb("shell", "logcat", "-c")
tap("play", exact=True)
pitch = None
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    km = keymap_from(out)
    if km:
        keymap = km
    m = re.search(r"TENGOK-GAME-WAIT pitch=(\d+)", out)
    if m:
        pitch = int(m.group(1))
        break
    time.sleep(0.5)
if pitch != 36 or 36 not in keymap or 40 not in keymap:
    shot("FAILED-midi-game-chord")
    print("chord setup missing: wait pitch", pitch, "keymap 36/40",
          36 in keymap, 40 in keymap)
    sys.exit(1)
adb("shell", "logcat", "-c")
adb("shell", "am", "broadcast", "-a", "com.vanipy.tengok.debug.CHORD",
    "--ei", "a", "36", "--ei", "b", "40", "-p", "com.vanipy.tengok")
time.sleep(1.2)  # both struck by grace; second finger up; first still holding
shot("16-chord-held")
time.sleep(1.5)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
inject = re.findall(r"TENGOK-GAME-CHORD-INJECT a=(\d+) b=(\d+)", out)
taps = re.findall(r"TENGOK-GAME-TAP pitch=(\d+)", out)
strikes = re.findall(r"TENGOK-GAME-STRIKE pitch=(\d+) grade=(\d+)", out)
if not ("36", "40") in inject:
    shot("FAILED-midi-game-chord")
    print("chord dispatch seam never injected (debug receiver missing?):", inject)
    sys.exit(1)
if not ("36" in taps and "40" in taps):
    shot("FAILED-midi-game-chord")
    print("chord did not reach the view as TWO key taps:", taps)
    sys.exit(1)
if not (any(p_ == "36" and g in ("1", "2") for p_, g in strikes)
        and any(p_ == "40" and g in ("1", "2") for p_, g in strikes)):
    shot("FAILED-midi-game-chord")
    print("chord keys did not both score:", strikes)
    sys.exit(1)
print("chord: two-pointer dispatch seam (synthetic, not real hardware), "
      "one finger released early, both keys scored:", strikes)
# the round still completes: the single note after the chord scores too
pitch = None
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    km = keymap_from(out)
    if km:
        keymap = km
    events = list(re.finditer(r"TENGOK-GAME-(WAIT|STRIKE) pitch=(\d+)", out))
    last_strike = max((i for i, e in enumerate(events)
                       if e.group(1) == "STRIKE"), default=-1)
    after = [e for e in events[last_strike + 1:] if e.group(1) == "WAIT"]
    if after:
        pitch = int(after[0].group(2))
        break
    time.sleep(0.5)
if pitch != 38 or 38 not in keymap:
    shot("FAILED-midi-game-chord")
    print("game never waited for the note after the chord:", pitch)
    sys.exit(1)
x, y = keymap[38]
adb("shell", "input", "tap", str(x), str(y))
time.sleep(1.5)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
strikes = re.findall(r"TENGOK-GAME-STRIKE pitch=(\d+) grade=(\d+)", out)
if not any(p_ == "38" and g in ("1", "2") for p_, g in strikes):
    shot("FAILED-midi-game-chord")
    print("the note after the chord did not score:", strikes)
    sys.exit(1)
score = None
for _ in range(6):
    for n in nodes():
        m = re.match(
            r"perfect (\d+) · good (\d+) · miss (\d+) · streak (\d+) · rank (\S+)",
            n.get("text") or "")
        if m:
            score = m
            break
    if score:
        break
    time.sleep(1)
if (not score or int(score.group(1)) + int(score.group(2)) != 3
        or score.group(3) != "0" or score.group(4) != "3"):
    shot("FAILED-midi-game-chord-score")
    print("chord round must judge all 3 notes, 0 miss, streak 3; got:",
          score.group(0) if score else None)
    sys.exit(1)
print("chord round judged all three notes:", score.group(0))
shot("16b-chord-done")
tap("exit game", exact=True)
need("MIDI format 0")
back()

# ---- game on a late-entrance part: empty-lane hint, tap-to-skip ----
# The pad enters at 12 s, plays at 12.5 s, then rests until 29 s. Before this
# fix the lane stayed blank through those stretches with no explanation - the
# exact "empty game lane" report from a phone on a late-entering pad. The lane
# must now announce the wait and offer a jump; the intro itself is never
# skipped silently (the player taps to jump, the music stays intact).
def game_geometry(out):
    m = re.findall(r"\| w=(\d+) h=(\d+) loc=(\d+),(\d+) kb=(\d+)", out)
    if not m:
        return None
    w, h, lx, ly, kb = map(int, m[-1])
    return w, h, lx, ly, kb

def strike_until_scored(pitch, keymap):
    for attempt in range(2):
        x, y = keymap[pitch]
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.2)
        out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
        strikes = re.findall(r"TENGOK-GAME-STRIKE pitch=(\d+) grade=(\d+)", out)
        if any(int(p_) == pitch and int(g) in (1, 2) for p_, g in strikes):
            return out, strikes
        km = keymap_from(out)
        if km:
            keymap = km
    shot("FAILED-midi-game-late")
    print("tap on the waiting key did not score: pitch", pitch, "strikes:", strikes)
    sys.exit(1)

adb("shell", "logcat", "-c")
tap("MIDI: late entrance and gap")
need("MIDI format 1")
tap("Game", exact=True)
time.sleep(1)
tap("pad (3 notes)")  # the part picker: bass plays from bar one, pad enters late
time.sleep(2)
ready = None
keymap = {}
hint = None
for _ in range(40):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    m = re.search(r"TENGOK-GAME-READY track=(\d+)", out)
    km = keymap_from(out)
    if km:
        keymap = km
    s = re.search(r"TENGOK-GAME-HINT nextSongMs=(\d+)", out)
    if s:
        hint = int(s.group(1))
    if m:
        ready = m.group(1)
        if keymap and hint:
            break
    time.sleep(0.5)
if ready is None or not keymap:
    shot("FAILED-midi-game-late")
    print("late-entrance game never became ready:", ready, len(keymap))
    sys.exit(1)
if hint is None or not (10000 <= hint <= 14000):
    shot("FAILED-midi-game-late")
    print("no empty-lane hint for the 12 s entrance:", hint)
    sys.exit(1)
notes_line = re.search(r"TENGOK-GAME-NOTES track=\d+ count=(\d+) firstMs=(\d+) lastMs=(\d+) badTimes=(\d+)", out)
if not notes_line or notes_line.group(1) != "3" or notes_line.group(4) != "0":
    shot("FAILED-midi-game-late")
    print("game notes diagnostic missing or wrong:", notes_line)
    sys.exit(1)
print("empty-lane hint announced the 12 s entrance:", hint, "notes:", notes_line.groups())
cam = camera_from(out)
if not cam or cam[:3] != (2, 2, 1) or cam[3] != 44:
    shot("FAILED-midi-game-late")
    print("camera plan for the late-entrance pad wrong "
          "(want segments=2 groups=2 glides=1 minWhiteDp=44):", cam)
    sys.exit(1)
print("late-entrance camera plan: 2 segments, 1 glide for the 16 s rest:", cam)
need("song timeline")  # the horizontal timeline, like normal playback
need("/ 0:32")
shot("05d-midi-game-hint")  # the hint text is canvas-drawn; this proves the pixels
g = game_geometry(out)
if g:
    w, h, lx, ly, kb = g
    adb("shell", "input", "tap", str(lx + w // 2), str(ly + int(h * 0.4)))
else:
    adb("shell", "input", "tap", "540", "800")
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
skip = re.search(r"TENGOK-GAME-SKIP mediaMs=(\d+)", out)
if not skip or not (8000 <= int(skip.group(1)) <= 12000):
    shot("FAILED-midi-game-late")
    print("lane tap did not skip to the runway of the 12 s note:", skip)
    sys.exit(1)
print("tap-to-skip landed:", skip.group(1))
tap("wait for me: off", exact=True)  # toggles wait mode ON
adb("shell", "logcat", "-c")
tap("play", exact=True)
pitch = None
for _ in range(30):  # 12 s entrance on a 10 s runway: the wait must come in ~2 s, not 12 s
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    km = keymap_from(out)
    if km:
        keymap = km
    m = re.search(r"TENGOK-GAME-WAIT pitch=(\d+)", out)
    if m:
        pitch = int(m.group(1))
        break
    time.sleep(0.5)
if pitch != 60:
    shot("FAILED-midi-game-late")
    print("game did not wait for the 12 s entrance quickly, pitch:", pitch)
    sys.exit(1)
out, _ = strike_until_scored(60, keymap)
km = keymap_from(out)
if km:
    keymap = km
pitch = None
for _ in range(20):  # second pad note at 12.5 s
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    m = re.findall(r"TENGOK-GAME-WAIT pitch=(\d+)", out)
    if len(m) >= 2:
        pitch = int(m[-1])
        break
    time.sleep(0.5)
if pitch != 64:
    shot("FAILED-midi-game-late")
    print("game did not wait for the 12.5 s note, pitch:", pitch)
    sys.exit(1)
out, _ = strike_until_scored(64, keymap)
km = keymap_from(out)
if km:
    keymap = km
# now the 16 s rest: the lane must announce the gap instead of staring blank
hint = None
for _ in range(20):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    m = re.search(r"TENGOK-GAME-HINT nextSongMs=(\d+)", out)
    if m:
        hint = int(m.group(1))
        break
    time.sleep(0.5)
if hint is None or not (27000 <= hint <= 31000):
    shot("FAILED-midi-game-late")
    print("no empty-lane hint for the 29 s note:", hint)
    sys.exit(1)
print("gap hint announced for the 29 s note:", hint)
shot("05e-midi-game-gap")  # the hint text is canvas-drawn; this proves the pixels
g = game_geometry(out)
if g:
    w, h, lx, ly, kb = g
    adb("shell", "input", "tap", str(lx + w // 2), str(ly + int(h * 0.4)))
else:
    adb("shell", "input", "tap", "540", "800")
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
skip = re.search(r"TENGOK-GAME-SKIP mediaMs=(\d+)", out)
if not skip or not (25000 <= int(skip.group(1)) <= 29000):
    shot("FAILED-midi-game-late")
    print("lane tap did not skip to the runway of the 29 s note:", skip)
    sys.exit(1)
print("tap-to-skip landed:", skip.group(1))
pitch = None
for _ in range(20):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    km = keymap_from(out)
    if km:
        keymap = km
    m = re.findall(r"TENGOK-GAME-WAIT pitch=(\d+)", out)
    if len(m) >= 3:
        pitch = int(m[-1])
        break
    time.sleep(0.5)
if pitch != 67:
    shot("FAILED-midi-game-late")
    print("game did not wait for the 29 s note after the skip, pitch:", pitch)
    sys.exit(1)
strike_until_scored(67, keymap)
need("done!")
need("miss 0")  # the skips landed on their runways without costing a note
need("accuracy 100%")
need("rank SSS")  # final grade: accuracy plus the best streak the round held
shot("05f-midi-game-done")
# a done round restarts deliberately on play
adb("shell", "logcat", "-c")
tap("play", exact=True)
restarted = False
for _ in range(20):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    if re.search(r"TENGOK-GAME-READY", out):
        restarted = True
        break
    time.sleep(0.5)
if not restarted:
    shot("FAILED-midi-game-late")
    print("play after done did not restart the round")
    sys.exit(1)
need("perfect 0")
print("round restarted on play after done")
tap("exit game", exact=True)
need("MIDI format 1")

# the timeline scrubs the round: drag it into the song in a fresh round
tap("Game", exact=True)
time.sleep(1)
tap("pad (3 notes)")
time.sleep(2)
for _ in range(20):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    if re.search(r"TENGOK-GAME-READY", out):
        break
    time.sleep(0.5)
n = find("song timeline")
if not n:
    shot("FAILED-midi-game-late")
    print("song timeline not on screen in the fresh round")
    sys.exit(1)
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n["bounds"])
x1, y1, x2, y2 = map(int, m.groups())
# start well inside the bar: a drag from the screen's left edge is stolen
# by gesture navigation as back (observed: game exits to the fixture list)
sx = x1 + int((x2 - x1) * 0.3)
ex = x1 + int((x2 - x1) * 0.5)
adb("shell", "input", "swipe", str(sx), str((y1 + y2) // 2),
    str(ex), str((y1 + y2) // 2), "400")
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
scrub = re.findall(r"TENGOK-GAME-SCRUB mediaMs=(\d+)", out)
if not scrub or not (5000 <= int(scrub[-1]) <= 20000):
    shot("FAILED-midi-game-late")
    print("timeline drag did not scrub into the song:", scrub)
    sys.exit(1)
print("timeline scrubbed to:", scrub[-1])
need("/ 0:32")  # the clock follows the scrubbed position
shot("05g-midi-game-scrub")
tap("exit game", exact=True)
need("MIDI format 1")
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
if "DETAILS-ERR op=hash ex=java.lang.SecurityException" not in log:
    print("EXPECTED IN diag.log: DETAILS-ERR op=hash ex=java.lang.SecurityException")
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
if "DETAILS-OK" not in log:
    print("EXPECTED IN diag.log: DETAILS-OK")
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
need("tengok debug crash test")  # on-screen preview of the local trace; share payload is filtered
shot("12c-crash-card")
# the same events must be in the on-device diagnostic log (debug build: run-as)
time.sleep(1)
log = adb("shell", "run-as", PKG, "cat", "files/diag.log").stdout.decode("utf-8", "replace")
for wanted in ("APP-START version", "VIEW kind=midi", "CRASH java.lang.RuntimeException"):
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

# ---- folder explorer: pick a real folder through the system picker ----
adb("shell", "mkdir", "-p", "/sdcard/Download/tengok-fixtures")
adb("push", "/tmp/smoke.mid", "/sdcard/Download/tengok-fixtures/smoke.mid")
time.sleep(1)
tap("Browse a folder")
time.sleep(3)
need("Download")
tap("Download")
time.sleep(2)
need("tengok-fixtures")
tap("tengok-fixtures")
time.sleep(2)
shot("13b-tree-picker")
tap("Use this folder", exact=True)
if not wait_present("Allow", exact=True):
    shot("FAILED-tree-confirm")
    print("EXPECTED ON SCREEN: system allow dialog after USE THIS FOLDER")
    sys.exit(1)
tap("Allow", exact=True)
time.sleep(2.5)
need("read-only")  # tengok's own footer: proves we left the picker
need("smoke.mid")
need("tengok-fixtures")  # title bar shows the real folder name
shot("13c-explorer-list")
tap("smoke.mid")
time.sleep(2.5)
need("MIDI format 1")
shot("13d-explorer-open")
back()
time.sleep(1)
# persisted grant: after a force-stop, Browse a folder goes straight in
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(2.5)
tap("Browse a folder")
time.sleep(2.5)
need("smoke.mid")
shot("13e-explorer-persisted")
back()
time.sleep(1)

# ---- recent files: reopen one tap away; removal clears only the list ----
need("Recent files")
need("smoke.mid")
shot("13f-recents")
tap("smoke.mid")  # newest entry: the file just opened through the explorer
time.sleep(2.5)
need("MIDI format 1")
shot("13g-recent-reopen")
back()
time.sleep(1)
need("Recent files")
for _ in range(6):  # remove every entry until the section folds away
    if not present("Recent files"):
        break
    tap("Remove", exact=True)
    time.sleep(1)
    need("Remove from recent files?")
    tap("Remove from list", exact=True)
    time.sleep(1)
if present("Recent files"):
    shot("FAILED-recents-remove")
    print("recent files section still visible after removing every entry")
    sys.exit(1)
shot("13h-recents-cleared")
# removing the entries must not take the folder's access with them:
tap("Browse a folder")
time.sleep(2.5)
need("smoke.mid")
shot("13i-explorer-grant-intact")
back()
time.sleep(1)

# ---- a lost read grant must fail safe, never crash ----
# If Android's per-app persisted-grant budget was full when a file was first
# opened, nothing persisted; reopening that entry later must show the
# access-lost card with a re-pick instruction, keep the app alive, and NOT
# offer the hex fallback (every read of that Uri would fail the same way).
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
rr = adb("shell", "am", "start",
         "-a", "android.intent.action.VIEW",
         "-n", f"{PKG}/.MainActivity",
         "-t", "audio/midi",
         "--ez", "fail_open_grant", "true",
         "-d", f"content://{PKG}.fixtureprovider/smoke.mid")
print("am start viewer fail_open_grant:", rr.stdout.decode("utf-8", "replace").strip(),
      rr.stderr.decode("utf-8", "replace").strip())
time.sleep(2.5)
need("tengok no longer has access to this file")
need("Pick the file again from the home screen")
if present("Show as hex instead"):
    shot("FAILED-access-lost-hex")
    print("hex fallback offered on a lost grant; it would fail the same way")
    sys.exit(1)
shot("13j-access-lost")
# the VIEW flow finishes MainActivity behind the viewer, so back lands on
# the launcher, not tengok's home: start home explicitly to prove the app
# is alive and renders fine after the failure
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(2)
need("Open a file")
log = adb("shell", "run-as", PKG, "cat", "files/diag.log").stdout.decode("utf-8", "replace")
if "ERR view op=open ex=java.lang.SecurityException" not in log:
    print("EXPECTED IN diag.log: ERR view op=open ex=java.lang.SecurityException")
    print(log[-2000:])
    sys.exit(1)

# ---- offline conversion through the real SAF picker ----
# Proves the whole loop: viewer -> Convert card -> real DocumentsUI save ->
# bytes read back over adb -> original untouched. Covers success with
# readback (image + text), picker cancel, and an undecodable input.
def sdcard_path(pattern):
    # /sdcard is a symlink chain to the FUSE mount; -L follows it on every
    # toybox/GNU find variant, and /storage/emulated/0 covers images where
    # /sdcard itself resolves oddly. Stderr is captured for diagnosis.
    rr = adb("shell", "find", "-L", "/sdcard", "/storage/emulated/0", "-name", pattern)
    out = rr.stdout.decode("utf-8", "replace")
    err = rr.stderr.decode("utf-8", "replace")
    found = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("/sdcard/") or line.startswith("/storage/"):
            found = line
            break
    if found is None:
        print("sdcard_path diagnostics for", pattern)
        print("find stdout:", out[:800])
        print("find stderr:", err[:800])
        print("Documents:", adb("shell", "ls", "-la", "/sdcard/Documents/").stdout.decode("utf-8", "replace")[:800])
        print("Download:", adb("shell", "ls", "-la", "/sdcard/Download/").stdout.decode("utf-8", "replace")[:800])
    return found


def fixture_hash(name):
    return adb("shell", "run-as", PKG, "sha256sum", f"cache/{name}").stdout.decode().split()[0]


adb("shell", "am", "start", "-n", f"{PKG}/.FixtureActivity")
time.sleep(2)

# image: PNG -> JPEG, bytes verified after the real picker save
tap("Image: painted PNG")
time.sleep(2)
need("480 × 320")
before = fixture_hash("painted.png")
tap("Save as JPEG image")
wait_picker("painted.jpg")  # DocumentsUI inflate time varies wildly under load
tap("Save", exact=True)
need("Saved painted.jpg.")
path = sdcard_path("painted*.jpg")
if not path:
    shot("FAILED-convert-jpeg")
    print("converted JPEG not found under /sdcard")
    sys.exit(1)
data = adb("exec-out", "cat", path).stdout
if not data.startswith(b"\xff\xd8\xff"):
    shot("FAILED-convert-jpeg")
    print("readback is not JPEG data:", data[:16])
    sys.exit(1)
print(f"convert readback: {len(data)} JPEG bytes at {path}")
if fixture_hash("painted.png") != before:
    print("FAIL: original file changed during conversion")
    sys.exit(1)

# picker cancel: no save, no file, no false status
tap("Save as WebP image")
wait_picker("painted.webp")
back()
dismiss_picker("painted.webp")
if wait_present("Saved painted.webp", tries=3):
    shot("FAILED-convert-cancel")
    print("cancel path reported a save")
    sys.exit(1)
if sdcard_path("painted*.webp"):
    shot("FAILED-convert-cancel")
    print("cancel left a file behind")
    sys.exit(1)
print("convert cancel: no file, no saved status")
back()
time.sleep(1)

# corrupt image: error surfaces, no silent failure
tap("Image: corrupt PNG")
time.sleep(2)
need("can't show this image")
tap("Save as PNG image")
wait_picker("corrupt.png")
tap("Save", exact=True)
need("Could not convert")
print("convert error path: failure surfaced as status text")
back()
time.sleep(1)

# text: latin-1 -> UTF-8, re-encoded bytes verified
tap("Text: latin-1 cafe")
time.sleep(2)
tap("Save as UTF-8 text")
wait_picker("latin1.txt")
tap("Save", exact=True)
need("Saved latin1.txt.")
path = sdcard_path("latin1*.txt")
if not path:
    shot("FAILED-convert-text")
    print("converted text file not found under /sdcard")
    sys.exit(1)
data = adb("exec-out", "cat", path).stdout
if b"caf\xc3\xa9" not in data or b"sm\xc3\xb8rrebr\xc3\xb8d" not in data:
    shot("FAILED-convert-text")
    print("readback is not the UTF-8 re-encode:", data[:40])
    sys.exit(1)
print(f"convert readback: {len(data)} UTF-8 bytes at {path}")
back()
time.sleep(1)
shot("15-convert")

# ---- launcher icon ----
adb("shell", "input", "keyevent", "3")
time.sleep(2)
adb("shell", "input", "swipe", "540", "1900", "540", "600", "300")
time.sleep(2)
shot("14-launcher")

print("SMOKE OK")
