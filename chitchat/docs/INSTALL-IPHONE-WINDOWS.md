# ChitChat on iPhone, using a Windows PC

Use ChitChat-iPhone-unsigned.ipa. This is an actual ARM64 iPhone build, **not** a simulator build and **not** an APK. It requires iOS 16 or later. It is deliberately unsigned: your sideloading tool signs it with your Apple Account during installation. Tapping the file in Files alone will not install it.

The app uses the same ChitChat/v2 Bluetooth protocol as Android 0.2.0. The iPhone device build and Swift protocol tests are automated; physical Android–iPhone and iPhone–iPhone testing still needs your devices. Keep both apps visible while chatting.

## Recommended for repeat use: SideStore

A Windows PC and USB cable are needed for the initial setup. Afterwards, SideStore can renew the app from your phone over Wi-Fi. Free Apple signing lasts seven days; renew before it expires. Apple permits three active apps on a free account, including SideStore. Installing the app and signing renewal require internet; chatting uses Bluetooth.

1. Open the official prerequisites: https://docs.sidestore.io/docs/installation/prerequisites . Install the Apple device drivers/iTunes and **iloader** linked there. Use the official links rather than search advertisements. Have a passcode set on your iPhone.
2. Install **LocalDevVPN** from the App Store using SideStore's instructions. It is needed while installing or refreshing apps through SideStore; it is not the chat connection.
3. Follow https://docs.sidestore.io/docs/installation/install . Connect the iPhone by USB, unlock it and trust the PC. Open iloader, sign in with your own Apple Account locally, select your phone and choose **Install SideStore (Stable)**. Complete the pairing, developer trust and Developer Mode steps required for your iOS version. Never send your account password or device pairing file to someone else.
4. On the iPhone, connect to Wi-Fi, enable LocalDevVPN and open SideStore. Finish setup and refresh SideStore itself as instructed.
5. Download **ChitChat-iPhone-unsigned.ipa** into the iPhone's Files app. In SideStore → **My Apps**, tap **+**, choose that IPA, and let SideStore sign and install it.
6. Open ChitChat from your Home Screen and allow Bluetooth. Choose a nickname. Android opens a room; iPhone taps **Find a friend** and **Connect**; Android accepts. Either native app can also host; test each role on your phones.
7. Before the seven-day timer expires, open SideStore on Wi-Fi with LocalDevVPN active and choose **Refresh All**. Turn on SideStore's background refresh if offered; check that renewal actually succeeds. If an app expires, it must be renewed/reinstalled before it opens again.

## Simpler initial setup: Sideloadly

1. Install **Sideloadly for Windows** from https://sideloadly.io/ and follow its official Apple-driver requirements.
2. Connect and trust the iPhone by USB.
3. Drag **ChitChat-iPhone-unsigned.ipa** into Sideloadly. Select the phone, enter your own Apple Account locally and click **Start**. Complete Apple's verification prompts.
4. Trust your developer profile in iPhone Settings → General → VPN & Device Management. Enable Developer Mode if requested.
5. Open ChitChat and allow Bluetooth.
6. Use Sideloadly's automatic refresh or reconnect to the PC and re-sign with the **same Apple Account and bundle ID** before seven days pass. Automatic refresh depends on the PC and tool being available.

Official renewal details: https://sideloadly.io/faq . Pick one signing route for this app; avoid switching tools/accounts repeatedly.

## Lowest ongoing installation friction

For friends to tap an invitation and install without PCs or weekly signing, use TestFlight for the native app. It requires an Apple Developer Program membership and external beta review. Each TestFlight build lasts up to 90 days. The long-term public distribution route is the App Store. No paid signing service is needed for the free routes above.

For a phone-only first trial, the separate **ChitChat web client in Bluefy** can join a room hosted by the existing Android app. It has no signing-renewal timer. The native IPA above works independently of Bluefy.

## Connection checks

Start 1–2 metres apart. Enable Bluetooth on both phones. On Android 8–11, Location also needs to be enabled for BLE discovery. Keep both apps visible and do not lock the screen. Test Accept, Decline, messages both ways, leave/reconnect, then swap which phone hosts. Leaving or backgrounding clears the chat; the nickname is saved locally. Approval is permission to chat and does not prove a person's identity.

Sources checked 29 October 2026: official SideStore installation/FAQ, Sideloadly FAQ, Apple Personal Team and TestFlight documentation.
