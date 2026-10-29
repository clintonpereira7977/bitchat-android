# Install ChitChat on two phones

**Use the 0.2.0 APK on both phones.** Download `ChitChat-android.apk` from the repository's `codex/chitchat-download` branch → `downloads/`, or download and unzip the **ChitChat-Android-APK** artifact from a successful **Actions → Build ChitChat** run. Open the APK directly in your phone's Files app.

The Android source is in `chitchat/android/`; the iPhone project is `chitchat/ios/ChitChat.xcodeproj`. The iPhone source is included; installation requires Apple signing. Check CI for the current version’s compile result. Physical phone-to-phone Bluetooth tests remain outstanding.

## Two Android phones

Requires Android 8.0+ and Bluetooth Low Energy. At least one phone must support BLE peripheral advertising; most modern Android phones do.

### 1. Get the APK

Use the built APK described above, or build your own using the README. Your file will be named either `app-debug.apk` or `ChitChat-android.apk`; they are the same application build if copied by the provided script. Keep the original file to share and keep a copy of the build computer's debug signing key for future updates.

### 2. Install on your phone

If you installed an earlier test APK, uninstall it first when Android reports a signature conflict. Personal CI builds may use different debug signing keys.

1. Transfer the APK to your phone by USB, Quick Share, or a file-sharing app.
2. Open **Files / My Files**, find the APK, and tap it.
3. If Android asks, allow **Install unknown apps** for that file manager. On many phones: **Settings → Apps → Special app access → Install unknown apps**.
4. Tap **Install**, then open **ChitChat**. Device security policy may require extra confirmation for a personal APK. Only install the APK you built or received from your trusted friend; do not disable device protection globally.
5. Allow **Nearby devices**. On Android 8–11, allow **Location** and turn Location on: those Android versions require it for Bluetooth scanning. ChitChat doesn't read or store location coordinates.
6. Turn on Bluetooth if prompted. No pairing in the phone's Bluetooth settings is required.

### 3. Install on your friend's Android

On ChitChat's home screen, tap **SHARE CHITCHAT WITH A FRIEND** and choose Quick Share, Bluetooth file transfer, or another file-transfer option your phone offers. Alternatively, send the original APK with Files / My Files. Your friend follows step 2 above on their phone. Bluetooth file transfer availability varies; Quick Share is a separate transfer service and is only used to send the installer.

### 4. Start talking

Version 0.2 uses a new protocol. Update **both** phones; it cannot chat with the earlier code-matching build.

1. Start within **1–2 metres** with both apps open.
2. You tap **Open a room**. Note the four-character room ID.
3. Your friend taps **Find a friend**, then **Connect** beside your room ID. If discovery doesn't show a room ID, look for **Nearby ChitChat room**.
4. Your friend taps **Connect** to send a request. You see their nickname and choose **Accept** or **Decline**. Accept opens the chat on both phones; there is no code to compare. Only accept requests you expect; a nickname does not prove someone’s identity.
5. Type a message and tap the white **↑** button. “Delivered ✓” appears after the recipient app acknowledges receipt.
6. Keep both apps visible. Range depends on hardware, walls and interference; no fixed range is promised. Leaving the app, losing Bluetooth or moving out of range closes the session. Reconnect by opening a new room. Messages are not queued for later delivery.

## Android + iPhone, or two iPhones

The iPhone source uses the same BLE protocol, so mixed-platform communication is implemented, but it still needs native compilation and device testing. An iPhone cannot install an APK.

### Install with a Mac and Xcode

1. Install Xcode on a Mac. Open `ios/ChitChat.xcodeproj` from this package.
2. Connect the iPhone by USB, unlock it, and trust the Mac when prompted.
3. In Xcode, open **Settings → Accounts** and add your Apple ID yourself. Do not share passwords or signing keys in chat.
4. Select the project → **ChitChat** target → **Signing & Capabilities**. Enable automatic signing, choose your team, and set a unique bundle identifier, e.g. `com.yourname.chitchat`.
5. Select the connected iPhone as the run destination. On the iPhone, enable **Developer Mode** if Xcode requests it, then restart and confirm.
6. Click Xcode's **Run ▶**. If the iPhone asks to trust the developer, use **Settings → General → VPN & Device Management**.
7. Launch ChitChat and allow Bluetooth. Then follow “Start talking” above.

### Install on your friend's iPhone

- **Nearby with your Mac:** connect their iPhone and repeat the Xcode run steps. Apple account/device/signing limits apply. Free Personal Team builds generally need re-signing about every seven days and are not a permanent distribution method.
- **Friend has a Mac:** give them this source zip and have them sign and run with their own Apple ID.
- **Remote, normal app-style distribution:** use a paid Apple Developer Program team and **TestFlight**. Archive the app in Xcode, upload to App Store Connect, and invite testers. External testing may require Apple's review. That release cannot be completed without your signing team and developer-account access.

There is no universal unsigned IPA that installs on any iPhone. The simulator build in GitHub Actions only checks compilation and cannot be installed on a phone.

## If something goes wrong

| Symptom | Try |
| --- | --- |
| No room found | Both apps open; one hosts, one searches; move closer; check Bluetooth/Nearby devices; Android 8–11 also needs Location on. Search stops after 30 seconds; tap Search again. |
| Phone cannot open a room | Swap roles. Some Android BLE hardware supports scanning but not peripheral advertising. |
| Connected but no chat | The room owner must accept the connection request. Pending requests expire after three minutes. Both phones must use version 0.2.0 or later. |
| “Unconfirmed” message | The other app hasn't acknowledged it. It may or may not have arrived. Reconnect; there is no silent automatic resend. |
| Disconnected after switching apps/locking | This first version is foreground-only. Keep both apps on screen; the app keeps the display awake while connected. |
| APK says “App not installed” / signature conflict | Builds made with different signing keys cannot update each other. Uninstall the old personal build, then install the new APK. |
| A friend has BitChat installed | Both phones need this ChitChat build. The protocols differ. |
| iPhone won't install APK | Build and sign the iPhone project through Xcode or TestFlight. |

## Privacy in plain language

Nearby devices can detect that a ChitChat room exists. Session message contents use encryption. The requester shares a nickname when sending a request; the owner shares theirs on acceptance. Approval gives permission to chat, without verifying identity. Your nickname is saved locally. Messages are kept in memory and cleared when the room closes. A recipient can still copy or photograph them; keyboards, the operating system and crash/memory tooling are outside this promise. This implementation has not had an independent security audit.
