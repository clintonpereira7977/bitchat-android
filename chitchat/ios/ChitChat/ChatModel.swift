import Foundation
import SwiftUI
import CoreBluetooth

struct NearbyRoom: Identifiable { let id: UUID; let title: String }
struct ChatMessage: Identifiable {
    let id: String
    let text: String
    let own: Bool
    var receipt: String
}

final class ChatModel: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "c17c0001-5a7e-4c10-8a21-6d6573680001")
    static let rxID = CBUUID(string: "c17c0002-5a7e-4c10-8a21-6d6573680001")
    static let txID = CBUUID(string: "c17c0003-5a7e-4c10-8a21-6d6573680001")
    @Published var page = "home"
    @Published var status = ""
    @Published var rooms: [NearbyRoom] = []
    @Published var messages: [ChatMessage] = []
    @Published var friend = "Friend"
    @Published var incomingRequest = false
    @Published var requestPending = false
    @Published var requestSent = false
    @Published var error: String?
    @Published var nickname = UserDefaults.standard.string(forKey: "nickname") ?? "Sweet Tooth" {
        didSet { UserDefaults.standard.set(nickname, forKey: "nickname") }
    }
    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var devices: [UUID: CBPeripheral] = [:]
    private var peer: CBPeripheral?
    private var subscriber: CBCentral?
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?
    private var hostTX: CBMutableCharacteristic?
    private var hostRX: CBMutableCharacteristic?
    private var hosting = false, ready = false, writing = false, pendingReopen = false, declinedResult = false
    private var pending = "", roomCode = "", generation = 0
    private var outgoing: [Data] = []
    private var early: [Data] = []
    private var decoder = Framing()
    private var crypto: SessionCrypto?
    private var consent: ConsentGate?
    private var afterSend: (() -> Void)?
    private var received = Set<String>()
    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    }
    func start(_ action: String) {
        leave(); hosting = action == "host"; pending = action
        page = hosting ? "host" : "find"; status = "Starting Bluetooth…"
        UIApplication.shared.isIdleTimerDisabled = true
        startWhenReady()
    }
    private func startWhenReady() {
        guard !pending.isEmpty else { return }
        let state = hosting ? peripheralManager.state : central.state
        if state == .unknown || state == .resetting { return }
        guard state == .poweredOn else {
            fail(state == .unauthorized ? "Allow Bluetooth in Settings → Privacy & Security → Bluetooth → ChitChat." : "Turn on Bluetooth in Settings and try again."); return
        }
        pending = ""
        if hosting {
            roomCode = String(format: "%04X", UInt16.random(in: 0...UInt16.max))
            let service = CBMutableService(type: Self.service, primary: true)
            let receive = CBMutableCharacteristic(type: Self.rxID, properties: [.write], value: nil, permissions: [.writeable])
            let transmit = CBMutableCharacteristic(type: Self.txID, properties: [.indicate], value: nil, permissions: [.readable])
            hostTX = transmit; hostRX = receive; service.characteristics = [receive, transmit]
            peripheralManager.add(service)
        } else {
            central.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            status = "Listening for nearby rooms…"
            let g = generation
            DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
                guard let self, self.generation == g, self.page == "find", self.peer == nil else { return }
                self.central.stopScan(); self.status = self.rooms.isEmpty ? "No rooms yet. Ask your friend to open one, then search again." : "Choose a room below, or search again."
            }
        }
    }
    func join(_ room: NearbyRoom) {
        guard page == "find", peer == nil, let device = devices[room.id] else { return }
        central.stopScan(); requestPending = true; peer = device; device.delegate = self; status = "Sending a connection request…"
        central.connect(device, options: nil)
        timeout(20, "Connection timed out. Move closer and try again.") { !$0.ready }
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if !pending.isEmpty { startWhenReady() }
        else if page != "home", !hosting, central.state != .poweredOn { fail("Bluetooth became unavailable. Start a new room after turning it on.") }
    }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if !pending.isEmpty { startWhenReady() }
        else if page != "home", hosting, peripheral.state != .poweredOn { fail("Bluetooth became unavailable. Start a new room after turning it on.") }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard page == "find", peer == nil, devices[peripheral.identifier] == nil else { return }
        var title = "Nearby ChitChat room"
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data], let bytes = serviceData[Self.service], bytes.count == 2 {
            title = "Room " + bytes.map { String(format: "%02X", $0) }.joined()
        } else if let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String, name.hasPrefix("ChitChat ") { title = "Room " + String(name.dropFirst(9)) }
        devices[peripheral.identifier] = peripheral; rooms.append(NearbyRoom(id: peripheral.identifier, title: title))
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === peer else { return }; peripheral.discoverServices([Self.service])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if peripheral === peer { fail("Couldn't connect. Ask your friend to open a new room.") }
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if peripheral === peer { disconnected("Your friend disconnected. Keep both apps open and reconnect.") }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === peer else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else { fail("This isn't a compatible ChitChat room."); return }
        peripheral.discoverCharacteristics([Self.rxID, Self.txID], for: service)
    }
    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        if peripheral === peer, invalidatedServices.contains(where: { $0.uuid == Self.service }) { fail("The room closed or changed. Find a new room to reconnect.") }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === peer else { return }
        rx = service.characteristics?.first(where: { $0.uuid == Self.rxID }); tx = service.characteristics?.first(where: { $0.uuid == Self.txID })
        guard error == nil, rx != nil, let tx else { fail("The room's Bluetooth service is incomplete."); return }
        peripheral.setNotifyValue(true, for: tx)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === peer else { return }
        if error == nil, characteristic.uuid == Self.txID, characteristic.isNotifying { established() }
        else { fail("Couldn't subscribe to messages. Reconnect.") }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === peer, characteristic.uuid == Self.txID else { return }
        guard error == nil, let data = characteristic.value else { fail("Bluetooth receive failed."); return }; receive(data)
    }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === peer, characteristic.uuid == Self.rxID, writing else { return }
        guard error == nil else { fail("Bluetooth couldn't send. Reconnect."); return }
        if !outgoing.isEmpty { outgoing.removeFirst() }; writing = false; pump()
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard page == "host", hosting, service.characteristics?.contains(where: { $0 === hostTX }) == true else { return }
        guard error == nil else { fail("Couldn't register the room."); return }
        peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.service], CBAdvertisementDataLocalNameKey: "ChitChat " + roomCode])
    }
    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard page == "host", hosting else { return }
        if error != nil { fail("Couldn't advertise. Try swapping who opens the room.") }
        else { status = "Room \(roomCode) is open. Your friend can find it now." }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard hosting, page != "home", characteristic === hostTX else { return }
        guard subscriber == nil else { return }
        subscriber = central; peripheralManager.stopAdvertising(); established()
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if characteristic === hostTX, subscriber?.identifier == central.identifier { disconnected("Your friend disconnected. Open a new room to reconnect.") }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // Respond once per CoreBluetooth write batch, reject the entire batch if malformed.
        guard let first = requests.first else { return }
        let valid = ready && requests.allSatisfy { $0.central.identifier == subscriber?.identifier && $0.characteristic === hostRX && $0.offset == 0 && ($0.value?.count ?? 0) >= 4 && ($0.value?.count ?? 100) <= 20 }
        peripheral.respond(to: first, withResult: valid ? .success : .requestNotSupported)
        if valid { for request in requests { if let value = request.value { receive(value) } } }
    }
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { pump() }
    private func established() {
        guard !ready else { return }; ready = true; status = "Checking your connection…"
        crypto = SessionCrypto(); consent = ConsentGate(hosting: hosting); sendRaw(crypto!.hello)
        let buffered = early; early = []; for fragment in buffered { if ready { receive(fragment) } }
        timeout(180, "The connection request expired. Try again when you're both ready.") { $0.page != "chat" }
    }
    private func sendRaw(_ packet: Data) {
        do {
            guard ready else { throw ChatError.invalid("Bluetooth link closed") }
            let frames = try Framing.split(packet)
            guard frames.count + outgoing.count <= 512 else { throw ChatError.invalid("Bluetooth queue is full") }
            outgoing.append(contentsOf: frames); pump()
        } catch { fail("Couldn't send. Reconnect and try again.") }
    }
    private func send(_ object: [String: String]) throws {
        guard let crypto else { throw ChatError.invalid("Session unavailable") }; sendRaw(try crypto.seal(object))
    }
    private func pump() {
        guard ready, !writing else { return }
        if outgoing.isEmpty { let completion = afterSend; afterSend = nil; completion?(); return }
        if hosting {
            guard let hostTX, let subscriber else { return }
            while let frame = outgoing.first {
                if !peripheralManager.updateValue(frame, for: hostTX, onSubscribedCentrals: [subscriber]) { return }
                outgoing.removeFirst()
            }
            if outgoing.isEmpty { let completion = afterSend; afterSend = nil; completion?() }
        } else if let peer, let rx, let frame = outgoing.first {
            writing = true; peer.writeValue(frame, for: rx, type: .withResponse)
            let g = generation
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
                guard let self, self.generation == g, self.writing, self.outgoing.first == frame else { return }
                self.fail("Bluetooth stopped responding. Reconnect.")
            }
        }
    }
    private func receive(_ fragment: Data) {
        guard ready else {
            if peer != nil, !hosting, early.count < 8 { early.append(fragment) }
            else { fail("Unexpected Bluetooth data. Reconnect.") }
            return
        }
        do {
            guard let packet = try decoder.accept(fragment), let crypto, let consent else { return }
            if packet.first == 1 { fail("Install the latest ChitChat on both phones to use connection requests."); return }
            if packet.first == 3 {
                _ = try crypto.receiveHello(packet); try consent.keysReady()
                if hosting { status = "Waiting for a connection request…" }
                else { requestSent = true; rooms = []; status = "Waiting for the room owner to accept…"; try send(["t": "request", "name": localName]) }
                return
            }
            let o = try crypto.open(packet)
            switch o["t"] {
            case "request":
                try consent.receiveRequest()
                guard let name = o["name"] else { throw ChatError.invalid("Missing nickname") }
                friend = safeName(name); status = "Connection request from \(friend)."; incomingRequest = true
            case "accept":
                try consent.receiveAcceptance()
                guard let name = o["name"] else { throw ChatError.invalid("Missing nickname") }
                friend = safeName(name); try send(["t": "ready"]); maybeChat()
            case "ready":
                try consent.receiveReady(); maybeChat()
            case "decline":
                try consent.receiveDecline(); declinedResult = true
                let g = generation
                afterSend = { [weak self] in guard let self, self.generation == g else { return }; self.showDeclined() }
                try send(["t": "declined"])
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                    guard let self, self.generation == g, self.declinedResult else { return }; self.showDeclined()
                }
            case "declined":
                try consent.receiveDeclineAck()
                guard pendingReopen else { throw ChatError.invalid("Unexpected decline acknowledgment") }
                start("host")
            case "message":
                try consent.requireChat()
                guard page == "chat", let id = o["id"], UUID(uuidString: id) != nil, let text = o["text"], !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf8.count <= 1000, received.count < 500 else { throw ChatError.invalid("Invalid message") }
                if received.insert(id).inserted { messages.append(ChatMessage(id: id, text: text, own: false, receipt: Date().formatted(date: .omitted, time: .shortened))) }
                try send(["t": "ack", "id": id])
            case "ack":
                try consent.requireChat()
                guard page == "chat" else { throw ChatError.invalid("Approval required") }
                if let i = messages.firstIndex(where: { $0.own && $0.id == o["id"] }) { messages[i].receipt = "delivered ✓" }
            default: throw ChatError.invalid("Invalid packet")
            }
        } catch { fail("The connection request couldn't be completed. Try again with the latest ChitChat on both phones.") }
    }
    private var localName: String { let name = String(nickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(24)); return name.isEmpty ? "Sweet Tooth" : name }
    private func safeName(_ value: String) -> String {
        let name = String(value.filter { !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }.prefix(24))
        return name.isEmpty ? "Friend" : name
    }
    func acceptRequest() {
        do {
            guard let consent else { throw ChatError.invalid("Request unavailable") }
            try consent.accept(); incomingRequest = false; status = "Accepted. Opening your chat…"
            try send(["t": "accept", "name": localName])
        } catch { fail("Couldn't accept this request. Please try again.") }
    }
    func declineRequest() {
        do {
            guard let consent else { throw ChatError.invalid("Request unavailable") }
            try consent.decline(); incomingRequest = false; pendingReopen = true
            status = "Request declined. Reopening your room…"; try send(["t": "decline"])
            let g = generation
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                guard let self, self.generation == g, self.pendingReopen else { return }; self.start("host")
            }
        } catch { start("host") }
    }
    private func showDeclined() { leave(); error = "Request declined. The room owner declined your request. You can try another room." }
    private func maybeChat() { if consent?.canChat == true { requestPending = false; page = "chat" } }
    func sendText(_ text: String) -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard page == "chat", consent?.canChat == true, !clean.isEmpty else { return false }
        guard clean.utf8.count <= 1000 else { error = "This message is too long. Try a shorter note."; return false }
        guard messages.count < 500, messages.filter({ $0.own && $0.receipt != "delivered ✓" }).count < 8 else { error = "Wait for delivery, or start a new room if this session is full."; return false }
        let id = UUID().uuidString
        do {
            messages.append(ChatMessage(id: id, text: clean, own: true, receipt: "sending…")); try send(["t": "message", "id": id, "text": clean])
            let g = generation
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
                guard let self, self.generation == g, let i = self.messages.firstIndex(where: { $0.id == id && $0.receipt == "sending…" }) else { return }
                self.messages[i].receipt = "unconfirmed · reconnect if needed"
            }
            return page == "chat"
        } catch { fail("Message couldn't be sent. Reconnect."); return false }
    }
    private func timeout(_ seconds: Double, _ reason: String, condition: @escaping (ChatModel) -> Bool) {
        let g = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
            guard let self, self.generation == g, self.page != "home", condition(self) else { return }; self.fail(reason)
        }
    }
    func leave() {
        generation += 1; pending = ""; ready = false; writing = false; incomingRequest = false; requestPending = false; requestSent = false
        let previous = peer; peer = nil; previous?.delegate = nil
        if let previous { central?.cancelPeripheralConnection(previous) }
        central?.stopScan(); peripheralManager?.stopAdvertising(); peripheralManager?.removeAllServices()
        subscriber = nil; hostTX = nil; hostRX = nil; rx = nil; tx = nil; crypto = nil; consent = nil; afterSend = nil; pendingReopen = false; declinedResult = false
        outgoing = []; early = []; devices = [:]; rooms = []; messages = []; received = []; decoder = Framing(); page = "home"
        UIApplication.shared.isIdleTimerDisabled = false
    }
    private func disconnected(_ reason: String) {
        if declinedResult { showDeclined(); return }
        if hosting, consent != nil, consent?.canChat == false { start("host"); return }
        fail(reason)
    }
    private func fail(_ reason: String) { if declinedResult { showDeclined(); return }; leave(); error = reason + "\n\nThis session's messages have been cleared." }
}
