"""Refuse to publish an installer that cannot update the permanent beta 3.

Only public APK certificates are inspected. No keystore or password is read.
"""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

baseline = 'downloads/ChitChat-0.3-beta3.apk'
candidate = Path('dist/ChitChat-0.3-beta4.apk')
apksigner = str(Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/apksigner')
subprocess.run(['git', 'fetch', '--depth=1', 'origin', 'codex/chitchat-download'], check=True)
old_apk = Path(os.environ['RUNNER_TEMP']) / 'chitchat-update-baseline.apk'
old_apk.write_bytes(subprocess.check_output(['git', 'show', 'FETCH_HEAD:' + baseline]))
metadata = json.loads(subprocess.check_output(['git', 'show', 'FETCH_HEAD:downloads/BUILDINFO-0.3-beta3.json']))
if metadata.get('permanentReleaseSigning') is not True or hashlib.sha256(old_apk.read_bytes()).hexdigest() != metadata.get('sha256'):
    raise RuntimeError('The beta 3 signing baseline did not match its published metadata')

def certificate(apk):
    output = subprocess.check_output([apksigner, 'verify', '--print-certs', str(apk)], text=True)
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.M)
    if len(digests) != 1:
        raise RuntimeError('Expected exactly one verified APK signer')
    return digests[0].lower()

digest = certificate(candidate)
if digest != certificate(old_apk):
    raise RuntimeError('Signing certificate differs from beta 3; publication blocked to preserve in-place updates and IDs')
Path('dist/UPDATE-SIGNATURE.json').write_text(json.dumps({
    'sameSignerAsBeta3': True,
    'certificateSHA256': digest,
    'baseline': 'ChitChat-0.3-beta3.apk',
}, indent=2) + '\n')
old_apk.unlink()
print('PASS: release signature matches permanent beta 3; in-place updates preserve app data')
