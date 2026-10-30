/* ChitChat/v2: compatible with the shipped Android app. No external dependencies. */
const enc = new TextEncoder();
const dec = new TextDecoder('utf-8', { fatal: true });
export const SERVICE = 'c17c0001-5a7e-4c10-8a21-6d6573680001';
export const RX = 'c17c0002-5a7e-4c10-8a21-6d6573680001';
export const TX = 'c17c0003-5a7e-4c10-8a21-6d6573680001';
export const utf8 = value => enc.encode(value);
export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((sum, p) => sum + p.length, 0));
  let offset = 0; for (const part of parts) { out.set(part, offset); offset += part.length; } return out;
}
function less(a, b) { for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] < b[i]; return false; }
function same(a, b) { return a.length === b.length && a.every((n, i) => n === b[i]); }
const hex = bytes => Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('');
async function hmac(key, data) {
  const k = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, data));
}
export class Session {
  static async create() {
    const s = new Session();
    s.pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    s.publicKey = new Uint8Array(await crypto.subtle.exportKey('raw', s.pair.publicKey));
    s.seen = new Set(); s.alive = true; return s;
  }
  hello() { if (!this.alive) throw Error('Session closed'); return concat(new Uint8Array([3]), this.publicKey); }
  async receiveHello(packet) {
    if (!this.alive || this.key || packet.length !== 66 || packet[0] !== 3 || packet[1] !== 4) throw Error('Invalid handshake');
    const other = packet.slice(1); if (same(other, this.publicKey)) throw Error('Reflected handshake');
    const peer = await crypto.subtle.importKey('raw', other, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const secret = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: peer }, this.pair.privateKey, 256));
    const low = less(this.publicKey, other);
    const salt = new Uint8Array(await crypto.subtle.digest('SHA-256', low ? concat(this.publicKey, other) : concat(other, this.publicKey)));
    const prk = await hmac(salt, secret); const key = await hmac(prk, utf8('ChitChat/v2/session\x01'));
    const send = await hmac(key, utf8(low ? 'ChitChat/v2/low-to-high' : 'ChitChat/v2/high-to-low'));
    const receive = await hmac(key, utf8(low ? 'ChitChat/v2/high-to-low' : 'ChitChat/v2/low-to-high'));
    secret.fill(0); prk.fill(0);
    if (!this.alive) { key.fill(0); send.fill(0); receive.fill(0); throw Error('Session closed'); }
    const sendKey = await crypto.subtle.importKey('raw', send, 'AES-GCM', false, ['encrypt']);
    const receiveKey = await crypto.subtle.importKey('raw', receive, 'AES-GCM', false, ['decrypt']);
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', key));
    key.fill(0); send.fill(0); receive.fill(0);
    if (!this.alive) throw Error('Session closed');
    this.sendKey = sendKey; this.receiveKey = receiveKey; this.key = true;
    return hex(digest.slice(0, 6)).toUpperCase().match(/.{4}/g).join(' ');
  }
  async seal(text) {
    if (!this.alive || !this.key) throw Error('Handshake required');
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: utf8('ChitChat/v2'), tagLength: 128 }, this.sendKey, utf8(text)));
    if (!this.alive) throw Error('Session closed');
    return concat(new Uint8Array([2]), nonce, cipher);
  }
  async open(packet) {
    if (!this.alive || !this.key || packet.length < 29 || packet[0] !== 2) throw Error('Invalid encrypted packet');
    const nonce = packet.slice(1, 13), id = hex(nonce);
    if (this.seen.has(id) || this.seen.size >= 10000) throw Error('Replay or session limit');
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce, additionalData: utf8('ChitChat/v2'), tagLength: 128 }, this.receiveKey, packet.slice(13));
    if (!this.alive || this.seen.has(id)) throw Error('Session closed or replay');
    this.seen.add(id); return dec.decode(plain);
  }
  destroy() { this.alive = false; this.key = false; this.sendKey = null; this.receiveKey = null; this.pair = null; this.seen?.clear(); }
}
export function split(packet) {
  if (!packet.length || packet.length > 4096) throw Error('Packet size');
  const parts = [];
  for (let offset = 0, seq = 0; offset < packet.length; offset += 17, seq++) {
    const n = Math.min(17, packet.length - offset);
    parts.push(concat(new Uint8Array([(offset === 0 ? 1 : 0) | (offset + n === packet.length ? 2 : 0), seq >> 8, seq & 255]), packet.slice(offset, offset + n)));
  } return parts;
}
export class Decoder {
  reset() { this.parts = null; this.size = 0; this.next = 0; }
  accept(frame, now = performance.now()) {
    try {
      if (!frame || frame.length < 4 || frame.length > 20 || (frame[0] & ~3)) throw Error('Invalid fragment');
      const seq = (frame[1] << 8) | frame[2];
      if (frame[0] & 1) { this.reset(); if (seq !== 0) throw Error('Bad start'); this.parts = []; this.started = now; }
      if (!this.parts || seq !== this.next || now - this.started > 15000 || this.size + frame.length - 3 > 4096) throw Error('Incomplete packet');
      this.parts.push(frame.slice(3)); this.size += frame.length - 3; this.next++;
      if (frame[0] & 2) { const packet = concat(...this.parts); this.reset(); return packet; } return null;
    } catch (e) { this.reset(); throw e; }
  }
}
export class GuestConsent {
  constructor() { this.state = 'start'; }
  keysReady() { if (this.state !== 'start') throw Error('Duplicate handshake'); this.state = 'pending'; }
  accept() { if (this.state !== 'pending') throw Error('Unexpected acceptance'); this.state = 'chat'; }
  decline() { if (this.state !== 'pending') throw Error('Unexpected refusal'); this.state = 'declined'; }
  requireChat() { if (this.state !== 'chat') throw Error('Approval required'); }
}
export function nickname(value) { return Array.from(value.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').trim()).slice(0, 24).join('') || 'Sweet Tooth'; }
export function uuid() {
  const b = crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
  const h = hex(b); return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
