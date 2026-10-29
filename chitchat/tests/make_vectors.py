"""Public deterministic crypto fixtures only; never use these keys or nonces in the app."""
from pathlib import Path
import base64
import hashlib
import hmac
import json
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

a_key = ec.derive_private_key(1, ec.SECP256R1())
b_key = ec.derive_private_key(2, ec.SECP256R1())
a = a_key.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
b = b_key.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
shared = a_key.exchange(ec.ECDH(), b_key.public_key())
master = HKDF(algorithm=hashes.SHA256(), length=32, salt=hashlib.sha256(b"".join(sorted([a,b]))).digest(), info=b"ChitChat/v2/session").derive(shared)
label = b"ChitChat/v2/high-to-low" if a < b else b"ChitChat/v2/low-to-high"
key = hmac.new(master, label, hashlib.sha256).digest()
nonce = bytes(range(12))
text = "Cross-platform fixture नमस्ते 👋"
plain = json.dumps({"t":"message", "text":text}, ensure_ascii=False, separators=(",", ":")).encode()
packet = b"\x02" + nonce + AESGCM(key).encrypt(nonce, plain, b"ChitChat/v2")
fingerprint = hashlib.sha256(master).digest()[:6].hex().upper()
vector = {"description":"Public deterministic test keys only. Never use these keys/nonces in the app.", "privateKeyA":(1).to_bytes(32,"big").hex(), "peerHelloBase64":base64.b64encode(b"\x03"+b).decode(), "fingerprint":" ".join(fingerprint[i:i+4] for i in range(0,12,4)), "incomingPacketBase64":base64.b64encode(packet).decode(), "text":text}
Path(__file__).with_name("vectors.json").write_text(json.dumps(vector, ensure_ascii=False, indent=2)+"\n")
