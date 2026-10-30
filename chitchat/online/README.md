# ChitChat internet delivery service — private pilot

This is the deployable Cloudflare backend and public installation guide for the ChitChat Android 0.3 beta. The tested Android client is under `chitchat/android`; its guide is `chitchat/docs/ONLINE-ANDROID.md`. The working older Android 0.2 APK remains Bluetooth-only. Real-phone Online/notification checks and independent security review remain necessary before wider use.

The public root links to the Android installer. `/nearby/` serves the already-tested iPhone Bluefy Bluetooth client and `/nearby/install.html` explains the unsigned native iPhone/Windows alternative. These iPhone options are Bluetooth-only and require physical-device tests; iPhone Online mode is not implemented yet. Static assets use the existing Workers Free deployment, without a new paid service or domain.

It stores opaque client-encrypted envelopes for approved contacts, for at most 24 hours of delivery availability. A recipient must acknowledge delivery before its queued envelope is removed. Expired envelopes are never returned; background cleanup deletes expired rows on a best-effort schedule (normally every five minutes). Underlying hosting persistence/backups are outside an immediate physical-erasure claim. No recipient or remote-device erasure can be guaranteed by this backend.

## Cloudflare screen settings

Use this repository's **codex/chitchat-online** branch, not main.

| Field | Value |
| --- | --- |
| Project / Worker name | `chitchat-online` |
| Production branch | `codex/chitchat-online` |
| Root directory | `chitchat/online` |
| Build command | Leave empty |
| Deploy command | `npm run deploy` |
| Preview builds | Off for the first pilot |
| Protect with Cloudflare Access | Off; mobile clients use the service's own authentication |
| Plan | Workers Free |

Cloudflare installs the project dependencies before deployment. Wrangler bundles the JavaScript; there is no separate build command. The `new_sqlite_classes` migration provisions free-compatible SQLite-backed Durable Object storage. No paid KV namespace, domain purchase, containers or external database is required. This is a small invite-only pilot, not a production availability commitment.

If Root directory is not visible, expand **Advanced settings** or go back to the repository configuration step. Do not deploy the BitChat Android root. If the selected production branch cannot be changed in the creation screen, use the fallback below rather than deploying main.

### Fallback if this screen cannot select a branch

Select project name `chitchat-online`, leave Build command empty, disable Preview builds, and use this exact Deploy command:

```sh
git fetch origin codex/chitchat-online && git checkout --detach FETCH_HEAD && cd chitchat/online && npm ci && npm run deploy
```

This explicitly checks out the tested backend branch, even if the importer initially selected main. After initial deployment, change the production branch to `codex/chitchat-online` and Root directory to `chitchat/online` under Settings → Builds, then restore the normal `npm run deploy` command. This avoids building any Android code. Only use this fallback on the setup screen where Root directory is still the repository root.

After deployment, open the generated workers.dev address, then `/v1/health`. Expect `status: ok`, `appIntegration: android-beta1`, `pushPayload: data-only-v1`, and both configuration flags true once the owner has added the two runtime secrets. A deployment does not update an installed APK; both Android phones must install the 0.3 beta. Configuration flags alone do not prove actual notification delivery.

## Pilot registration and notifications

Registration is closed until the owner adds `PILOT_INVITE_CODE` in Worker → Settings → Variables and Secrets as a **secret**. Use a long randomly chosen invitation phrase for your small test group; it is not a permanent user ID or account password. Do not put it into GitHub, screenshots or public chat. Device identities use independent signing keys.

Firebase can be configured later with one Worker secret, `FCM_SERVICE_ACCOUNT_JSON`, containing a service account allowed to send FCM messages for the owner's Firebase project. Enter this only in the provider's secure secret settings. Never commit it or paste it into chat. FCM notifications are generic and contain no chat text, ciphertext, sender nickname or contact ID. This initial notification transport targets Android; iPhone web push/native APNs is not implemented by this backend release.

Before those secrets are configured, the status page remains usable, and the app-facing APIs stay closed to registration. Adding a secret may require a new deployment to activate the new configuration.

## What is implemented

- P-256 signed HTTP requests with per-request nonces, five-minute clock tolerance, method/origin/path/body binding and replay rejection. Account IDs and eight-character contact codes derive from a device's public identity key. IDs are public addresses, not passwords.
- Public-key bundles and nicknames, contact requests/acceptance/decline, blocking and account deletion. Public-key bundles are client-defined; this relay does not implement Signal, key transparency or identity verification.
- Opaque bounded envelopes, independent inboxes, approval checks, recipient acknowledgement, idempotent send retries, expiration and size/count limits.
- Single-use one-minute WebSocket tickets, authenticated hibernating event sockets and generic events. An event socket is a hint to fetch state, not a message archive. Live delivery receipt events are not persisted for an offline sender.
- Optional FCM using a scoped server credential, OAuth caching and generic notifications. Provider acceptance is not proof a notification appeared.
- Default 100-account limit, 100 sends per account/day, 5,000 sends globally/day, 200 queued messages per inbox, and 20,000 backend HTTP admissions/day. These are pilot controls; they do not guarantee a paid-plan spending cap or denial-of-service resistance.
- No application console logging or Cloudflare observability logging configured. Hosting/push services can process operational metadata. Daily-scope hashed IP buckets are retained for at most two days for abuse control.

The server never receives a private decryption key, but it cannot prove that an arbitrary envelope contains encrypted data. **Clients must encrypt, verify key bundles, protect identity keys, authenticate contacts and clear local chat state correctly.** This backend alone does not provide a complete or independently audited encrypted messenger. Contacts and public identity metadata persist until account deletion; queued payloads, replay entries, tickets and quota buckets have bounded retention. A payload hash/identifier remains for send deduplication until the original 24-hour expiry, even after payload acknowledgement.

## Development and checks

```sh
npm ci
npm test
npm run check
npm run dry-run
node test/runtime.mjs
```

Use Node 24 for the tests (`node:sqlite`). The runtime check starts a disposable local workerd and temporary SQLite state, uses generated synthetic signing keys, and never sends real Google notifications or touches a Cloudflare account. The GitHub Actions **Check ChitChat internet backend** workflow runs these checks. No cloud deployment occurs in CI.

Never log request headers, signed bodies, push tokens, service credentials or message envelopes when diagnosing real devices.
