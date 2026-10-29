#!/usr/bin/env bash
# Builds a sideloadable APK. Requires JDK 17+, Android SDK, and internet for Gradle dependencies.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
task_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$task_sdk" || ! -f "$task_sdk/platforms/android-35/android.jar" ]]; then
  echo 'Install Android SDK Platform 35 using Android Studio → SDK Manager.' >&2
  echo 'Set ANDROID_HOME to the Android SDK directory, then rerun this script.' >&2
  exit 1
fi
if command -v gradle >/dev/null 2>&1; then
  task_gradle="$(command -v gradle)"
elif [[ -x "$task_root/.tools/gradle-8.9/bin/gradle" ]]; then
  task_gradle="$task_root/.tools/gradle-8.9/bin/gradle"
else
  mkdir -p .tools
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 300 https://services.gradle.org/distributions/gradle-8.9-bin.zip -o .tools/gradle-8.9-bin.zip
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 60 https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256 -o .tools/gradle.sha256
  python3 - <<'PY'
from pathlib import Path
import hashlib
p=Path('.tools/gradle-8.9-bin.zip')
expected=Path('.tools/gradle.sha256').read_text().strip().split()[0]
if hashlib.sha256(p.read_bytes()).hexdigest()!=expected: raise SystemExit('Gradle checksum mismatch')
PY
  unzip -q .tools/gradle-8.9-bin.zip -d .tools
  task_gradle="$task_root/.tools/gradle-8.9/bin/gradle"
fi
ANDROID_HOME="$task_sdk" "$task_gradle" -p android --no-daemon assembleDebug
mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk dist/ChitChat-android.apk
"$task_sdk/build-tools/35.0.0/apksigner" verify --verbose dist/ChitChat-android.apk
python3 - <<'PY'
from pathlib import Path
import hashlib
p=Path('dist/ChitChat-android.apk')
Path('dist/SHA256SUMS.txt').write_text(hashlib.sha256(p.read_bytes()).hexdigest()+'  ChitChat-android.apk\n')
print('Ready to share:',p.resolve())
PY
