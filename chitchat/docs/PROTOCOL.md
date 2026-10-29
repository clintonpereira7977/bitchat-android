# ChitChat protocol v2

Direct, single-peer BLE; no network stack or mesh relay. The host is a GATT peripheral; the guest is a GATT central. All UUIDs use 128-bit form.

| Purpose | UUID | Properties |
| --- | --- | --- |
| Service | `c17c0001-5a7e-4c10-8a21-6d6573680001` | Primary |
| Receive | `c17c0002-5a7e-4c10-8a21-6d6573680001` | Write with response |
| Transmit | `c17c0003-5a7e-4c10-8a21-6d6573680001` | Indicate |
| Transmit CCCD | `00002902-0000-1000-8000-00805f9b34fb` | Enable indications `02 00` |

Android advertises the service UUID and adds a two-byte random room ID as service data in its scan response. iOS advertises the service UUID and local name `ChitChat ABCD`. Room labels are only discovery hints; identity depends on comparing the session code.

## Fragments

Every write/indication is at most 20 bytes to work with ATT MTU 23:

```
flags:u8 | sequence:u16-big-endian | payload:1..17 bytes
```

Bit 0 = start; bit 1 = end; other bits must be zero. Sequence starts at zero for every packet and increments. Packets may not interleave. New starts replace unfinished packets. Maximum reassembled packet: 4096 bytes. Assemblies expire after 15 seconds. Guests serialize writes with response; hosts use reliable indications. Android serializes indication callbacks; CoreBluetooth owns the iOS peripheral indication queue and exposes backpressure via `updateValue`/`peripheralManagerIsReady`.

## Handshake and encryption

1. Each connection creates a fresh P-256 ECDH key pair. The hello packet is `03 || uncompressed-public-key-65-bytes` (ANSI X9.63, leading `04`). No nickname in hello. Duplicate and reflected hello packets are rejected.
2. Validate and import the peer public key using the platform crypto implementation. Derive the 32-byte ECDH shared secret.
3. Sort the two raw public keys in unsigned lexicographic order. `salt = SHA256(low_public || high_public)`.
4. `master = HKDF-SHA256(shared_secret, salt, info="ChitChat/v2/session", outputLength=32)`.
5. Derive directional AES keys: `low_to_high = HMAC-SHA256(master, "ChitChat/v2/low-to-high")`; `high_to_low = HMAC-SHA256(master, "ChitChat/v2/high-to-low")`. A sender chooses according to its public key's position. Separate keys reject packet reflection.
6. An internal fingerprint of the master key remains available for crypto test vectors; it is not displayed or compared by users. There is no manual identity-authentication step in version 2.
7. Encrypted packet: `02 || random-nonce-12 || ciphertext || GCM-tag-16`. AES-256-GCM with AAD UTF-8 `ChitChat/v2`. Android uses JCA; iOS uses CryptoKit. Directional received-nonce sets reject replay. Session limit: 10,000 incoming encrypted packets.

Encrypted plaintext is UTF-8 JSON. Consent follows this ordered flow:

```json
{"t":"request","name":"Sweet Tooth"}
{"t":"accept","name":"Room owner"}
{"t":"ready"}
{"t":"message","id":"UUID","text":"Hello 👋"}
{"t":"ack","id":"same-UUID"}
```

Selecting Connect is the requester's consent. The host accepts a single request only after its local user taps Accept, and then waits for `ready`. The requester opens chat only after receiving `accept`, and queues `ready` before any messages. The host does not accept `ready` before local acceptance. Role-bound state machines reject duplicate, early and wrong-role control messages; neither UI nor message handling permits chat without acceptance.

Declining sends `{"t":"decline"}`; the requester replies `{"t":"declined"}` and closes the link. The host reopens a fresh room after acknowledgment/disconnection, with a three-second fallback. Cancelling a pending request disconnects without creating a chat. Pending requests expire after 180 seconds.

Nicknames are shared by the requester on request and by the host on acceptance. They are unverified display names. Text remains bounded to 1,000 UTF-8 bytes; sessions cap retained messages and pending sends. Acknowledgments mean app receipt, not human reading or persistence. Lost acknowledgments become unconfirmed, without automatic resend.

Version 2 uses hello marker `03` and version-2 key derivation/AAD labels. Version-1 code-matching builds are incompatible: both phones must be updated.

## Security boundary

This is an original, unaudited application protocol using platform cryptography. A passive observer can see BLE advertisements, connection metadata, lengths and timing. A nearby active attacker can disrupt connectivity. Approval is access control, not cryptographic identity authentication. Without manual fingerprint comparison, an active man-in-the-middle can establish separate encrypted sessions and impersonate nicknames. Only accept expected requests. Encryption still protects passive observers, subject to the cryptographic implementation’s security. There is no trusted directory, stored identity, account recovery, forward-secure message ratchet, or independent audit. Fresh ephemeral connection keys prevent simply decrypting old sessions using a future session's keys, but do not protect compromised devices or retained process memory. Java key arrays are zeroed where possible; runtime and CryptoKit memory lifetime is managed by the platform.

No transport names or encrypted-message claims imply compatibility with BitChat. No code or services from that app are used.
