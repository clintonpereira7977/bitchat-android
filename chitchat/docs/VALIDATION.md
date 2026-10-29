# ChitChat 0.2 validation

Current change: replace manual fingerprint comparison with requester consent and owner Accept / Decline.

## Completed locally

- 2,989 Java protocol checks passed, including no chat before approval, wrong-role and early-ready rejection, duplicate requests/approval, decline closure, and fresh approval for a new request.
- Java syntax checks passed for all Android sources.
- 12 independent Python cryptography sessions against Java passed for version 2 hello format, ECDH/HKDF, directional keys, internal key fingerprint and AES-GCM in both directions.
- Android XML, iPhone plist, assets, Python test sources and workflow structure parsed successfully.

## Native CI passed

Successful run: `40820600543`. Tested source commit: `8a55225a6de2f8118fda3fa394db3b06916be97d`.

- Android native compile and `apksigner` signature verification passed.
- Native Android 15 UI checks passed for launch, home controls, help, nickname saving across process restart, permission-denial recovery and no runtime crash.
- Two Android emulators passed requester cancellation, decline and acknowledgment, automatic owner-room reopening, a fresh request after refusal, no composer before approval, one-tap owner acceptance, no code-comparison UI, encrypted messages in both directions, delivery receipts, draft/focus preservation and disconnect cleanup.
- 3,104 Swift/CryptoKit checks passed, including consent ordering, early/wrong-role rejection, decline and version-2 encryption fixtures.
- The iPhone project compiled successfully for the iOS simulator.

Emulator startup recovery handles the synthetic Pixel Launcher's own crash dialog; it does not dismiss or ignore ChitChat crashes.

## Limits

Physical Android↔Android, Android↔iPhone and iPhone↔iPhone compatibility, range and radio behavior still require actual devices. Emulator results cannot establish those. Apple signing and installation on iPhones remain separate steps. There has been no independent security audit. Approval does not authenticate a person's identity; it replaces the earlier manual comparison step by explicit owner permission.

## Reproduce

From `chitchat/`:

```sh
bash scripts/test.sh
swiftc ios/ChitChat/SessionCrypto.swift ios/ChitChat/ConsentGate.swift tests/SwiftProtocolTest.swift -o /tmp/chitchat-swift-tests
/tmp/chitchat-swift-tests
xcodebuild -project ios/ChitChat.xcodeproj -scheme ChitChat -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

The Android UI tests require a fresh synthetic emulator and refuse to clear personal phones.
