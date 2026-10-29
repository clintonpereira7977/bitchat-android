#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .test-classes
java com.sun.tools.javac.Main -encoding UTF-8 -d .test-classes android/app/src/main/java/app/chitchat/Session.java android/app/src/main/java/app/chitchat/Wire.java android/app/src/main/java/app/chitchat/ConsentGate.java tests/ProtocolTest.java tests/InteropHarness.java tests/SyntaxCheck.java
java -cp .test-classes app.chitchat.ProtocolTest
java -cp .test-classes SyntaxCheck android/app/src/main/java/app/chitchat/*.java
if python3 -c 'import cryptography' 2>/dev/null; then python3 tests/interop.py; else echo 'SKIP: Python interoperability checks need the cryptography package'; fi
