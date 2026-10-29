import Foundation
import CryptoKit

enum ChatError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let reason) = self { return reason }; return nil }
}

final class SessionCrypto {
    private let privateKey: P256.KeyAgreement.PrivateKey
    init(privateKey: P256.KeyAgreement.PrivateKey = .init()) { self.privateKey = privateKey }
    private var key: SymmetricKey?
    private var sendKey: SymmetricKey?
    private var receiveKey: SymmetricKey?
    private var seen = Set<Data>()
    var hello: Data { Data([1]) + privateKey.publicKey.x963Representation }
    func receiveHello(_ packet: Data) throws -> String {
        guard key == nil, packet.count == 66, packet.first == 1 else { throw ChatError.invalid("Invalid handshake") }
        let peer = Data(packet.dropFirst()), own = privateKey.publicKey.x963Representation
        guard peer != own else { throw ChatError.invalid("Reflected handshake") }
        let remote = try P256.KeyAgreement.PublicKey(x963Representation: peer)
        let sorted = own.lexicographicallyPrecedes(peer) ? own + peer : peer + own
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: remote)
        let derived = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(SHA256.hash(data: sorted)), sharedInfo: Data("ChitChat/v1/session".utf8), outputByteCount: 32)
        key = derived
        let low = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: Data("ChitChat/v1/low-to-high".utf8), using: derived)))
        let high = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: Data("ChitChat/v1/high-to-low".utf8), using: derived)))
        sendKey = own.lexicographicallyPrecedes(peer) ? low : high
        receiveKey = own.lexicographicallyPrecedes(peer) ? high : low
        let digest = derived.withUnsafeBytes { Data(SHA256.hash(data: Data($0))) }
        let pairs = digest.prefix(6).map { String(format: "%02X", $0) }
        return stride(from: 0, to: 6, by: 2).map { pairs[$0] + pairs[$0 + 1] }.joined(separator: " ")
    }
    func seal(_ object: [String: String]) throws -> Data {
        guard let sendKey else { throw ChatError.invalid("Handshake required") }
        let bytes = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        let box = try AES.GCM.seal(bytes, using: sendKey, authenticating: Data("ChitChat/v1".utf8))
        guard let combined = box.combined else { throw ChatError.invalid("Encryption failed") }
        return Data([2]) + combined
    }
    func open(_ packet: Data) throws -> [String: String] {
        guard let receiveKey, packet.first == 2, packet.count >= 29 else { throw ChatError.invalid("Invalid encrypted packet") }
        let nonce = Data(packet.dropFirst().prefix(12))
        guard !seen.contains(nonce), seen.count < 10000 else { throw ChatError.invalid("Replay or session limit; reconnect") }
        let box = try AES.GCM.SealedBox(combined: Data(packet.dropFirst()))
        let bytes = try AES.GCM.open(box, using: receiveKey, authenticating: Data("ChitChat/v1".utf8))
        guard let object = try JSONSerialization.jsonObject(with: bytes) as? [String: String] else { throw ChatError.invalid("Invalid message") }
        seen.insert(nonce); return object
    }
}

struct Framing {
    static func split(_ packet: Data) throws -> [Data] {
        guard !packet.isEmpty, packet.count <= 4096 else { throw ChatError.invalid("Packet size") }
        let bytes = [UInt8](packet)
        return stride(from: 0, to: bytes.count, by: 17).map { offset in
            let end = min(offset + 17, bytes.count), seq = offset / 17
            let flag: UInt8 = (offset == 0 ? 1 : 0) | (end == bytes.count ? 2 : 0)
            return Data([flag, UInt8(seq >> 8), UInt8(seq & 255)] + Array(bytes[offset..<end]))
        }
    }
    private var buffer: Data?
    private var next = 0
    private var started: TimeInterval = 0
    mutating func accept(_ fragment: Data) throws -> Data? {
        let f = [UInt8](fragment), now = ProcessInfo.processInfo.systemUptime
        guard f.count >= 4, f.count <= 20, f[0] & ~UInt8(3) == 0 else { throw ChatError.invalid("Invalid fragment") }
        let seq = Int(f[1]) * 256 + Int(f[2])
        if f[0] & 1 != 0 {
            guard seq == 0 else { throw ChatError.invalid("Invalid start") }
            buffer = Data(); next = 0; started = now
        }
        guard buffer != nil, seq == next, now - started <= 15, buffer!.count + f.count - 3 <= 4096 else { throw ChatError.invalid("Incomplete packet; reconnect") }
        buffer!.append(contentsOf: f.dropFirst(3)); next += 1
        if f[0] & 2 != 0 { let out = buffer; buffer = nil; next = 0; return out }
        return nil
    }
}
