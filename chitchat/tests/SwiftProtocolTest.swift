import Foundation
import CryptoKit

@main
struct SwiftProtocolTest {
    static var checks = 0
    static func check(_ ok: Bool, _ description: String) { checks += 1; precondition(ok, description) }
    static func reject(_ description: String, _ action: () throws -> Void) {
        var rejected = false
        do { try action() } catch { rejected = true }
        check(rejected, description)
    }
    static func main() throws {
        let vector = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: "tests/vectors.json"))) as! [String: String]
        let hex = vector["privateKeyA"]!
        var privateBytes = Data()
        var index = hex.startIndex
        while index < hex.endIndex { let end = hex.index(index, offsetBy: 2); privateBytes.append(UInt8(hex[index..<end], radix: 16)!); index = end }
        let fixture = SessionCrypto(privateKey: try P256.KeyAgreement.PrivateKey(rawRepresentation: privateBytes))
        let fixtureCode = try fixture.receiveHello(Data(base64Encoded: vector["peerHelloBase64"]!)!)
        check(fixtureCode == vector["code"], "independent HKDF verification-code vector")
        let fixtureMessage = try fixture.open(Data(base64Encoded: vector["incomingPacketBase64"]!)!)
        check(fixtureMessage["text"] == vector["text"], "independent directional-key / AES-GCM vector")
        for _ in 0..<30 {
            let a = SessionCrypto(), b = SessionCrypto()
            let x = try a.receiveHello(b.hello), y = try b.receiveHello(a.hello)
            check(x == y, "verification code agreement")
            for text in ["Hello", "नमस्ते दोस्त 👋", "你好\nline two", String(repeating: "x", count: 1000)] {
                let sealed = try a.seal(["t":"message", "text":text])
                var decoder = Framing(), output: Data?
                for frame in try Framing.split(sealed) { check(frame.count <= 20, "default MTU"); output = try decoder.accept(frame) }
                guard let output else { fatalError("missing packet") }
                let plain = try b.open(output)
                check(plain["text"] == text, "Unicode encrypted message")
                reject("replay rejected") { _ = try b.open(output) }
                let reflected = try a.seal(["t":"message", "text":"reflection"])
                reject("direction reflection rejected") { _ = try a.open(reflected) }
            }
            var tampered = try a.seal(["t":"approve", "name":"friend"])
            tampered[tampered.count - 1] ^= 1
            reject("authentication tag") { _ = try b.open(tampered) }
            reject("duplicate hello") { _ = try a.receiveHello(b.hello) }
        }
        let fresh = SessionCrypto()
        reject("reflected hello") { _ = try fresh.receiveHello(fresh.hello) }
        reject("unestablished send") { _ = try fresh.seal(["t":"approve"]) }
        reject("oversized packet") { _ = try Framing.split(Data(repeating: 0, count: 4097)) }
        for n in stride(from: 1, through: 4096, by: 13) {
            let input = Data((0..<n).map { UInt8($0 & 255) })
            var decoder = Framing(), output: Data?
            for frame in try Framing.split(input) { output = try decoder.accept(frame) }
            check(input == output, "binary framing")
        }
        print("PASS: \(checks) Swift / CryptoKit protocol checks")
    }
}
