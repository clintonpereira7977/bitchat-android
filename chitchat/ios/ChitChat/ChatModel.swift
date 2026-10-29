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
    @Published var code = ""
    @Published var friend = "Friend"
    @Published var verification = false
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
    private var hosting = false, ready = false, writing = false, localApproved = false, peerApproved = false
    private var pending = "", roomCode = "", generation = 0
    private var outgoing: [Data] = []
    private var early: [Data] = []
    private var decoder = Framing()
    private var crypto: SessionCrypto?
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
        central.stopScan(); peer = device; device.delegate = self; status = "Connecting…"
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
        if peripheral === peer { fail("Your friend disconnected. Keep both apps open and reconnect.") }
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
        if characteristic === hostTX, subscriber?.identifier == central.identifier { fail("Your friend disconnected. Open a new room to reconnect.") }
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
        crypto = SessionCrypto(); sendRaw(crypto!.hello)
        let buffered = early; early = []; for fragment in buffered { if ready { receive(fragment) } }
        timeout(180, "Verification timed out. Start a new room when you're both ready.") { $0.page != "chat" }
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
        if hosting {
            guard let hostTX, let subscriber else { return }
            while let frame = outgoing.first {
                if !peripheralManager.updateValue(frame, for: hostTX, onSubscribedCentrals: [subscriber]) { return }
                outgoing.removeFirst()
            }
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
            guard let packet = try decoder.accept(fragment), let crypto else { return }
            if packet.first == 1 { code = try crypto.receiveHello(packet); verification = true; return }
            let o = try crypto.open(packet)
            switch o["t"] {
            case "approve":
                guard !peerApproved, let name = o["name"] else { throw ChatError.invalid("Duplicate approval") }
                peerApproved = true; friend = String(name.filter { !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }.prefix(24))
                if friend.isEmpty { friend = "Friend" }; maybeChat()
            case "message":
                guard page == "chat", let id = o["id"], UUID(uuidString: id) != nil, let text = o["text"], !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf8.count <= 1000, received.count < 500 else { throw ChatError.invalid("Invalid message") }
                if received.insert(id).inserted { messages.append(ChatMessage(id: id, text: text, own: false, receipt: Date().formatted(date: .omitted, time: .shortened))) }
                try send(["t": "ack", "id": id])
            case "ack":
                guard page == "chat" else { throw ChatError.invalid("Not verified") }
                if let i = messages.firstIndex(where: { $0.own && $0.id == o["id"] }) { messages[i].receipt = "delivered ✓" }
            default: throw ChatError.invalid("Invalid packet")
            }
        } catch { fail("This connection couldn't be verified or a packet was invalid. Start a new room.") }
    }
    func approve() {
        do { localApproved = true; try send(["t": "approve", "name": String(nickname.prefix(24))]); maybeChat() }
        catch { fail("Couldn't verify this connection.") }
    }
    private func maybeChat() {
        if localApproved && peerApproved { page = "chat" }
        else { status = "Waiting for your friend to compare the code…" }
    }
    func sendText(_ text: String) -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard page == "chat", !clean.isEmpty else { return false }
        guard clean.utf8.count <= 1000 else { error = "Keep it short: up to 1,000 UTF-8 bytes per message."; return false }
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
        generation += 1; pending = ""; ready = false; writing = false; verification = false
        let previous = peer; peer = nil; previous?.delegate = nil
        if let previous { central?.cancelPeripheralConnection(previous) }
        central?.stopScan(); peripheralManager?.stopAdvertising(); peripheralManager?.removeAllServices()
        subscriber = nil; hostTX = nil; hostRX = nil; rx = nil; tx = nil; crypto = nil; localApproved = false; peerApproved = false
        outgoing = []; early = []; devices = [:]; rooms = []; messages = []; received = []; decoder = Framing(); code = ""; page = "home"
        UIApplication.shared.isIdleTimerDisabled = false
    }
    private func fail(_ reason: String) { leave(); error = reason + "\n\nThis session's messages have been cleared." }
}
