# ChitChat protocol v1

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

1. Each connection creates a fresh P-256 ECDH key pair. The hello packet is `01 || uncompressed-public-key-65-bytes` (ANSI X9.63, leading `04`). No nickname in hello. Duplicate and reflected hello packets are rejected.
2. Validate and import the peer public key using the platform crypto implementation. Derive the 32-byte ECDH shared secret.
3. Sort the two raw public keys in unsigned lexicographic order. `salt = SHA256(low_public || high_public)`.
4. `master = HKDF-SHA256(shared_secret, salt, info="ChitChat/v1/session", outputLength=32)`.
5. Derive directional AES keys: `low_to_high = HMAC-SHA256(master, "ChitChat/v1/low-to-high")`; `high_to_low = HMAC-SHA256(master, "ChitChat/v1/high-to-low")`. A sender chooses according to its public key's position. Separate keys reject packet reflection.
6. Display the first six bytes of `SHA256(master)` as 12 uppercase hex characters in groups of four. Both users must compare every character through a trusted physical/verbal channel and approve. The code authenticates this session, not a stored identity.
7. Encrypted packet: `02 || random-nonce-12 || ciphertext || GCM-tag-16`. AES-256-GCM with AAD UTF-8 `ChitChat/v1`. Android uses JCA; iOS uses CryptoKit. Directional received-nonce sets reject replay. Session limit: 10,000 incoming encrypted packets.

Encrypted plaintext is UTF-8 JSON:

```json
{"t":"approve","name":"Sweet Tooth"}
{"t":"message","id":"UUID","text":"Hello 👋"}
{"t":"ack","id":"same-UUID"}
```

Only `approve` is accepted until both local and remote approvals exist. Text length is bounded to 1,000 UTF-8 bytes. Sessions are capped at about 500 retained messages and eight unconfirmed sends. Acknowledgment means the receiving app accepted the message into volatile UI state. It does not prove that a person read it or persisted it. Lost acknowledgments produce “unconfirmed”; the app does not resend automatically.

Verification expires after 180 seconds. Disconnect, Bluetooth loss, invalid packets or foreground loss close the room. New sessions generate new keys and codes; no identity pinning, reconnection resumption or offline queue.

## Security boundary

This is an original, unaudited application protocol using platform cryptography. A passive observer can see BLE advertisements, connection metadata, lengths and timing. A nearby active attacker can disrupt connectivity. A man-in-the-middle attack is not excluded unless both users compare the full code correctly. There is no trusted directory, stored identity, account recovery, forward-secure message ratchet, or independent audit. Fresh ephemeral connection keys prevent simply decrypting old sessions using a future session's keys, but do not protect compromised devices or retained process memory. Java key arrays are zeroed where possible; runtime and CryptoKit memory lifetime is managed by the platform.

No transport names or encrypted-message claims imply compatibility with BitChat. No code or services from that app are used.
