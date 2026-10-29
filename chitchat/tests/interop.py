"""Independent Python implementation checks Java against the documented CryptoKit wire format."""
import base64
import hashlib
import hmac
import os
import subprocess
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root = Path(__file__).resolve().parents[1]
for trial in range(12):
    with subprocess.Popen(["java", "-cp", str(root / ".test-classes"), "app.chitchat.InteropHarness"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as proc:
        remote = base64.b64decode(proc.stdout.readline().strip())
        assert remote[0] == 1 and len(remote) == 66
        remote = remote[1:]
        private = ec.generate_private_key(ec.SECP256R1())
        own = private.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
        shared = private.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), remote))
        salt = hashlib.sha256(b"".join(sorted([own, remote]))).digest()
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=b"ChitChat/v1/session").derive(shared)
        low = hmac.new(key, b"ChitChat/v1/low-to-high", hashlib.sha256).digest()
        high = hmac.new(key, b"ChitChat/v1/high-to-low", hashlib.sha256).digest()
        send_key, receive_key = (low, high) if own < remote else (high, low)
        sas = hashlib.sha256(key).digest()[:6].hex().upper()
        expected = " ".join(sas[i:i+4] for i in range(0, 12, 4))
        proc.stdin.write(base64.b64encode(b"\x01" + own).decode() + "\n"); proc.stdin.flush()
        assert proc.stdout.readline().strip() == expected
        plain = '{"t":"message","text":"Hello Java नमस्ते 👋"}'
        nonce = os.urandom(12)
        packet = b"\x02" + nonce + AESGCM(send_key).encrypt(nonce, plain.encode(), b"ChitChat/v1")
        proc.stdin.write(base64.b64encode(packet).decode() + "\n"); proc.stdin.flush()
        assert proc.stdout.readline().rstrip("\n") == plain
        sealed = base64.b64decode(proc.stdout.readline().strip())
        assert sealed[0] == 2
        assert AESGCM(receive_key).decrypt(sealed[1:13], sealed[13:], b"ChitChat/v1").decode() == "Hello from Java. नमस्ते 👋"
        proc.stdin.close()
        assert proc.wait(timeout=5) == 0
print("PASS: 12 independent P-256 / HKDF / AES-GCM interoperability sessions")
