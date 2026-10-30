# Repeat notifications: Android beta 4 and delivery service 0.1.3

Two distinct paths require verification. Android beta 4 posts individual child
alerts under an explicitly silent summary, with a fresh tag and numeric ID for
each new delivery. Its existing notification channel and user sound preferences
are retained. The APK update is signed to match permanent beta 3.

Service 0.1.3 additionally removes the collapse key from inbox and test pushes.
Previously, all pending pushes shared one key, so Firebase could replace pending
hints before a sleeping or disconnected phone received them. Contact refreshes
can still coalesce. Chat text, envelopes, contacts and IDs remain absent from FCM.
No database migration, new secret, account reset, billing change or notification
registration reset is required. Old Android clients remain API-compatible.

The exact physical cause of the reported beta 3 issue is not confirmed. Neither
native posting tests nor Firebase acceptance proves a phone showed a banner or
played a sound. Android cooldown, Do Not Disturb, rate limits, battery policy,
connectivity and the FCM pending-message limit still apply. A powered-off phone
cannot show live alerts. No phone policy is bypassed.

## Owner rollout

Build and deploy the latest codex/chitchat-online service source to the existing
chitchat-online Worker. Keep Production runtime secrets, existing Durable Object
storage and Free plan. Never expose or rotate credentials for this update.
If build root is /, the existing command is:

    git fetch origin codex/chitchat-online && git checkout --detach FETCH_HEAD && cd chitchat/online && npm ci && npm test && npm run deploy

If the build root is already chitchat/online and production branch is
codex/chitchat-online, use npm ci && npm test && npm run deploy there.

After traffic reaches the new deployment, /v1/health must report version 0.1.3,
pushDelivery per-message-v1, pushPayload data-only-v1, status ok and both existing
configuration flags true. These confirm configuration, not actual delivery.

## Phone check

Install beta 4 as an Update over permanent beta 3. Stop on a signature conflict;
do not uninstall, clear phone data or reset an ID. Lock the receiving phone, send
three messages a few seconds apart, and leave every previous notification in
the tray. Confirm each later message triggers an alert. If one does not, open
Online → Check notifications and compare the local received-hint and posted-alert
counts before and after repeating. They contain no message contents or IDs.

Future direct APK releases can offer an in-app download/update prompt; that is
not included in beta 4. Android normally still requires installation approval.
Google Play distribution can provide automatic updates after compatible signing
and store setup. Server changes are rolled out once centrally, never separately
by each app user.
