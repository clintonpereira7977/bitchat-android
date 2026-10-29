# Private-pilot delivery API v1

This describes a delivery transport, not an end-to-end cryptographic messaging protocol. Integrate an established client encryption protocol before real chats. Bodies may contain public registration metadata or opaque ciphertext envelopes; the signing key is a device authentication key, not a decryption key.

All authenticated calls include:

- `X-ChitChat-Key`: base64url uncompressed P-256 public signing key (65 bytes)
- `X-ChitChat-Time`: current Unix time in milliseconds (13 digits)
- `X-ChitChat-Nonce`: fresh base64url random 16 bytes
- `X-ChitChat-Signature`: base64url 64-byte IEEE P1363 ECDSA/SHA-256 signature
- `Content-Type: application/json` for POST/PUT, including empty-object bodies

Sign these newline-separated UTF-8 fields, without a final newline:

```text
ChitChat-delivery/v1
HTTP_METHOD
https://SERVICE_ORIGIN/path?query
TIMESTAMP
NONCE
BASE64URL_SHA256_OF_EXACT_UTF8_BODY
```

GET/DELETE have an empty body. The server accepts timestamps within five minutes, and retains used nonces for ten minutes. Retry an HTTP request using a new nonce and signature. Retry sending a queued message using its original UUID and identical envelope; retries do not extend expiry. Do not retry an expired old message as a new send without the user's action. Account IDs are base64url SHA-256 hashes of public signing keys; contact codes encode the first 40 hash bits with the documented unambiguous alphabet. A code collision rejects registration and requires a fresh identity.

| Method and route | Purpose |
| --- | --- |
| GET `/v1/health` | Public service status and server clock |
| POST `/v1/register` | `{name,keyBundle}` with `X-ChitChat-Invite` for first registration |
| GET/PUT/DELETE `/v1/me` | Read/update `{name,keyBundle}` or delete own account |
| GET `/v1/lookup/CODE` | Resolve a shared eight-character code; authenticated and limited |
| POST `/v1/requests` | `{code}` sends a contact request |
| GET `/v1/requests` | Incoming requests |
| POST `/v1/requests/ACCOUNT_ID/accept` or `/decline` | Recipient decision, body `{}` |
| GET `/v1/contacts` | Approved contacts |
| POST `/v1/contacts/ACCOUNT_ID/block` | Block and purge queued messages between both accounts, body `{}` |
| PUT/DELETE `/v1/push` | Set `{token}` or remove Android FCM registration |
| POST `/v1/messages` | `{to,id,envelope}`; `to` is account ID, `id` UUIDv4, `envelope` base64url 48–8,192 bytes |
| GET `/v1/inbox` | First 20 unexpired queued envelopes, ordered by creation/ID |
| POST `/v1/messages/UUID/ack` | Recipient deletes delivered payload, body `{}` |
| POST `/v1/socket-ticket` | Obtain one-minute single-use event ticket, body `{}` |
| GET `/v1/events` | WebSocket upgrade using subprotocols `chitchat.v1` and `ticket.TICKET` |

Socket events: `connected`, `requests`, `contacts`, `inbox`, `delivered` with message ID, and `account-deleted`. Events contain no message envelopes. Fetch the corresponding API after reconnect, because event history is not persisted. `ping` gets `pong` with a limit of ten pings/minute. A new socket for the same account replaces the previous socket.

Acknowledge only after the intended client has accepted/decrypted the message into its current foreground conversation. Keep expired-request handling, no-history-on-exit, and permission to begin a chat explicit in the client UX. The relay is not an authority that can erase copies retained by a recipient.
