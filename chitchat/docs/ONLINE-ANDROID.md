# ChitChat Online Android beta 0.3 — notification update

This development branch adds an Online mode alongside the existing Nearby
Bluetooth app. Both people need the new app for Online. Existing Nearby 0.2
users remain compatible with this app's unchanged Bluetooth transport.

## Install and use

1. Install ChitChat-0.3-beta2.apk from the owner's shared installer or the
   codex/chitchat-download branch. Allow installation from your Files app if
   Android asks. This APK needs Android 8+; optional notifications need Google
   Play services. No Play Store publication is required.
2. If Android reports a signature conflict with the old Bluetooth-only test
   build, uninstall that old build first. It only stores a nickname. After
   creating an Online ID, uninstalling/clearing data loses that ID and contacts.
   The pilot currently has no account recovery. Keep the APK you install.
3. Choose Online, enter a nickname and the private pilot invitation supplied by
   the owner. The invite is never embedded in the APK or saved by the client.
4. Share your public eight-character ID. Your friend enters it under Add a
   friend. You accept or decline; chat is available only after approval.
5. Open a contact to chat. Leaving/backgrounding clears this phone's opened
   messages and draft. Each participant controls their own copy.
6. Enable optional generic notifications from the Online home screen. Allow the
   Android notification permission. Enable notifications on both real phones
   and test a message while the recipient has backgrounded the app.
7. Switch to Nearby for Bluetooth-only interaction. The Online event connection
   closes, Online alerts are disabled locally and push unregistration is tried.

## Owner's server deployment

Production service: https://chitchat-online.clintonpereira7977.workers.dev
Firebase project: chitchat-2faca. Both runtime secrets were configured by the
owner using Work; that confirms presence, not notification delivery.

Deploy the latest codex/chitchat-online backend source, preserving the existing
runtime FCM_SERVICE_ACCOUNT_JSON and PILOT_INVITE_CODE secrets. The new health
field pushPayload must equal data-only-v1 before the app enables push. This
prevents provider-rendered background notifications from bypassing app opt-out.

If the Cloudflare build root is /, its existing fallback deploy command is:

    git fetch origin codex/chitchat-online && git checkout --detach FETCH_HEAD && cd chitchat/online && npm ci && npm run deploy

If the build root is chitchat/online and production branch codex/chitchat-online,
use npm run deploy. Do not combine that root with the fallback cd command.
Rebuild from source; redeploying an old saved version does not fetch new code.

## Encryption and identity

- Message confidentiality uses Google Tink 1.24 HPKE, RFC9180:
  X25519/HKDF-SHA256/AES-256-GCM, no Tink output prefix.
- A separate Android Keystore P256 identity signs the public HPKE key bundle,
  every HTTP request, and every ciphertext with sender/recipient/message-ID
  context. The backend cannot replace a public decryption key without breaking
  its identity signature. Public IDs/codes derive from the identity key.
- The client validates signatures, identity hashes, code derivation and allowed
  HPKE parameters. Contacts must be explicitly pinned on request/accept. A key
  change pauses the contact instead of silently replacing the pin. Compare the
  full two-person safety number out of band to verify identity.
- This is not Signal, a double ratchet, key transparency or independent security
  review. Static recipient keys do not provide forward secrecy against future
  device key compromise. Human identity is not verified by a nickname or server.
- The HPKE private keyset is encrypted with Tink's Android Keystore Aead and
  stored using AtomicFile in noBackupFilesDir. The signing private key is
  non-exportable. Keystore failure fails closed; no plaintext-key fallback.

## Delivery, clearing, and privacy limits

Unopened ciphertext is staged in an encrypted phone spool before recipient ACK
removes it from the relay. This lets one friend's backlog avoid blocking another
friend's messages. Both relay and phone enforce the original expiry, at most
24 hours. The phone stores at most 200 unopened envelopes and a bounded expiring
dedup ledger. Opened ciphertext is removed; plaintext/drafts are never written
to preferences or a transcript database. Normal process memory, keyboards,
malicious apps, cameras and provider/device backups preclude absolute-erasure
claims. A participant leaving does not remotely wipe another device.

The client closes WebSockets on background, uses generic optional FCM hints,
fetches an authenticated inbox in foreground and clears visible chat onStop.
Production chat windows use Android FLAG_SECURE; synthetic debug screenshots
are allowed. Live receipts mean delivery to encrypted phone storage, not reading.
Failed sends retry with the original message UUID and envelope; they are never
silently replaced with a fresh send. Contacts/identity survive restarts locally.

The Nearby transport and cryptographic files are preserved from verified v0.2;
the separate unshipped BLE reconnect patch is not included. After opting into
FCM, Android's shared Google Play services connection can still exist while
Nearby is active; the app disables its own Online alerts and connections.

## Validation and release status

CI runs the unchanged Bluetooth protocol tests, notification policy tests, native
Keystore/encryption/real local workerd signed-API checks, and a synthetic native
UI flow: signup, incoming request/accept, two-way Unicode messages, live receipt,
draft retention, leave/reopen and real HOME background/reopen clearing. It then
runs the existing native Bluetooth smoke and two-emulator pair checks. These
never use the owner's invitation, FCM sender credential or production accounts.

Real phone Online delivery/notifications still require owner testing. The beta
APK is signed with the CI debug certificate. That is not a permanent owner-held
release key; builds from another run may not update an installed beta. Before a
wider pilot, configure a stable private signing key in GitHub Actions secrets.
Never commit or publish that private keystore or its passwords.

Sources: https://developers.google.com/tink/hybrid and RFC9180.

## Notification update: beta 2

The current owner-reported beta 1 can be kept installed while diagnosing push.
Deploy the latest server, then turn notifications off and on once on the phone.
Token setup now validates the request with Google. The public health check's
pushDiagnostics is v1; pushLastResult is a fixed provider result code with no
account, token, request contents or provider error text. ACCEPTED means Google
accepted a request, not that Android displayed it. No sender errors are hidden
behind the configuration-presence flag. Existing beta 1 message APIs still work.

Beta 2 adds honest registration status, notification settings checks, an
account-authenticated Send test button, audible/vibrating repeat alerts and
foreground alerts for incoming messages outside the visible conversation.
The test arrives through FCM even while the app is open. Message text stays out
of FCM and notification previews. Nearby opt-out remains immediate.

IMPORTANT: beta 1 was signed with an ephemeral CI debug certificate, which was
not preserved as a release secret. This beta 2 cannot be promised as an in-place
update to an installed beta 1. Do not tell an existing user to uninstall or clear
data merely to test push: it loses their ID and contacts. Diagnose the existing
server first. Before any migration to a replacement install, configure a permanent
owner signing key and explicitly agree how to reconnect existing contacts.
The beta 1 public APK and its metadata are preserved. Beta 2 is a separate
private-pilot diagnostic build; no physical FCM delivery has been verified in CI.
