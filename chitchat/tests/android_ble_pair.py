"""Try native BLE messaging between two synthetic Android emulator devices."""
from pathlib import Path
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = Path("dist/ui")
OUT.mkdir(parents=True, exist_ok=True)

class Device:
    def __init__(self, serial):
        self.serial = serial
    def adb(self, *args, binary=False):
        return subprocess.check_output(["adb", "-s", self.serial, *args], text=not binary, timeout=30, stderr=subprocess.DEVNULL)
    def screen(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/chitchat-pair.xml")
        return ET.fromstring(self.adb("shell", "cat", "/sdcard/chitchat-pair.xml"))
    def wait(self, text, seconds=40):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            root = self.screen()
            if any(text.casefold() in n.get("text", "").casefold() for n in root.iter("node")):
                return root
            time.sleep(0.5)
        raise AssertionError("Native BLE screen did not reach: " + text)
    def tap(self, root, text=None, description=None):
        entries = list(root.iter("node"))
        found = next((n for n in entries if (text is not None and n.get("text", "").casefold() == text.casefold()) or (description is not None and n.get("content-desc") == description)), None)
        if found is None and text is not None:
            found = next((n for n in entries if text.casefold() in n.get("text", "").casefold()), None)
        assert found is not None, "Expected native control: " + str(text or description)
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",found.get("bounds", "")))
        self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(0.5)
    def screenshot(self, name):
        (OUT / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
    def prepare(self):
        assert self.adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Synthetic emulator required"
        self.adb("install", "-r", "dist/ChitChat-android.apk")
        self.adb("shell", "pm", "clear", "app.chitchat")
        for permission in ["BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"]:
            self.adb("shell", "pm", "grant", "app.chitchat", "android.permission." + permission)
        self.adb("shell", "svc", "bluetooth", "enable")
        self.adb("shell", "am", "start", "-W", "-n", "app.chitchat/.MainActivity")
        root = self.wait("What should we call you?")
        self.tap(root, "Later")
        self.wait("Open a room")
    def send(self, message):
        root = self.screen()
        field = next((n for n in root.iter("node") if n.get("class") == "android.widget.EditText"), None)
        assert field is not None, "Verified conversation composer required"
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",field.get("bounds", "")))
        self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        self.adb("shell", "input", "text", message)
        self.tap(self.screen(), description="Send message")

host = Device("emulator-5554")
guest = Device("emulator-5556")
assert host.adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
subprocess.run(["avdmanager", "create", "avd", "--force", "--name", "chitchat_peer", "--package", "system-images;android-35;google_apis;x86_64", "--device", "pixel_6"], input="no\n", text=True, check=True, stdout=subprocess.DEVNULL)
with open("dist/peer-emulator.log", "w") as log:
    emulator_binary = str(Path(os.environ["ANDROID_HOME"]) / "emulator/emulator")
    process = subprocess.Popen([emulator_binary, "-avd", "chitchat_peer", "-port", "5556", "-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot", "-noaudio", "-no-boot-anim", "-memory", "2048", "-cores", "2"], stdout=log, stderr=log)
    try:
        end = time.monotonic() + 240
        while time.monotonic() < end:
            if process.poll() is not None:
                raise RuntimeError("Second emulator exited during startup: " + Path("dist/peer-emulator.log").read_text()[-2500:])
            try:
                if guest.adb("shell", "getprop", "sys.boot_completed").strip() == "1":
                    break
            except (subprocess.SubprocessError, OSError):
                pass
            time.sleep(2)
        else:
            raise RuntimeError("Second synthetic emulator did not boot")
        guest.adb("shell", "input", "keyevent", "82")
        for key in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
            guest.adb("shell", "settings", "put", "global", key, "0")
        host.prepare(); guest.prepare()
        host.tap(host.screen(), "Open a room")
        opened = host.wait("is open.")
        room = re.search(r"Room ([0-9A-F]{4}) is open", " ".join(n.get("text", "") for n in opened.iter("node")))
        assert room, "Host must advertise a room"
        guest.tap(guest.screen(), "Find a friend")
        end = time.monotonic() + 28
        while time.monotonic() < end:
            root = guest.screen()
            label = next((n.get("text", "") for n in root.iter("node") if ("Room " + room.group(1)) in n.get("text", "") or "Nearby ChitChat room" in n.get("text", "")), None)
            if label:
                guest.tap(root, label)
                break
            time.sleep(0.5)
        else:
            raise AssertionError("Guest did not discover the native BLE room")
        a = host.wait("Same little secret?")
        b = guest.wait("Same little secret?")
        def code(root):
            return re.search(r"\b[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}\b", " ".join(n.get("text", "") for n in root.iter("node"))).group(0)
        assert code(a) == code(b), "Native BLE verification codes must match"
        host.tap(a, "Codes match"); guest.tap(b, "Codes match")
        host.wait("BLUETOOTH · VERIFIED"); guest.wait("BLUETOOTH · VERIFIED")
        message = "hello-from-host-" + "fragment" * 50
        host.send(message)
        guest.wait(message)
        host.wait("delivered")
        guest.send("hello-from-guest")
        host.wait("hello-from-guest")
        guest.wait("delivered")
        host.adb("shell", "input", "keyevent", "BACK")
        guest.adb("shell", "input", "keyevent", "BACK")
        host.screenshot("03-ble-host"); guest.screenshot("04-ble-guest")
        guest.tap(guest.screen(), "Leave")
        guest.tap(guest.wait("Leave this room?"), "Leave")
        host.wait("Room closed")
        print("PASS: native BLE advertising, discovery, GATT connection, matching verification, bidirectional fragmented encrypted messages, delivery acknowledgments, disconnect cleanup", flush=True)
    except Exception:
        for device, name in [(host, "pair-failure-host"), (guest, "pair-failure-guest")]:
            try:
                device.screenshot(name)
                print("SYNTHETIC_PAIR_STATE", name, ET.tostring(device.screen(), encoding="unicode"), flush=True)
            except Exception:
                pass
        raise
    finally:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
