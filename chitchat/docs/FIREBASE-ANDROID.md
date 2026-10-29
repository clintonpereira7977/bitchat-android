# Firebase Android integration checkpoint

This change packages the owner's Firebase **client configuration** for
`app.chitchat` / `chitchat-2faca` and adds notification plumbing on the
`codex/chitchat-online` development branch. It is **not** a new public APK or
a completed Online client. The working Nearby v0.2 installer is unchanged.

The public client JSON contains project/app identifiers and a client API key;
it is not a service-account credential. No Admin private key belongs in this
repository, an APK, chat or CI output.

## Behavior

- Only firebase-messaging is included: no Firebase Auth, Analytics, Firestore,
  Crashlytics, paid database or Google login requirement is added.
- Manifest defaults disable FCM auto-init, Firebase data collection, Analytics,
  BigQuery delivery export and delegated notification rendering. The Nearby
  Activity has no calls to OnlinePush or getToken. Firebase initialization reads
  local generated resources; it does not opt the device into messaging.
- The future registered Online client must explicitly enable notifications for
  its account, request Android 13+ notification permission in context, request a
  token, and PUT it to /v1/push using its device authentication. Server-side
  removal via signed DELETE /v1/push is required on opt-out/account deletion.
- Automatic registration stays off after opt-in. Token refresh is explicitly
  requested from foreground Online sessions; onNewToken stages a rotated token
  locally for upload by that signed-in client. An old async getToken result is
  rejected if the account changed or the user opted out while it was pending.
- Server pushes must be data-only `kind` hints: inbox, requests, contacts.
  Android checks consent and OS permission before rendering fixed generic text.
  No message, ciphertext, nickname or provider-provided preview is displayed.
- A single private-visibility notification collapses alerts and opens the app
  via an immutable PendingIntent. The forthcoming Online Activity routing must
  handle OPEN_ONLINE and retrieve the signed inbox. That routing is not part of
  this checkpoint, so notifications remain disabled from the current UI.
- This checkpoint keeps Internet permission in the development build for the
  SDK, without introducing network calls into Nearby screens. It does not turn
  Android's shared Google Play services connection on or off for other apps.

## Validation

CI compiles the actual Firebase SDK and the supplied client resources, runs
notification-policy unit tests, then uses an API35 emulator for native checks:
config IDs, automatic registration disabled, no installation JSON on fresh
startup, opt-out blocks token requests/alerts, account changes clear token
staging, rotation cannot clear a newer token, and actual generic notification
rendering/removal. These use only synthetic tokens and never call getToken while
enabled, contact Google for registration, or send a real push.

The backend suite verifies data-only hints, fixed Google endpoints, OAuth token
caching, no chat contents in provider payloads and provider-failure handling.
Real phone token registration and Cloudflare-to-FCM delivery remain unverified
until the owner installs the Online client and configures the sender secret.

## Remaining owner setup

The Cloudflare sender needs an IAM service account restricted to sending FCM in
project chitchat-2faca (Firebase Cloud Messaging API Admin,
roles/firebasecloudmessaging.admin). Its private JSON goes directly into Worker
Settings → Variables and Secrets → FCM_SERVICE_ACCOUNT_JSON, type Secret. It
must never be attached in chat or committed. Enable Firebase Cloud Messaging
API v1 for that project if not enabled. No Firebase paid plan is required for
FCM. A long private pilot invitation phrase goes into PILOT_INVITE_CODE.

The Worker must also be redeployed from the updated branch to use data-only
hints. The current importer starts from main and fetches this branch in its
deploy command; branch pushes may not trigger deployment until the build's
production branch is changed to codex/chitchat-online.

## Official references

- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/docs/cloud-messaging/android/get-started
- https://firebase.google.com/docs/cloud-messaging/auth-server
- https://cloud.google.com/iam/docs/roles-permissions/firebasecloudmessaging

FCM is the Android notification transport only. This does not configure iPhone
Web Push or native APNs, or provide a complete client encryption design.
