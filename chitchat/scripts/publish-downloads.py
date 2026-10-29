"""Publish the personal installer and synthetic CI previews on a separate download branch."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import quote

repo = os.environ["GITHUB_REPOSITORY"]
branch = "codex/chitchat-download"

def api(method, endpoint, body=None, optional=False):
    cmd = ["gh", "api", "--method", method, endpoint]
    if body is not None:
        cmd += ["--input", "-"]
    result = subprocess.run(cmd, input=json.dumps(body) if body is not None else None, text=True, capture_output=True)
    if result.returncode:
        if optional and "404" in result.stderr:
            return None
        raise RuntimeError("GitHub download publication failed: " + result.stderr)
    return json.loads(result.stdout)

ref = api("GET", f"repos/{repo}/git/ref/heads/{branch}", optional=True)
if ref is None:
    api("POST", f"repos/{repo}/git/refs", {"ref": "refs/heads/" + branch, "sha": os.environ["GITHUB_SHA"]})

checks = os.environ.get("CHITCHAT_RUNTIME_CHECKS", "unknown")
info = {"app":"ChitChat", "version":"0.2.0", "protocol":"ChitChat/v2", "androidPackage":"app.chitchat", "minimumAndroid":"8.0", "sourceCommit":os.environ["GITHUB_SHA"], "workflowRun":os.environ["GITHUB_RUN_ID"], "nativeRuntimeChecks":checks, "apkSHA256":hashlib.sha256(Path("dist/ChitChat-android.apk").read_bytes()).hexdigest(), "identity":"Connection approval does not authenticate a person's identity.", "physicalPhonesVerified":False}
Path("dist/BUILDINFO.json").write_text(json.dumps(info, indent=2)+"\n")
Path("dist/README.md").write_text("""# ChitChat 0.2 — connection requests

Install `ChitChat-android.apk` on both Android phones. If an earlier personal test APK causes a signature conflict, uninstall it first. Open the APK with Files / My Files and allow installation from that file manager when prompted.

One person opens a room. The other finds it and taps Connect. The room owner taps Accept or Decline. Accept opens the encrypted chat on both phones; no code comparison is required. Keep both apps visible while chatting. Android 8–11 also needs Location enabled for BLE scanning.

Approval grants permission to chat. Nicknames are not verified identities. Only accept requests you expect.

See BUILDINFO.json and the Build ChitChat workflow for this build's test results. Physical-phone compatibility and range have not been verified. The iPhone source needs Apple signing for installation. Source and the full guide are on the `codex/chitchat-build` branch under `chitchat/`.

This is an independent direct Bluetooth chat app, without BitChat mesh relay. Version 0.2 requires the updated app on both phones.
""")
files = [Path("dist/ChitChat-android.apk"), Path("dist/SHA256SUMS.txt"), Path("dist/README.md"), Path("dist/BUILDINFO.json"), *sorted(Path("dist/ui").glob("*.png"))]
for file in files:
    if not file.is_file():
        continue
    target = "downloads/" + file.name
    endpoint = f"repos/{repo}/contents/{quote(target, safe='/')}"
    previous = api("GET", endpoint + "?ref=" + quote(branch, safe=""), optional=True)
    content = base64.b64encode(file.read_bytes()).decode()
    body = {"message": "Publish ChitChat personal installer and CI preview", "content": content, "branch": branch}
    if previous:
        body["sha"] = previous["sha"]
    result = api("PUT", endpoint, body)
    print("Published", target, result["content"]["sha"], flush=True)
if checks == "success":
    for name in ["pair-failure-host.png", "pair-failure-guest.png", "failure.png"]:
        endpoint = f"repos/{repo}/contents/downloads/{name}"
        previous = api("GET", endpoint + "?ref=" + quote(branch, safe=""), optional=True)
        if previous:
            api("DELETE", endpoint, {"message":"Remove obsolete synthetic failure capture", "sha":previous["sha"], "branch":branch})
print("Installer and previews are on the separate download branch; the main branch is unchanged.")
