"""Exercise native screens on a fresh CI emulator. Does not claim BLE radio coverage."""
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = Path("dist/ui")
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False):
    return subprocess.check_output(["adb", *args], text=not binary)

def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/chitchat-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/chitchat-ui.xml"))

def node(root, text):
    for entry in root.iter("node"):
        if text.casefold() in entry.get("text", "").casefold():
            return entry
    return None

def tap(entry):
    if entry is None:
        raise AssertionError("Expected a visible control")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", entry.get("bounds", "")))
    print("TAP", entry.get("text", ""), entry.get("content-desc", ""), entry.get("bounds", ""), flush=True)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.6)

def wait_for(text):
    for _ in range(12):
        view = screen()
        if node(view, text) is not None:
            return view
        time.sleep(0.5)
    snapshot("failure")
    print("CI_SCREEN_STATE", ET.tostring(view, encoding="unicode"), flush=True)
    raise AssertionError("Expected visible text: " + text)

def snapshot(name):
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))

# This script may clear/restart only an app on an emulator, never a personal phone.
assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "CI emulator required"
adb("logcat", "-c")
adb("install", "-r", "dist/ChitChat-android.apk")
adb("shell", "am", "start", "-W", "-n", "app.chitchat/.MainActivity")
view = wait_for("What should we call you?")
tap(node(view, "Later"))
view = wait_for("Small talk.")
assert node(view, "Open a room") is not None
assert node(view, "Find a friend") is not None
assert node(view, "SHARE CHITCHAT") is not None

# Help opens as a native dialog and returns to the app.
info = next((e for e in view.iter("node") if e.get("content-desc") == "Help and privacy"), None)
tap(info)
view = wait_for("Less signal.")
assert node(view, "No internet") is not None
tap(node(view, "Got it"))

# Use synthetic test data, save it, then verify it survives process restart.
view = wait_for("YOU /")
tap(node(view, "YOU /"))
view = wait_for("What should we call you?")
field = next((e for e in view.iter("node") if e.get("class") == "android.widget.EditText"), None)
tap(field)
adb("shell", "input", "text", "Test-Friend")
view = screen()
tap(node(view, "That's me"))
view = wait_for("Test-Friend")
snapshot("01-home")
adb("shell", "am", "force-stop", "app.chitchat")
adb("shell", "am", "start", "-W", "-n", "app.chitchat/.MainActivity")
view = wait_for("Test-Friend")
assert node(view, "Open a room") is not None

# Check the permission-denial recovery path without requiring emulator BLE support.
tap(node(view, "Find a friend"))
for _ in range(10):
    view = screen()
    deny = next((e for e in view.iter("node") if e.get("resource-id", "").endswith(":id/permission_deny_button")), None)
    if deny is not None:
        tap(deny)
        break
    time.sleep(0.5)
view = wait_for("Bluetooth needs permission")
snapshot("02-permission-help")
tap(node(view, "OK"))
wait_for("Small talk.")

logs = adb("logcat", "-d", "-s", "AndroidRuntime:E")
assert "FATAL EXCEPTION" not in logs, "App crashed during native smoke test"
print("PASS: native launch, home actions, help dialog, nickname save/restart, permission-denial recovery, no crash")
