# Published-APK proof for `workflow_dispatch` with release_tag.
#
# exec'd by ci/smoke.py (shares its globals: adb, tap, need, wait_present,
# shot, PKG, OUT) when RELEASE_TAG is set. Release bytes carry no debug
# fixture screen, so the identical late-entrance fixture arrives as a real
# user file in /sdcard/Download/tengok-fixtures and is opened through the
# real folder explorer - the actual user path. The game assertions mirror
# the debug smoke's late-entrance block.


import glob


def keymap_from(out):
    # signed coordinates: keys left of the panned viewport log negative
    # rectangles; an unsigned pattern never matches (see smoke.py)
    km = {}
    keys = re.findall(r"TENGOK-GAME-KEYS((?: -?\d+:-?\d+,-?\d+,\d+)+)", out)
    if keys:
        for mm in re.finditer(r"(-?\d+):(-?\d+),(-?\d+),(\d+)", keys[-1]):
            km[int(mm.group(1))] = ((int(mm.group(2)) + int(mm.group(3))) // 2, int(mm.group(4)))
    return km


def game_geometry(out):
    m = re.findall(r"\| w=(\d+) h=(\d+) loc=(\d+),(\d+) kb=(\d+)", out)
    if not m:
        return None
    w, h, lx, ly, kb = map(int, m[-1])
    return w, h, lx, ly, kb


def vxkwd_from(out):
    # screen width, viewport offset, white-key width in dp: freshest geometry line
    m = re.findall(r"\| w=(\d+) h=\d+ loc=\d+,\d+ kb=\d+ lo=\d+ hi=\d+ vx=(-?\d+) kwd=(\d+)", out)
    return (int(m[-1][2]), int(m[-1][1]), int(m[-1][0])) if m else None

def camera_from(out):
    m = re.findall(
        r"TENGOK-GAME-CAMERA segments=(\d+) groups=(\d+) glides=(\d+) "
        r"minWhiteDp=(\d+) below44dp=(\d+) below32dp=(\d+)", out)
    return tuple(int(v) for v in m[-1]) if m else None


def strike_until_scored(pitch, keymap):
    for _ in range(2):
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
    shot("FAILED-rel-game")
    print("no scored strike for pitch", pitch)
    sys.exit(1)


def lane_tap(out):
    g = game_geometry(out)
    if g:
        w, h, lx, ly, kb = g
        adb("shell", "input", "tap", str(lx + w // 2), str(ly + int(h * 0.4)))
    else:
        adb("shell", "input", "tap", "540", "800")


adb("shell", "logcat", "-c")

if os.environ.get("UPGRADE_PROOF"):
    # The previous release is installed and holds no state yet. Create the
    # one piece of user state tengok keeps - a remembered folder with its
    # persisted tree grant - then install the candidate over it in place and
    # prove both survive: same signature, preferences and grants intact.
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)
    need("Open a file")
    tap("Browse a folder")
    time.sleep(3)
    need("Download")
    tap("Download")
    time.sleep(2)
    need("tengok-fixtures")
    tap("tengok-fixtures")
    time.sleep(2)
    tap("Use this folder", exact=True)
    if not wait_present("Allow", exact=True):
        shot("FAILED-rel-upgrade-tree")
        print("EXPECTED ON SCREEN: system allow dialog after USE THIS FOLDER")
        sys.exit(1)
    tap("Allow", exact=True)
    time.sleep(2.5)
    need("latepad.mid")  # browsing the folder on the previous release
    shot("rel-00a-upgrade-before")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    cand = sorted(glob.glob("/tmp/rel/tengok-v*.apk"))
    if not cand:
        print("candidate APK missing under /tmp/rel")
        sys.exit(1)
    rr = adb("install", "-r", cand[0])
    print("upgrade install:", rr.stdout.decode("utf-8", "replace").strip(),
          rr.stderr.decode("utf-8", "replace").strip())
    if rr.returncode or "Success" not in rr.stdout.decode("utf-8", "replace"):
        shot("FAILED-rel-upgrade-install")
        print("in-place upgrade install failed - signature or version regression")
        sys.exit(1)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.5)
    tap("Browse a folder")
    time.sleep(2.5)
    # with state preserved there is no picker: the folder opens straight away
    need("latepad.mid")
    need("read-only")
    shot("rel-00b-upgrade-preserved")
    print("upgrade proof: remembered folder and its grant survived the in-place upgrade")

# the remaining proofs want a fresh install state (the clear is a no-op
# right after the upgrade proof's own clear)
adb("shell", "pm", "clear", PKG)
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(2)
need("Open a file")
shot("rel-01-home")

# pick the fixture folder through the real SAF picker, like a user
tap("Browse a folder")
time.sleep(3)
need("Download")
tap("Download")
time.sleep(2)
need("tengok-fixtures")
tap("tengok-fixtures")
time.sleep(2)
tap("Use this folder", exact=True)
if not wait_present("Allow", exact=True):
    shot("FAILED-rel-tree-confirm")
    print("EXPECTED ON SCREEN: system allow dialog after USE THIS FOLDER")
    sys.exit(1)
tap("Allow", exact=True)
time.sleep(2.5)
need("read-only")  # tengok's own footer: proves we left the picker
need("latepad.mid")
shot("rel-02-explorer")

tap("latepad.mid")
time.sleep(2)
need("MIDI format 1")
adb("shell", "logcat", "-c")
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
    shot("FAILED-rel-game")
    print("late-entrance game never became ready:", ready, len(keymap))
    sys.exit(1)
if hint is None or not (10000 <= hint <= 14000):
    shot("FAILED-rel-game")
    print("no empty-lane hint for the 12 s entrance:", hint)
    sys.exit(1)
notes_line = re.search(r"TENGOK-GAME-NOTES track=\d+ count=(\d+) firstMs=(\d+) lastMs=(\d+) badTimes=(\d+)", out)
if not notes_line or notes_line.group(1) != "3" or notes_line.group(4) != "0":
    shot("FAILED-rel-game")
    print("game notes diagnostic missing or wrong:", notes_line)
    sys.exit(1)
print("published APK: empty-lane hint announced the 12 s entrance:", hint, "notes:", notes_line.groups())
cam = camera_from(out)
if not cam or cam[:3] != (2, 2, 1) or cam[3] != 44:
    shot("FAILED-rel-game")
    print("published APK: camera plan for the late-entrance pad wrong "
          "(want segments=2 groups=2 glides=1 minWhiteDp=44):", cam)
    sys.exit(1)
print("published APK: camera plan 2 segments, 1 glide for the 16 s rest:", cam)
need("song timeline")
need("/ 0:32")
shot("rel-05d-game-hint")
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
lane_tap(out)
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
skip = re.search(r"TENGOK-GAME-SKIP mediaMs=(\d+)", out)
if not skip or not (8000 <= int(skip.group(1)) <= 12000):
    shot("FAILED-rel-game")
    print("lane tap did not skip to the runway of the 12 s note:", skip)
    sys.exit(1)
print("tap-to-skip landed:", skip.group(1))
tap("wait for me: off", exact=True)  # toggles wait mode ON
adb("shell", "logcat", "-c")
tap("play", exact=True)
pitch = None
for _ in range(30):
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
    shot("FAILED-rel-game")
    print("game did not wait for the 12 s entrance quickly, pitch:", pitch)
    sys.exit(1)
gk = None
for _ in range(10):
    gk = vxkwd_from(adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace"))
    if gk is not None:
        break
    time.sleep(0.5)  # the app re-emits geometry when the wait engages
if gk is None or gk[0] < 44:
    shot("FAILED-rel-game-pan")
    print("published APK: white key width below the 44dp finger minimum:", gk)
    sys.exit(1)
# a sideways drag on the keys pans the board and never strikes a note
x, y = keymap[pitch]
adb("shell", "logcat", "-c")
adb("shell", "input", "swipe", "800", str(y), "300", str(y), "300")
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
if "TENGOK-GAME-TAP" in out or "TENGOK-GAME-STRIKE" in out:
    shot("FAILED-rel-game-pan")
    print("published APK: a drag on the keys struck a note")
    sys.exit(1)
moved = vxkwd_from(out)
if moved is None or moved[1] == gk[1]:
    shot("FAILED-rel-game-pan")
    print("published APK: drag on the keys did not pan the board:", gk, moved)
    sys.exit(1)
print("published APK: white keys", gk[0], "dp; drag panned vx", gk[1], "->", moved[1], "no strike")
time.sleep(3.5)  # outlast the manual hold; follow re-centers the waiting note
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
km = keymap_from(out)
if km:
    keymap = km
x, y = keymap[pitch]
if not (0 < x < gk[2]):
    shot("FAILED-rel-game-pan")
    print("published APK: follow did not bring the waiting key back on screen:", x)
    sys.exit(1)
out, _ = strike_until_scored(60, keymap)
km = keymap_from(out)
if km:
    keymap = km
pitch = None
for _ in range(20):  # second pad note at 12.5 s
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    # the pan proof cleared the log (drag-strike isolation), so the 12.0 s
    # wait line is gone: the FIRST wait visible here is the 12.5 s note
    m = re.findall(r"TENGOK-GAME-WAIT pitch=(\d+)", out)
    if len(m) >= 1:
        pitch = int(m[-1])
        break
    time.sleep(0.5)
if pitch != 64:
    shot("FAILED-rel-game")
    print("game did not wait for the 12.5 s note, pitch:", pitch)
    sys.exit(1)
out, _ = strike_until_scored(64, keymap)
km = keymap_from(out)
if km:
    keymap = km
hint = None
for _ in range(20):
    out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
    m = re.search(r"TENGOK-GAME-HINT nextSongMs=(\d+)", out)
    if m:
        hint = int(m.group(1))
        break
    time.sleep(0.5)
if hint is None or not (27000 <= hint <= 31000):
    shot("FAILED-rel-game")
    print("no empty-lane hint for the 29 s note:", hint)
    sys.exit(1)
print("gap hint announced for the 29 s note:", hint)
shot("rel-05e-game-gap")
lane_tap(out)
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
skip = re.search(r"TENGOK-GAME-SKIP mediaMs=(\d+)", out)
if not skip or not (25000 <= int(skip.group(1)) <= 29000):
    shot("FAILED-rel-game")
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
    if len(m) >= 2:  # post-clear buffer: 12.5 s wait plus this 29 s one
        pitch = int(m[-1])
        break
    time.sleep(0.5)
if pitch != 67:
    shot("FAILED-rel-game")
    print("game did not wait for the 29 s note after the skip, pitch:", pitch)
    sys.exit(1)
strike_until_scored(67, keymap)
need("done!")
need("miss 0")  # the skips landed on their runways without costing a note
need("accuracy 100%")
need("rank SSS")  # final grade: accuracy plus the best streak the round held
shot("rel-05f-game-done")

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
    shot("FAILED-rel-game")
    print("play after done did not restart the round")
    sys.exit(1)
need("perfect 0")
print("round restarted on play after done")

# the timeline scrubs the round: drag it into the song in a fresh round
n = find("song timeline")
if not n:
    shot("FAILED-rel-game")
    print("song timeline not on screen in the fresh round")
    sys.exit(1)
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n["bounds"])
x1, y1, x2, y2 = map(int, m.groups())
# start well inside the bar: a drag from the screen's left edge is stolen
# by gesture navigation as back
sx = x1 + int((x2 - x1) * 0.3)
ex = x1 + int((x2 - x1) * 0.5)
adb("shell", "input", "swipe", str(sx), str((y1 + y2) // 2),
    str(ex), str((y1 + y2) // 2), "400")
time.sleep(1)
out = adb("logcat", "-d", "-s", "tengok:I").stdout.decode("utf-8", "replace")
scrub = re.findall(r"TENGOK-GAME-SCRUB mediaMs=(\d+)", out)
if not scrub or not (5000 <= int(scrub[-1]) <= 20000):
    shot("FAILED-rel-game")
    print("timeline drag did not scrub into the song:", scrub)
    sys.exit(1)
print("timeline scrubbed to:", scrub[-1])
need("/ 0:32")  # the clock follows the scrubbed position
shot("rel-05g-game-scrub")

# ---- recent files on the published APK: reopen, remove, folder access kept ----
tap("exit game", exact=True)
need("MIDI format 1")
back()  # viewer -> explorer
time.sleep(1)
back()  # explorer -> home
time.sleep(1)
need("Recent files")
need("latepad.mid")
shot("rel-06-recents")
tap("latepad.mid")
time.sleep(2.5)
need("MIDI format 1")
shot("rel-07-recent-reopen")
back()
time.sleep(1)
for _ in range(4):  # remove every entry until the section folds away
    if not present("Recent files"):
        break
    tap("Remove", exact=True)
    time.sleep(1)
    need("Remove from recent files?")
    tap("Remove from list", exact=True)
    time.sleep(1)
if present("Recent files"):
    shot("FAILED-rel-recents")
    print("published APK: recent files section still visible after removing every entry")
    sys.exit(1)
shot("rel-08-recents-cleared")
# removing the entry must not take the folder's access with it:
tap("Browse a folder")
time.sleep(2.5)
need("latepad.mid")
shot("rel-09-explorer-grant-intact")
back()
print("published-APK game proof complete")
sys.exit(0)
