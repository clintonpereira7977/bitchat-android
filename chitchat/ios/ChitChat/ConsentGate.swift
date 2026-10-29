import Foundation

final class ConsentGate {
    enum State { case start, waitingRequest, waitingApproval, incomingRequest, waitingReady, chat, declined, closed }
    let hosting: Bool
    private(set) var state = State.start
    var canChat: Bool { state == .chat }
    init(hosting: Bool) { self.hosting = hosting }
    private func require(_ role: Bool, _ expected: State) throws {
        guard hosting == role, state == expected else { throw ChatError.invalid("Unexpected connection request state") }
    }
    func keysReady() throws {
        guard state == .start else { throw ChatError.invalid("Duplicate handshake") }
        state = hosting ? .waitingRequest : .waitingApproval
    }
    func receiveRequest() throws { try require(true, .waitingRequest); state = .incomingRequest }
    func accept() throws { try require(true, .incomingRequest); state = .waitingReady }
    func receiveAcceptance() throws { try require(false, .waitingApproval); state = .chat }
    func receiveReady() throws { try require(true, .waitingReady); state = .chat }
    func decline() throws { try require(true, .incomingRequest); state = .declined }
    func receiveDecline() throws { try require(false, .waitingApproval); state = .declined }
    func receiveDeclineAck() throws { try require(true, .declined); state = .closed }
    func requireChat() throws { guard canChat else { throw ChatError.invalid("Approval required") } }
}
