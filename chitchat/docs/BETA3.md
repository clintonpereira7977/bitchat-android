# ChitChat beta 3: repeat alerts and Nearby readability

Native Android update only; no server redeployment, data migration or Bluetooth
protocol change is required by these source changes. Version 0.3.0-beta3 / 5.

Each new FCM delivery now creates a fresh Android notification tag rather than
updating the same tray item. Provider redelivery is deduplicated using only a
bounded list of hashed delivery IDs. Up to six unread alerts remain visible;
opening chat or opting out clears all of them. New alerts request sound and
vibration while respecting Android permissions, channel settings and Do Not
Disturb. Phone firmware can still throttle rapid alerts. FCM contains no chat
text, contact names or message previews.

Nearby now uses bold headings/buttons, medium-weight body text, larger captions,
proper action icons and content-sized buttons. Home and room screens scroll on
small displays and at larger font scales. Window insets are handled once, so the
keyboard does not add a second gap. The chat scroll callback no longer retains
stale views after leaving. Repeated discovery updates an existing room button.
The Bluetooth handshake, approval, wire and encryption code remain unchanged.

Checks include native consecutive-push/duplicate/opt-out tests, compact screen
with larger text, existing Online flows, Bluetooth protocol interoperability,
and two-emulator real Bluetooth request/accept/chat/keyboard/leave behavior.
Synthetic screenshots are retained with the workflow artifact for review.
Physical repeat sound/banner behavior and UI still need phone confirmation.

Publication requires all four existing owner signing secrets in GitHub Actions.
Do not silently generate a replacement key or publish a CI debug-key APK. Keep
existing APKs available. An owner-signed beta 2 can update with the same owner key;
an original temporary-key beta requires an explicitly agreed fresh-ID migration.
No app uninstall, clear-data operation or ID reset is performed by this update.
