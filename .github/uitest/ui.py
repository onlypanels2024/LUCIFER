"""Tiny helper for the emulator test: find on-screen text (or button descriptions) and tap it."""
import re, subprocess, sys, time

def adb(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout

def nodes():
    adb("uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n)
        d = re.search(r' content-desc="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        clean = lambda s: (s or "").replace("&#10;", " ").replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'")
        out.append((clean(t.group(1) if t else ""), clean(d.group(1) if d else ""), (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)))
    return out

def find(text, exact=False):
    for t, d, x, y, b in nodes():
        for s in (t, d):
            if s and ((s == text) if exact else (text.lower() in s.lower())):
                return s, x, y
    return None

def tap(text, scroll=0, exact=False, long=False):
    for attempt in range(scroll + 2):
        f = find(text, exact)
        if f:
            s, x, y = f
            if long:
                adb("input", "swipe", str(x), str(y), str(x), str(y), "900")
            else:
                adb("input", "tap", str(x), str(y))
            print(f"tapped '{s}' at {x},{y}")
            time.sleep(1.5)
            return 0
        if attempt < scroll:
            adb("input", "swipe", "540", "1700", "540", "700", "400")
        time.sleep(1)
    print(f"NOT FOUND: '{text}'")
    return 1

def wait(text, timeout, gone=False):
    end = time.time() + timeout
    while time.time() < end:
        present = find(text, exact=True) is not None
        if present != gone:
            print(f"{'gone' if gone else 'found'}: '{text}' after {int(timeout - (end - time.time()))}s")
            return 0
        time.sleep(3)
    print(f"TIMEOUT waiting for '{text}' {'to go' if gone else ''}")
    return 1

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        sys.exit(tap(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0))
    if cmd == "tapx":
        sys.exit(tap(sys.argv[2], exact=True))
    if cmd == "longtap":
        sys.exit(tap(sys.argv[2], long=True))
    if cmd == "wait":
        sys.exit(wait(sys.argv[2], int(sys.argv[3])))
    if cmd == "waitgone":
        sys.exit(wait(sys.argv[2], int(sys.argv[3]), gone=True))
    if cmd == "texts":
        for t, d, x, y, b in nodes():
            if t.strip() or d.strip():
                print(t if t.strip() else f"[{d}]")
