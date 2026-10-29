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
        let h = ConsentGate(hosting: true), g = ConsentGate(hosting: false)
        check(!h.canChat && !g.canChat, "no chat before request")
        reject("host cannot send before approval") { try h.requireChat() }
        reject("guest cannot send before approval") { try g.requireChat() }
        reject("accepting nonexistent request") { try h.accept() }
        reject("acceptance before keys") { try g.receiveAcceptance() }
        try h.keysReady(); try g.keysReady()
        reject("early ready cannot unlock host") { try h.receiveReady() }
        reject("wrong role ready") { try g.receiveReady() }
        reject("wrong role acceptance") { try h.receiveAcceptance() }
        reject("unsolicited decline acknowledgment") { try h.receiveDeclineAck() }
        try h.receiveRequest(); check(h.state == .incomingRequest, "incoming request state")
        reject("duplicate request") { try h.receiveRequest() }
        reject("request alone cannot message") { try h.requireChat() }
        try h.accept(); check(!h.canChat, "host waits for approved requester readiness")
        reject("duplicate acceptance") { try h.accept() }
        try g.receiveAcceptance(); check(g.canChat, "requester waits for host acceptance")
        try h.receiveReady(); check(h.canChat, "host opens after request, acceptance and readiness")
        let no = ConsentGate(hosting: true), denied = ConsentGate(hosting: false)
        try no.keysReady(); try denied.keysReady(); try no.receiveRequest(); try no.decline(); try denied.receiveDecline()
        check(!no.canChat && !denied.canChat, "decline never unlocks chat")
        reject("ready after decline") { try no.receiveReady() }
        reject("accept after decline") { try denied.receiveAcceptance() }
        try no.receiveDeclineAck(); check(no.state == .closed, "decline acknowledgment closes state")
        reject("closed request cannot message") { try no.requireChat() }
        reject("declined request cannot message") { try denied.requireChat() }
        let again = ConsentGate(hosting: true); try again.keysReady(); try again.receiveRequest()
        check(!again.canChat, "fresh request needs fresh approval")
        let vector = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: "tests/vectors.json"))) as! [String: String]
        let hex = vector["privateKeyA"]!
        var privateBytes = Data()
        var index = hex.startIndex
        while index < hex.endIndex { let end = hex.index(index, offsetBy: 2); privateBytes.append(UInt8(hex[index..<end], radix: 16)!); index = end }
        let fixture = SessionCrypto(privateKey: try P256.KeyAgreement.PrivateKey(rawRepresentation: privateBytes))
        let fixtureCode = try fixture.receiveHello(Data(base64Encoded: vector["peerHelloBase64"]!)!)
        check(fixtureCode == vector["fingerprint"], "independent HKDF key-fingerprint vector")
        let fixtureMessage = try fixture.open(Data(base64Encoded: vector["incomingPacketBase64"]!)!)
        check(fixtureMessage["text"] == vector["text"], "independent directional-key / AES-GCM vector")
        for _ in 0..<30 {
            let a = SessionCrypto(), b = SessionCrypto()
            let x = try a.receiveHello(b.hello), y = try b.receiveHello(a.hello)
            check(x == y, "key fingerprint agreement")
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
            var tampered = try a.seal(["t":"request", "name":"friend"])
            tampered[tampered.count - 1] ^= 1
            reject("authentication tag") { _ = try b.open(tampered) }
            reject("duplicate hello") { _ = try a.receiveHello(b.hello) }
        }
        let fresh = SessionCrypto()
        reject("reflected hello") { _ = try fresh.receiveHello(fresh.hello) }
        reject("unestablished send") { _ = try fresh.seal(["t":"request"]) }
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
