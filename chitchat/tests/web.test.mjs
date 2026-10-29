import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { Session, Decoder, GuestConsent, split, concat, utf8, SERVICE, RX, TX, nickname, uuid } from '../web/protocol.mjs';
import { GuestLink } from '../web/link.mjs';
let checks = 0;
const ok = (condition, reason) => { assert.ok(condition, reason); checks++; };
const wait = async (condition, reason) => { const start = Date.now(); while (!condition()) { if (Date.now() - start > 5000) throw Error('Timed out: ' + reason); await new Promise(resolve => setTimeout(resolve, 5)); } };
for (let length = 1; length <= 4096; length += 7) {
  const data = crypto.getRandomValues(new Uint8Array(length)), decoder = new Decoder(); let packet;
  for (const part of split(data)) { ok(part.length <= 20, 'Conservative MTU'); packet = decoder.accept(part, 123); }
  ok(Buffer.from(packet).equals(Buffer.from(data)), 'Framing round trip');
}
assert.throws(() => split(new Uint8Array(0))); assert.throws(() => split(new Uint8Array(4097)));
for (const bad of [new Uint8Array([1, 0, 0]), new Uint8Array([4, 0, 0, 1]), new Uint8Array([1, 0, 1, 1]), new Uint8Array([0, 0, 0, 1])]) assert.throws(() => new Decoder().accept(bad));
let decoder = new Decoder(), parts = split(new Uint8Array(50)); decoder.accept(parts[0], 0); assert.throws(() => decoder.accept(parts[1], 15001));
decoder = new Decoder(); decoder.accept(parts[0]); assert.throws(() => decoder.accept(parts[2]));
for (let i = 0; i < 10; i++) {
  const a = await Session.create(), b = await Session.create();
  ok(await a.receiveHello(b.hello()) === await b.receiveHello(a.hello()), 'Shared key fingerprint');
  const message = JSON.stringify({ t: 'message', text: 'नमस्ते 👋 <script>hello</script> café ' + i });
  const packet = await a.seal(message); ok(await b.open(packet) === message, 'Unicode encryption');
  await assert.rejects(() => b.open(packet)); await assert.rejects(() => a.open(packet));
  const tampered = await b.seal('no modification'); tampered[tampered.length - 1] ^= 1; await assert.rejects(() => a.open(tampered));
  await assert.rejects(() => a.receiveHello(b.hello())); a.destroy(); await assert.rejects(() => a.seal('closed')); b.destroy();
}
let a = await Session.create(); await assert.rejects(() => a.receiveHello(a.hello())); await assert.rejects(() => a.receiveHello(new Uint8Array(66))); a.destroy();
let consent = new GuestConsent(); assert.throws(() => consent.requireChat()); assert.throws(() => consent.accept()); consent.keysReady(); assert.throws(() => consent.keysReady()); consent.decline(); assert.throws(() => consent.accept()); assert.throws(() => consent.requireChat());
consent = new GuestConsent(); consent.keysReady(); consent.accept(); consent.requireChat(); assert.throws(() => consent.accept());
ok(nickname('\u0000 Sweet Tooth \n') === 'Sweet Tooth', 'Nickname sanitation'); ok(Array.from(nickname('👋'.repeat(50))).length === 24, 'Unicode nickname bound'); ok(/^[0-9a-f-]{36}$/.test(uuid()), 'Message identifier');

// Independent production Java implementation, not a JS-to-JS-only crypto test.
if (!process.env.CHITCHAT_JAVA_CLASSES) throw Error('Java interoperability classpath required');
for (let i = 0; i < 12; i++) {
  const child = spawn('java', ['-cp', process.env.CHITCHAT_JAVA_CLASSES, 'app.chitchat.InteropHarness'], { stdio: ['pipe', 'pipe', 'pipe'] });
  let errors = ''; child.stderr.on('data', bytes => { errors += bytes; });
  const lines = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
  const line = async () => { const result = await lines.next(); if (result.done) throw Error('Java protocol process ended: ' + errors); return result.value; };
  const js = await Session.create(), javaHello = new Uint8Array(Buffer.from(await line(), 'base64'));
  child.stdin.write(Buffer.from(js.hello()).toString('base64') + '\n');
  const fingerprint = await js.receiveHello(javaHello); ok(await line() === fingerprint, 'Java/JS P-256 and HKDF');
  const message = 'Hello from JavaScript. नमस्ते 👋 ' + i;
  child.stdin.write(Buffer.from(await js.seal(message)).toString('base64') + '\n');
  ok(await line() === message, 'Java opens browser ciphertext');
  ok(await js.open(new Uint8Array(Buffer.from(await line(), 'base64'))) === 'Hello from Java. नमस्ते 👋', 'Browser opens Java ciphertext');
  child.stdin.end(); await new Promise((resolve, reject) => { child.on('close', code => code === 0 ? resolve() : reject(Error(errors))); child.on('error', reject); }); js.destroy();
}

