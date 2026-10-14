#!/usr/bin/env python3
"""CI helper: drives Melodia on an Android emulator like a person would.

First launch, the welcome dialog, PC engine setup (the host's acestep.cpp server
at 10.0.2.2:8085), a real 30-second song with typed lyrics, waiting in the
Library, playback, the full player, Save to Music, and a look at the other
screens. Screenshots, UI dumps, logcat and a summary land in $OUT.
Exits non-zero if the app crashes or the song is not made.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.melodia.app"
OUT = os.environ.get("OUT", "ui-out")
APK = os.environ.get("APK", "Melodia.apk")
PC_URL = os.environ.get("PC_URL", "10.0.2.2:8085")
GEN_TIMEOUT = int(os.environ.get("GEN_TIMEOUT", str(45 * 60)))
TITLE = "CI River Song"
LYRICS = "Morning light upon the river\nCarry me home tonight\n\nSing it loud and sing it free\nYou are the song in me"

os.makedirs(OUT, exist_ok=True)
results = {}
shot_count = 0
W, H = 1080, 2400


def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(os.path.join(OUT, "steps.log"), "a") as f:
        f.write(line + "\n")


def adb(*args, timeout=120):
    try:
        return subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log(f"adb timeout: {args}")
        return subprocess.CompletedProcess(args, 1, b"", b"")


def shell(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout).stdout.decode(errors="replace")


def shot(name):
    global shot_count
    shot_count += 1
    path = os.path.join(OUT, f"{shot_count:02d}-{name}.png")
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p").stdout)
    log(f"screenshot {os.path.basename(path)}")


def dump(save_as=None):
    for _ in range(4):
        shell("uiautomator dump /sdcard/ui.xml", timeout=60)
        text = adb("exec-out", "cat", "/sdcard/ui.xml").stdout.decode(errors="replace")
        start, end = text.find("<?xml"), text.rfind("</hierarchy>")
        if start >= 0 and end > start:
            xml = text[start:end + len("</hierarchy>")]
            try:
                root = ET.fromstring(xml)
                if save_as:
                    with open(os.path.join(OUT, save_as), "w") as f:
                        f.write(xml)
                return root
            except ET.ParseError:
                pass
        time.sleep(2)
    return None


def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", n.get("bounds", "[0,0][0,0]")))
    return x1, y1, x2, y2


def texts(root):
    if root is None:
        return []
    out = []
    for n in root.iter("node"):
        for v in (n.get("text"), n.get("content-desc")):
            if v:
                out.append(v)
    return out


def find_all(text, exact=False, root=None):
    root = root if root is not None else dump()
    found = []
    if root is None:
        return found
    for n in root.iter("node"):
        for v in (n.get("text") or "", n.get("content-desc") or ""):
            if (v == text) if exact else (text in v):
                x1, y1, x2, y2 = bounds(n)
                if x2 > x1 and y2 > y1:
                    found.append(n)
                break
    return found


def pick(nodes, which):
    if not nodes:
        return None
    if which == "lowest":
        return max(nodes, key=lambda n: bounds(n)[1])
    if which == "highest":
        return min(nodes, key=lambda n: bounds(n)[1])
    if which == "first":
        return nodes[0]
    return min(nodes, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))


def on_screen(n):
    x1, y1, x2, y2 = bounds(n)
    return y1 >= H * 0.06 and y2 <= H * 0.86


def tap_node(n):
    x1, y1, x2, y2 = bounds(n)
    shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(1.2)


def swipe(up=True):
    a, b = (int(H * 0.70), int(H * 0.35)) if up else (int(H * 0.35), int(H * 0.70))
    shell(f"input swipe {W // 2} {a} {W // 2} {b} 350")
    time.sleep(1.0)


def find(text, exact=False, which="smallest", scroll=False, wait=8):
    deadline = time.time() + wait
    swipes = 0
    while True:
        nodes = find_all(text, exact)
        if scroll:
            nodes = [n for n in nodes if on_screen(n)]
        n = pick(nodes, which)
        if n is not None:
            return n
        if scroll and swipes < 10:
            swipe(True)
            swipes += 1
            continue
        if time.time() > deadline:
            return None
        time.sleep(1)


def tap(text, exact=False, which="smallest", scroll=False, wait=8):
    n = find(text, exact, which, scroll, wait)
    if n is None:
        log(f"NOT FOUND: {text!r}")
        return False
    tap_node(n)
    log(f"tapped {text!r}")
    return True


def type_text(s):
    for i, line in enumerate(s.split("\n")):
        if i:
            shell("input keyevent 66")
        if line:
            safe = re.sub(r"[^A-Za-z0-9 .,:]", "", line).replace(" ", "%s")
            shell(f"input text '{safe}'")
    time.sleep(0.5)


def keyboard_shown():
    return "mInputShown=true" in shell("dumpsys input_method")


def hide_keyboard():
    if keyboard_shown():
        shell("input keyevent 4")
        time.sleep(0.8)


def scroll_top():
    for _ in range(6):
        swipe(False)


def app_alive():
    return shell(f"pidof {PKG}").strip() != ""


def crashes():
    text = adb("logcat", "-d", "-b", "crash").stdout.decode(errors="replace")
    return text if PKG in text else ""


def check_alive(where):
    if not app_alive() or crashes():
        log(f"APP NOT RUNNING after: {where}")
        results.setdefault("crash_after", where)
        return False
    return True


def wait_for(text, timeout, exact=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find_all(text, exact):
            return True
        time.sleep(2)
    return False


def main():
    global W, H
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    if m:
        W, H = int(m.group(1)), int(m.group(2))
    log(f"screen {W}x{H}")

    r = adb("install", "-r", "-g", APK, timeout=300)
    log("install: " + (r.stdout + r.stderr).decode(errors="replace").strip().splitlines()[-1])
    shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")
    adb("logcat", "-b", "crash", "-c")

    # 1. First launch and the welcome dialog.
    shell(f"am start -W -n {PKG}/.MainActivity")
    time.sleep(8)
    shot("launch")
    results["launched"] = check_alive("launch")
    if not results["launched"]:
        return
    if tap("Use my PC", exact=True, wait=10):
        time.sleep(1.5)
        shot("pc-guide")
        shell("input keyevent 4")
        time.sleep(1.5)

    # 2. Settings: point the app at the PC engine.
    tap("Settings", exact=True, which="lowest")
    time.sleep(2)
    shot("settings")
    field = find("PC address", exact=True, scroll=True)
    if field is None:
        log("PC address field not found")
    else:
        tap_node(field)
        type_text(PC_URL)
        hide_keyboard()
        tap("Test connection", exact=True, scroll=True)
        results["pc_connected"] = wait_for("Connected", 40)
        log(f"PC connected: {results['pc_connected']}")
        shot("pc-tested")
        dump("settings.xml")

    scroll_top()
    if tap("Models & setup", exact=True, scroll=True):
        time.sleep(2)
        shot("phone-ai-setup")
        shell("input keyevent 4")
        time.sleep(1.5)
    check_alive("settings")

    # 3. Create: title, lyrics, a 30-second preview.
    tap("Create", exact=True, which="lowest")
    time.sleep(2)
    shot("create")
    if tap("Optional, taken from the first line", wait=8):
        type_text(TITLE)
        hide_keyboard()
    if tap("Write your lyrics here", scroll=True, wait=8):
        type_text(LYRICS)
        hide_keyboard()
    shot("create-filled")
    dump("create.xml")
    if not tap("Preview 30s", exact=True, wait=8):
        results["queued"] = False
        return
    time.sleep(3)
    if find("Continue", exact=True, wait=2):
        tap("Continue", exact=True)
    time.sleep(3)
    shot("library-queued")
    check_alive("create")

    # 4. Wait for the song.
    start = time.time()
    last_shot = 0
    status = "timeout"
    while time.time() - start < GEN_TIMEOUT:
        root = dump()
        all_text = texts(root)
        if any(t == "Retry" for t in all_text):
            status = "failed"
            break
        if find_all("Play", exact=True, root=root):
            status = "ready"
            break
        if not app_alive():
            status = "crashed"
            break
        if time.time() - last_shot > 120:
            stage = [t for t in all_text if any(k in t for k in ("Writing", "Singing", "Saving", "Starting", "Waiting"))]
            log(f"generating {int(time.time() - start)}s: {stage[:2]}")
            shot("generating")
            last_shot = time.time()
        time.sleep(10)
    results["song"] = status
    results["song_seconds"] = int(time.time() - start)
    log(f"song: {status} after {results['song_seconds']}s")
    shot("library-" + status)
    dump("library.xml")
    if status != "ready":
        results["library_text"] = texts(dump())
        return

    # 5. Play it. Tapping a ready song opens the full player.
    tap("Play", exact=True)
    time.sleep(6)
    shot("player")
    dump("player.xml")
    session = shell("dumpsys media_session")
    results["playback_state"] = re.findall(r"state=PlaybackState \{state=(\w+)", session)[:3]
    log(f"media session: {results['playback_state']}")
    if find("Now playing", exact=True, wait=3) is not None:
        results["player_opened"] = True
        shell("input keyevent 4")
        time.sleep(1.5)
    shot("library-mini-player")
    results["mini_player"] = find("Pause", exact=True, which="lowest", wait=3) is not None

    # Save to Music through the song's menu, then pull the file for analysis.
    if tap("More", exact=True):
        time.sleep(1)
        shot("song-menu")
        if tap("Save to Music", exact=True):
            time.sleep(4)
            shot("saved")
    listing = [l.strip() for l in shell("ls -1 /sdcard/Music/Melodia/").splitlines() if l.strip().lower().endswith(".mp3")]
    log(f"Music/Melodia: {listing}")
    for name in listing:
        local = os.path.join(OUT, "audio", "app-" + name.replace(" ", "_"))
        adb("pull", f"/sdcard/Music/Melodia/{name}", local)
        with open(os.path.splitext(local)[0] + "-lyrics.txt", "w") as f:
            f.write(LYRICS)
    results["saved_files"] = listing

    notes = shell("dumpsys notification --noredact")
    results["ready_notification"] = "is ready" in notes

    # 6. A look at the Voice designer.
    tap("Create", exact=True, which="lowest")
    time.sleep(1.5)
    if tap("New voice", exact=True, scroll=True):
        time.sleep(2)
        shot("voice-designer")
        swipe(True)
        shot("voice-designer-2")
        shell("input keyevent 4")
        time.sleep(1.5)
    check_alive("voice designer")

    # 7. "Write for me": the songwriter replaces the typed lyrics.
    tap("Create", exact=True, which="lowest")
    time.sleep(1.5)
    scroll_top()
    before = [t for t in texts(dump()) if "\n" in t]
    if tap("Write for me", exact=True, scroll=True):
        start = time.time()
        outcome = "timeout"
        while time.time() - start < 15 * 60:
            all_text = texts(dump())
            busy = any(t.startswith("Writing lyrics") or t.startswith("Starting the songwriter") for t in all_text)
            fields = [t for t in all_text if "\n" in t]
            if any("couldn't write" in t or "isn't installed" in t for t in all_text):
                outcome = "failed: " + next(t for t in all_text if "couldn't write" in t or "isn't installed" in t)
                break
            if not busy and fields and fields != before and time.time() - start > 5:
                outcome = "written"
                break
            time.sleep(2)
        results["write_for_me"] = outcome
        results["write_for_me_seconds"] = int(time.time() - start)
        log(f"write for me: {outcome} in {results['write_for_me_seconds']}s")
        shot("written-lyrics")
        dump("written.xml")
        results["written_lines"] = [t for t in texts(dump()) if "\n" in t][:1]
    check_alive("end")


if __name__ == "__main__":
    os.makedirs(os.path.join(OUT, "audio"), exist_ok=True)
    try:
        main()
    except Exception as e:
        log(f"script error: {e!r}")
        results["script_error"] = repr(e)
    finally:
        with open(os.path.join(OUT, "logcat.txt"), "w") as f:
            f.write(adb("logcat", "-d", "-v", "time").stdout.decode(errors="replace")[-400_000:])
        c = crashes()
        if c:
            results["crash"] = c[-4000:]
            with open(os.path.join(OUT, "crash.txt"), "w") as f:
                f.write(c)
        with open(os.path.join(OUT, "results.json"), "w") as f:
            json.dump(results, f, indent=2)
        log("results: " + json.dumps(results)[:2000])
    ok = results.get("launched") and results.get("song") == "ready" and "crash" not in results
    sys.exit(0 if ok else 1)
