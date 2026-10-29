# Validation record

## Completed checks

- Android Java source syntax parsing via the JDK compiler parser. This is a parse check, not Android API type checking or a native build.
- 2,965 Java protocol assertions: fresh P-256 agreement, matching code, Unicode round trips, bidirectional AES-GCM, fragmentation at MTU 23, invalid flags / sequence / bounds, expired assembly, reflected keys, reflected encrypted packets, duplicate hellos, modified tags, replay, and session key destruction.
- 12 independent Python `cryptography` sessions against the Java implementation: public key serialization, ECDH, HKDF, directional HMAC derivation, verification codes, and AES-GCM packets in both directions. This validates the documented wire format independently; it does not execute the Swift implementation.
- Android manifest/resource XML, iPhone plist, asset JSON, shared Xcode scheme and CI YAML structurally validated.
- Shell build/test scripts checked with `bash -n`.

## Native build and runtime results

- Android SDK 35 native build succeeded. The debug APK passed `apksigner verify`.
- Android 15 emulator UI checks passed: native launch, home actions, help dialog, nickname saving across process restart, permission-denial recovery, and no Android runtime crash during those flows. Actual native screen captures are published with the installer.
- 3,081 Swift / CryptoKit checks passed on a Mac runner, including the independent Python-generated format vector.
- Paired Android 15 emulator BLE checks passed: advertising, discovery, GATT subscription, matching verification codes, encrypted fragmented messages in both directions, receiver acknowledgments, draft and keyboard-focus preservation while receiving, and disconnect cleanup. These use simulated radios and do not establish physical-phone compatibility.
- The iPhone project compiled successfully for the iOS simulator.
- Java protocol and independent Python interoperability tests also passed on the GitHub build runner.

Successful native build and paired-emulator run: `40813444408`, tested app commit `dd0b94ffb7dfd114f727622e795914cd3a8dc110`.

## Verification limits

- **iPhone installation:** Apple signing and installation on a real iPhone remain separate steps.
- **UI coverage:** Android screen captures and the stated native flows were checked. iPhone UI runtime checks and the remaining Android screens have not all been exercised.
- **Physical Bluetooth:** Android↔Android, Android↔iOS and iOS↔iOS must be checked on actual phones. Emulator tests do not establish physical radio range, background behavior or hardware compatibility.
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
