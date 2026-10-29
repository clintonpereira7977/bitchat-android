# Validation record

## Completed in the authoring workspace

- Android Java source syntax parsing via the JDK compiler parser. This is a parse check, not Android API type checking or a native build.
- 2,965 Java protocol assertions: fresh P-256 agreement, matching code, Unicode round trips, bidirectional AES-GCM, fragmentation at MTU 23, invalid flags / sequence / bounds, expired assembly, reflected keys, reflected encrypted packets, duplicate hellos, modified tags, replay, and session key destruction.
- 12 independent Python `cryptography` sessions against the Java implementation: public key serialization, ECDH, HKDF, directional HMAC derivation, verification codes, and AES-GCM packets in both directions. This validates the documented wire format independently; it does not execute the Swift implementation.
- Android manifest/resource XML, iPhone plist, asset JSON, shared Xcode scheme and CI YAML structurally validated.
- Shell build/test scripts checked with `bash -n`.

## Not completed here

- **Android SDK build / APK signing and verification:** pending the Build ChitChat workflow. Download an APK only from a successful Android job.
- **Swift / Xcode compilation and CryptoKit test execution:** pending the simulator-compile CI job. Apple signing and installation on a real iPhone remain separate steps.
  The Swift suite includes a deterministic test vector generated independently in Python to check the exact public-key, HKDF, directional-key and AES-GCM formats; it has not yet been executed here.
- **UI screenshots / runtime UI review:** no compiled native app, simulator, emulator or connected physical phone available. No browser mockup is presented as a phone test.
- **Real Bluetooth:** no available Android/iOS radios. Android↔Android, Android↔iOS and iOS↔iOS must be checked on actual phones. Simulators do not establish BLE reliability.
- **Independent security audit:** not performed. See the protocol's security boundary.

## Physical-device acceptance checklist

Before treating this as ready for everyday use, build both platforms and run the applicable rows. Test Android 8–11 separately from Android 12+ if you need those versions.

| Test | Expected | Actual |
| --- | --- | --- |
| Install identical APK on two phones | Installs and launches; asks only for documented permissions | Not run |
| Install signed Xcode build on iPhone | Opens; Bluetooth prompt is clear | Not run |
| Android host → Android guest | Room discovered; both codes match; bidirectional messages | Not run |
| Android host → iPhone guest | Same behavior using Android service-data room ID | Not run |
| iPhone host → Android guest | Same behavior using iPhone local-name room ID | Not run |
| iPhone host → iPhone guest | Same behavior | Not run |
| Verification refused / timeout | Both can return home; no plaintext chat | Not run |
| Hindi, emoji, 1,000-byte message | Exact content; delivery receipt; no truncation | Not run |
| Eight rapid sends | Backpressure works; no interleaving or lost messages | Not run |
| Move out of range / disable Bluetooth | Room closes or send becomes unconfirmed; no false delivery | Not run |
| Home / app switch / screen lock | Session closes, messages clear; reopening is home | Not run |
| Reconnect repeatedly / swap host | New code each time; old callbacks don't close the new room | Not run |
| Deny permissions then grant in Settings | Helpful recovery path; no crash | Not run |
| Share installed APK to friend | Read-only APK URI works through supported sharing apps | Not run |
| Large font / narrow screen / keyboard | Controls and messages usable without overlap | Not run |

## Reproduce available checks

```sh
bash scripts/test.sh
```

Optional independent interop dependency: Python 3 `cryptography`. On a Mac:

```sh
swiftc ios/ChitChat/SessionCrypto.swift tests/SwiftProtocolTest.swift -o /tmp/chitchat-swift-tests
/tmp/chitchat-swift-tests
xcodebuild -project ios/ChitChat.xcodeproj -scheme ChitChat -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```
