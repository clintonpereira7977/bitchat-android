# ChitChat 0.2 validation

Current change: replace manual fingerprint comparison with requester consent and owner Accept / Decline.

## Completed locally

- 2,989 Java protocol checks passed, including no chat before approval, wrong-role and early-ready rejection, duplicate requests/approval, decline closure, and fresh approval for a new request.
- Java syntax checks passed for all Android sources.
- 12 independent Python cryptography sessions against Java passed for version 2 hello format, ECDH/HKDF, directional keys, internal key fingerprint and AES-GCM in both directions.
- Android XML, iPhone plist, assets, Python test sources and workflow structure parsed successfully.

## Pending native CI

- Android native compile, APK signature verification and UI smoke checks.
- Paired Android emulator checks: no composer before approval; requester cancellation; decline; owner room reopening; one-tap accept; encrypted messages both directions; receipts; typing/draft preservation; disconnect cleanup.
- Swift/CryptoKit consent and encryption tests, including the independent deterministic fixture.
- iPhone project simulator compilation.

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
