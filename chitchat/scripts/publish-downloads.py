"""Publish the personal installer and synthetic CI previews on a separate download branch."""
import base64
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

files = [Path("dist/ChitChat-android.apk"), Path("dist/SHA256SUMS.txt"), *sorted(Path("dist/ui").glob("*.png"))]
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
print("Installer and previews are on the separate download branch; the main branch is unchanged.")
