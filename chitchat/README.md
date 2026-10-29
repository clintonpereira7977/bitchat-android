# ChitChat

**Small talk. Zero signal.** A native, black-and-white Bluetooth messenger for two nearby phones.

## Delivery status — read this first

An installable Android APK has been built, signature-verified, and checked on an Android 15 emulator. The **Build ChitChat** workflow also compiles the iPhone project and runs its CryptoKit tests. The Android installer is published on the separate `codex/chitchat-download` branch under `downloads/ChitChat-android.apk`, and as the **ChitChat-Android-APK** workflow artifact.

Android native checks cover launch, home actions, help, nickname persistence and permission-denial recovery. Two emulated Android phones also passed discovery, verification, encrypted messages in both directions, delivery receipts, draft preservation and disconnect cleanup. Physical phone-to-phone Bluetooth compatibility remains unverified. The iPhone app still needs Apple signing through Xcode or TestFlight before installation. See [installation](INSTALL.md) and [validation](docs/VALIDATION.md).

## What is implemented

- Native Java / Android BLE and SwiftUI / CoreBluetooth, using a shared protocol.
- Open a room on one phone; find and join it on the other. A room accepts one friend.
- Compare the same 12-character verification code on both phones and approve before chatting.
- Fresh P-256 ECDH session keys, HKDF-SHA256, directional AES-256-GCM encryption, replay rejection.
- Text messaging, UTF-8 / emoji, fragmentation for the default Bluetooth MTU, and receiver acknowledgments. “Delivered” means the other app received the message; it is not a read receipt.
- Message history exists in process memory only and clears when leaving or backgrounding. Nickname is the only saved preference. The app keeps the screen awake during a room.
- Android can share its own installed APK from the home screen using the system share sheet.
- No account, backend, analytics, contact access, or internet permission in the Android app.

This is a new app, not a BitChat fork or compatible client. It implements direct nearby text messaging, **not mesh relay**, remote delivery, attachments, calls, or background messaging. It does not make claims about current app availability or legal status in any country.

## Get it onto phones

See [INSTALL.md](INSTALL.md). Android needs an APK built first. iPhone needs a Mac with Xcode and an Apple signing team; it cannot install an Android APK.

### Fastest Android build

1. Open `chitchat/android/` in current Android Studio with JDK 17 and install SDK Platform 35 + Build Tools 35.0.0.
2. Let Gradle sync, then choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Menu wording can vary by Android Studio version.
3. The signed debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. It can be installed directly on Android 8.0+ phones.
4. Send that **same APK** to your friend; use the steps in [INSTALL.md](INSTALL.md).

Or, on a Mac/Linux development computer with JDK 17+, Python 3 and SDK 35 installed:

Run these commands from `chitchat/`.

```sh
export ANDROID_HOME="/path/to/Android/sdk"
bash scripts/build-android.sh
```

Output: `dist/ChitChat-android.apk` and its checksum. The debug signing key is created by the Android build tools on the build computer. Keep that key for compatible future updates. This is a personal sideload build, not a Play Store release.

### Optional GitHub Actions build

This repository includes `.github/workflows/chitchat-build.yml`. Open **Actions → Build ChitChat** and select a successful run. Pushes to `codex/chitchat-build` start the workflow automatically. When the Android job succeeds, download the **ChitChat-Android-APK** artifact and unzip it. It contains the APK and checksum. The iOS job compiles for the simulator without signing; it does not distribute an iPhone installation file.

GitHub-hosted builds use a fresh debug signing key on each runner. For personal updates built on a different runner, uninstalling the old app may be needed. For a durable release, configure your own protected signing key. The workflow builds this independent app under `chitchat/`; it does not build the original BitChat client.

### iPhone source

Open `chitchat/ios/ChitChat.xcodeproj`, select **ChitChat**, add your Apple team under **Signing & Capabilities**, set a unique bundle ID, and run on a connected iPhone with iOS 16+. The checked-in Xcode project requires no package dependencies. See detailed signing and friend-install options in [INSTALL.md](INSTALL.md).

## Development

```sh
bash scripts/test.sh
```

Runs the Java protocol checks and Java syntax parser. If Python's `cryptography` package is installed, also runs independent interoperability checks. These checks do not test Android APIs, Swift compilation, UI rendering, or real Bluetooth radios.

The Xcode project is checked in; `python3 scripts/create-xcode-project.py` regenerates it after source-file changes.

- [Installation and troubleshooting](INSTALL.md)
- [Wire protocol and security boundaries](docs/PROTOCOL.md)
- [Validation results and physical-device checklist](docs/VALIDATION.md)

## License

MIT. Original source; no BitChat code or assets are included.