class Characteristic extends EventTarget {
  constructor(host, type) { super(); this.host = host; this.type = type; }
  async startNotifications() { this.host.notifications = this; this.host.notify(this.host.session.hello()); return this; }
  async writeValueWithResponse(frame) { ok(frame.length <= 20, 'BLE write bound'); await this.host.receive(frame); }
}
class Host {
  static async create() { const h = new Host(); h.session = await Session.create(); h.decoder = new Decoder(); h.controls = []; h.messages = []; h.ready = false; h.writeOrder = []; return h; }
  notify(packet) {
    for (const part of split(packet)) { this.notifications.value = new DataView(part.buffer, part.byteOffset, part.byteLength); this.notifications.dispatchEvent(new Event('characteristicvaluechanged')); }
  }
  async receive(frame) {
    const packet = this.decoder.accept(frame); if (!packet) return;
    if (packet[0] === 3) { this.writeOrder.push('hello'); await this.session.receiveHello(packet); return; }
    const object = JSON.parse(await this.session.open(packet)); this.writeOrder.push(object.t); this.controls.push(object);
    if (object.t === 'request') this.request = object;
    if (object.t === 'ready') this.ready = true;
    if (object.t === 'message') { assert.ok(this.ready); this.messages.push(object); await this.send({ t: 'ack', id: object.id }); }
  }
  async send(object) { this.notify(await this.session.seal(JSON.stringify(object))); }
  destroy() { this.session.destroy(); }
}
class Device extends EventTarget {
  constructor(host) {
    super(); this.host = host; this.rx = new Characteristic(host, RX); this.tx = new Characteristic(host, TX);
    this.gatt = { connected: false, connect: async () => { this.gatt.connected = true; return { getPrimaryService: async service => { assert.equal(service, SERVICE); return { getCharacteristic: async type => type === RX ? this.rx : this.tx }; } }; }, disconnect: () => { this.gatt.connected = false; } };
  }
}
const hosts = [], devices = [];
const bluetooth = { async requestDevice(options) { assert.deepEqual(options, { filters: [{ services: [SERVICE] }] }); const host = await Host.create(), device = new Device(host); hosts.push(host); devices.push(device); return device; } };
const link = new GuestLink(bluetooth); const events = { error: [], message: [], receipt: [], declined: [], ended: [] };
for (const type of Object.keys(events)) link.addEventListener(type, ({ detail }) => events[type].push(detail));
await assert.rejects(() => link.send('before approval'));
for (let cycle = 0; cycle < 5; cycle++) {
  await link.connect('Browser Friend'); await wait(() => link.state === 'pending', 'encrypted request');
  const host = hosts.at(-1); ok(host.request.name === 'Browser Friend', 'Request nickname delivered'); ok(host.writeOrder.join(',') === 'hello,request', 'Early host notifications cannot overtake own hello');
  ok(events.message.length === 0, 'No chat data before acceptance');
  await host.send({ t: 'accept', name: 'Android Friend' }); await wait(() => link.state === 'chat', 'owner acceptance'); ok(host.ready, 'Ready acknowledgment precedes chat UI');
  await link.send('hello-android-' + cycle); await wait(() => events.receipt.length === 1, 'delivery ack'); ok(host.messages.at(-1).text === 'hello-android-' + cycle, 'Browser-to-host message');
  const id = uuid(); await host.send({ t: 'message', id, text: 'hello-iphone-नमस्ते-👋' }); await wait(() => events.message.length === 2, 'host message'); await wait(() => host.controls.some(o => o.t === 'ack' && o.id === id), 'browser delivery ack');
  // Incoming plaintext is returned as data; the UI uses textContent, never HTML.
  ok(events.message.at(-1).text === 'hello-iphone-नमस्ते-👋', 'Host-to-browser Unicode');
  const oldTX = devices.at(-1).tx; link.close(); ok(link.session === null && link.received.size === 0 && link.receipts.size === 0, 'Close clears keys, receipts and history');
  oldTX.value = new DataView(new Uint8Array([3, 0, 0, 1]).buffer); oldTX.dispatchEvent(new Event('characteristicvaluechanged')); ok(link.state === 'home', 'Ignore late old-session notifications');
  for (const type of Object.keys(events)) events[type] = [];
}
await link.connect('Guest'); await wait(() => link.state === 'pending', 'decline request'); await hosts.at(-1).send({ t: 'decline' }); await wait(() => events.declined.length === 1, 'decline result'); ok(link.state === 'home' && hosts.at(-1).controls.some(o => o.t === 'declined'), 'Decline ACK then cleanup');
await link.connect('Guest'); await wait(() => link.state === 'pending', 'malicious early message'); await hosts.at(-1).send({ t: 'message', id: uuid(), text: 'before approval' }); await wait(() => link.state === 'home', 'reject unapproved message'); ok(events.error.length === 1, 'Unapproved messages fail closed');
await link.connect('Guest'); await wait(() => link.state === 'pending', 'cancellation'); link.close(); ok(link.state === 'home' && !devices.at(-1).gatt.connected, 'Cancel pending request disconnects');
link.close(); for (const host of hosts) host.destroy();
console.log(`PASS: ${checks} browser protocol/transport checks, 12 independent Java interop sessions, five encrypted reconnect cycles, pending/accept/decline/cancel, delivery, Unicode, replay/tamper and old callbacks`);
