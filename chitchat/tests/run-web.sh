#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .web-test-classes
java com.sun.tools.javac.Main -encoding UTF-8 -d .web-test-classes android/app/src/main/java/app/chitchat/Session.java tests/InteropHarness.java
CHITCHAT_JAVA_CLASSES="$PWD/.web-test-classes" node tests/web.test.mjs
node --check web/app.mjs
node --check web/sw.js
