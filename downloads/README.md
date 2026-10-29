# ChitChat — Android installer

Download `ChitChat-android.apk`, open it in Files / My Files, allow installation from that file manager if prompted, then tap Install. If an earlier personal test build causes a signature conflict, uninstall that build first.

Install the same APK on your friend's Android. ChitChat's home screen can share its installed APK through Quick Share or another file-transfer app.

Both enable Bluetooth and allow Nearby devices. One opens a room; the other finds it. Compare the 12-character code and both tap Codes match. Keep both apps visible while chatting. Android 8–11 also requires Location enabled for BLE scanning.

The verified build passed native Android UI checks and a two-emulator BLE test covering discovery, verification, encrypted messages both ways, delivery receipts, draft preservation and disconnect cleanup. Physical-phone compatibility and range have not been verified.

The iPhone source compiles, but installation requires Apple signing through Xcode or TestFlight. Source and the full guide are on the `codex/chitchat-build` branch under `chitchat/`.

This is an independent direct Bluetooth chat app. It does not implement BitChat mesh relay.
