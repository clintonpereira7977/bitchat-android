import { Session, Decoder, GuestConsent, split, SERVICE, RX, TX, utf8, nickname, uuid } from './protocol.mjs';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export class GuestLink extends EventTarget {
  constructor(bluetooth = globalThis.navigator?.bluetooth) { super(); this.bluetooth = bluetooth; this.epoch = 0; this.state = 'home'; }
  emit(type, detail) { this.dispatchEvent(new CustomEvent(type, { detail })); }
  phase(state, message) { this.state = state; this.emit('phase', { state, message }); }
  async connect(name) {
    if (this.state !== 'home') return;
    if (!this.bluetooth) { this.emit('error', 'Open this page in Bluefy on iPhone, or a browser with Bluetooth support.'); return; }
    this.close(); const epoch = this.epoch;
    this.name = nickname(name); this.decoder = new Decoder(); this.gate = new GuestConsent(); this.received = new Set(); this.receipts = new Set();
    this.writeChain = Promise.resolve(); this.readChain = Promise.resolve(); this.buffer = []; this.transportReady = false;
    this.phase('choosing', 'Choose your friend’s nearby Android room.');
    try {
      // Keep this call before the first await, while the button's user gesture is active.
      const device = await this.bluetooth.requestDevice({ filters: [{ services: [SERVICE] }] });
      if (epoch !== this.epoch) return;
      this.device = device;
      device.addEventListener('gattserverdisconnected', this.disconnected = () => {
        if (epoch !== this.epoch) return;
        const declined = this.declining; this.close();
        this.emit(declined ? 'declined' : 'ended', declined ? 'Your friend declined the request.' : 'Your friend disconnected. This session’s messages have been cleared.');
      });
      this.phase('connecting', 'Connecting over Bluetooth…');
      this.armTimeout(epoch, 25000, 'The connection took too long. Keep both apps open and try again.');
      const server = await device.gatt.connect(); this.ensure(epoch);
      const service = await server.getPrimaryService(SERVICE); this.ensure(epoch);
      this.rx = await service.getCharacteristic(RX); this.ensure(epoch);
      this.tx = await service.getCharacteristic(TX); this.ensure(epoch);
      const session = await Session.create();
      if (epoch !== this.epoch) { session.destroy(); return; } this.session = session;
      this.changed = event => {
        if (epoch !== this.epoch) return;
        const v = event.target.value;
        const frame = new Uint8Array(v.buffer, v.byteOffset, v.byteLength).slice();
        if (!this.transportReady) {
          if (this.buffer.length >= 8) this.fail('Unexpected Bluetooth data. Reconnect.'); else this.buffer.push(frame);
        } else this.queueFrame(frame, epoch);
      };
      this.tx.addEventListener('characteristicvaluechanged', this.changed);
      await this.tx.startNotifications(); this.ensure(epoch);
      // Own hello must be written before any encrypted request, including when
      // the host's hello arrives before startNotifications() resolves.
      await this.raw(session.hello(), epoch); this.ensure(epoch);
      this.transportReady = true; this.phase('handshake', 'Setting up encrypted chat…');
      for (const frame of this.buffer) this.queueFrame(frame, epoch); this.buffer = [];
    } catch (e) {
      if (epoch !== this.epoch) return;
      if (e.name === 'NotFoundError') { this.close(); return; }
      this.fail(e.name === 'NotAllowedError' || e.name === 'SecurityError' ? 'Allow Bluetooth for Bluefy in iPhone Settings, then try again.' : 'Couldn’t connect. Keep ChitChat open on Android and try a new room.');
    }
  }
  ensure(epoch) { if (epoch !== this.epoch) throw Error('Session ended'); }
  armTimeout(epoch, ms, message) {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => { if (epoch === this.epoch && this.state !== 'chat') this.fail(message); }, ms);
  }
  queueFrame(frame, epoch) {
    this.readChain = this.readChain.then(async () => { this.ensure(epoch); const packet = this.decoder.accept(frame); if (packet) await this.packet(packet, epoch); }).catch(() => { if (epoch === this.epoch) this.fail('The connection couldn’t be completed. Use the latest ChitChat on both phones and try again.'); });
  }
  raw(packet, epoch = this.epoch) {
    const parts = split(packet);
    this.queued = (this.queued || 0) + parts.length;
    if (this.queued > 512) return Promise.reject(Error('Bluetooth queue full'));
    this.writeChain = this.writeChain.then(async () => {
      for (const frame of parts) {
        this.ensure(epoch);
        let timer;
        try {
          const operation = typeof this.rx.writeValueWithResponse === 'function' ? this.rx.writeValueWithResponse(frame) : this.rx.writeValue(frame);
          await Promise.race([operation, new Promise((_, reject) => { timer = setTimeout(() => reject(Error('Bluetooth stopped responding')), 10000); })]);
          this.ensure(epoch); this.queued--;
        } finally { clearTimeout(timer); }
      }
    }); return this.writeChain;
  }
  async control(object, epoch) { const packet = await this.session.seal(JSON.stringify(object)); this.ensure(epoch); await this.raw(packet, epoch); }
  async packet(packet, epoch) {
    if (packet[0] === 3) {
      await this.session.receiveHello(packet); this.ensure(epoch); this.gate.keysReady();
      await this.control({ t: 'request', name: this.name }, epoch); this.ensure(epoch);
      this.phase('pending', 'Waiting for the room owner to accept…');
      this.armTimeout(epoch, 180000, 'Your request expired. Try again when you’re both ready.'); return;
    }
    const object = JSON.parse(await this.session.open(packet)); this.ensure(epoch);
    switch (object.t) {
      case 'accept':
        this.gate.accept(); if (typeof object.name !== 'string') throw Error('Missing name');
        this.friend = nickname(object.name); await this.control({ t: 'ready' }, epoch); this.ensure(epoch);
        clearTimeout(this.timer); this.phase('chat', this.friend); break;
      case 'decline':
        this.gate.decline(); this.declining = true;
        await this.control({ t: 'declined' }, epoch); this.ensure(epoch); this.close(); this.emit('declined', 'Your friend declined the request.'); break;
      case 'message': {
        this.gate.requireChat();
        if (!UUID_PATTERN.test(object.id) || typeof object.text !== 'string' || !object.text.trim() || utf8(object.text).length > 1000 || this.received.size >= 500) throw Error('Invalid message');
        if (!this.received.has(object.id)) { this.received.add(object.id); this.emit('message', { id: object.id, text: object.text, own: false }); }
        await this.control({ t: 'ack', id: object.id }, epoch); break;
      }
      case 'ack':
        this.gate.requireChat();
        if (this.receipts.delete(object.id)) this.emit('receipt', object.id); break;
      default: throw Error('Unexpected control');
    }
  }
  async send(text) {
    if (this.state !== 'chat') throw Error('Approval required');
    this.gate.requireChat(); const epoch = this.epoch, body = text.trim();
    if (!body) return;
    if (utf8(body).length > 1000) { this.emit('notice', 'Try a shorter note—up to 1,000 UTF-8 bytes.'); return; }
    if (this.receipts.size >= 8 || this.sentCount >= 500) { this.emit('notice', 'Wait for delivery, or open a fresh room.'); return; }
    const id = uuid(); this.receipts.add(id); this.sentCount = (this.sentCount || 0) + 1;
    this.emit('message', { id, text: body, own: true });
    try { await this.control({ t: 'message', id, text: body }, epoch); return id; }
    catch { if (epoch === this.epoch) this.fail('Bluetooth stopped responding. Open a new room to reconnect.'); }
  }
  fail(reason) { this.close(); this.emit('error', reason); }
  close() {
    this.epoch++; clearTimeout(this.timer);
    if (this.tx && this.changed) this.tx.removeEventListener('characteristicvaluechanged', this.changed);
    if (this.device && this.disconnected) this.device.removeEventListener('gattserverdisconnected', this.disconnected);
    try { this.device?.gatt.disconnect(); } catch {}
    this.session?.destroy(); this.session = null; this.gate = null; this.rx = null; this.tx = null; this.device = null;
    this.decoder?.reset(); this.buffer = []; this.queued = 0; this.sentCount = 0; this.declining = false;
    this.received?.clear(); this.receipts?.clear(); this.friend = ''; this.phase('home', '');
  }
}
