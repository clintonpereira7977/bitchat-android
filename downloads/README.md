# ChitChat 0.2 — connection requests

Install `ChitChat-android.apk` on both Android phones. If an earlier personal test APK causes a signature conflict, uninstall it first. Open the APK with Files / My Files and allow installation from that file manager when prompted.

One person opens a room. The other finds it and taps Connect. The room owner taps Accept or Decline. Accept opens the encrypted chat on both phones; no code comparison is required. Keep both apps visible while chatting. Android 8–11 also needs Location enabled for BLE scanning.

Approval grants permission to chat. Nicknames are not verified identities. Only accept requests you expect.

See BUILDINFO.json and the Build ChitChat workflow for this build's test results. Physical-phone compatibility and range have not been verified. The iPhone source needs Apple signing for installation. Source and the full guide are on the `codex/chitchat-build` branch under `chitchat/`.

This is an independent direct Bluetooth chat app, without BitChat mesh relay. Version 0.2 requires the updated app on both phones.
