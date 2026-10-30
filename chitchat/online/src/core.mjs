/* Pilot delivery service. Payloads are opaque; encryption belongs in the clients.
 * No private decryption keys, message contents in logs, analytics or account passwords.
 * The relay protocol is not an end-to-end encryption protocol or an audited messenger.
 */
export const DAY = 86400000;
const enc = new TextEncoder();
const decode = new TextDecoder('utf-8', { fatal: true });
const ID = /^[A-Za-z0-9_-]{43}$/;
const MESSAGE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CODE = /^[A-HJ-NP-Z2-9]{8}$/;
const JSON_HEADERS = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
export function json(value, status = 200) { return new Response(JSON.stringify(value), { status, headers: JSON_HEADERS }); }
export function b64(bytes) { let text = ''; for (const n of bytes) text += String.fromCharCode(n); return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }
export function unb64(text, min = 0, max = 16384) {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_-]+$/.test(text) || text.length % 4 === 1 || text.length > Math.ceil(max * 4 / 3) + 2) throw new ApiError(400, 'invalid_encoding');
  let bytes;
  try { bytes = Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - text.length % 4) % 4)), n => n.charCodeAt(0)); } catch { throw new ApiError(400, 'invalid_encoding'); }
  if (bytes.length < min || bytes.length > max || b64(bytes) !== text) throw new ApiError(400, 'invalid_encoding'); return bytes;
}
export async function hash(bytes) { return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)); }
export class ApiError extends Error { constructor(status, code) { super(code); this.status = status; this.code = code; } }
function require(value, status = 400, code = 'invalid_request') { if (!value) throw new ApiError(status, code); }
function number(env, key, fallback, maximum) { const value = Number(env[key] ?? fallback); return Number.isSafeInteger(value) && value >= 1 && value <= maximum ? value : fallback; }
function displayName(value) { require(typeof value === 'string'); const name = Array.from(value.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').trim()).slice(0, 24).join(''); return name || 'Friend'; }
function publicBundle(value) {
  require(value && typeof value === 'object' && !Array.isArray(value)); const string = JSON.stringify(value); require(enc.encode(string).length <= 4096);
  const inspect = node => { if (!node || typeof node !== 'object') return; for (const [key, part] of Object.entries(node)) { require(!['d', 'privatekey', 'secretkey', 'password', 'recoverykey'].includes(key.toLowerCase()), 400, 'public_keys_only'); inspect(part); } };
  inspect(value); return string;
}
function profile(row) { return { id: row.id, code: row.code, name: row.name, identityKey: row.identity_key, keyBundle: JSON.parse(row.key_bundle) }; }
export function contactCode(digest) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; let bits = 0, buffer = 0, result = '';
  for (const byte of digest) { buffer = (buffer << 8) | byte; bits += 8; while (bits >= 5 && result.length < 8) { bits -= 5; result += alphabet[(buffer >>> bits) & 31]; } if (result.length === 8) return result; }
  return result;
}
export async function signingText(method, path, timestamp, nonce, body) {
  return enc.encode(['ChitChat-delivery/v1', method, path, timestamp, nonce, b64(await hash(enc.encode(body)))].join('\n'));
}
export async function verifyRequest(request, body, now) {
  const keyText = request.headers.get('X-ChitChat-Key'), timestamp = request.headers.get('X-ChitChat-Time'), nonce = request.headers.get('X-ChitChat-Nonce'), signature = request.headers.get('X-ChitChat-Signature');
  require(timestamp && /^\d{13}$/.test(timestamp) && Math.abs(now - Number(timestamp)) <= 300000, 401, 'authentication_required');
  let raw, signed;
  try { raw = unb64(keyText, 65, 65); require(raw[0] === 4); unb64(nonce, 16, 16); signed = unb64(signature, 64, 64); } catch { throw new ApiError(401, 'authentication_required'); }
  let valid = false;
  try {
    const key = await crypto.subtle.importKey('raw', raw, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const url = new URL(request.url);
    valid = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, signed, await signingText(request.method, url.origin + url.pathname + url.search, timestamp, nonce, body));
  } catch {}
  require(valid, 401, 'authentication_required');
  const digest = await hash(raw); return { id: b64(digest), code: contactCode(digest), key: keyText, nonce };
}
export async function readBody(request) {
  if (!request.body) return '';
  const reader = request.body.getReader(); const parts = []; let size = 0;
  try { while (true) { const { done, value } = await reader.read(); if (done) break; size += value.byteLength; if (size > 16384) { await reader.cancel(); throw new ApiError(413, 'request_too_large'); } parts.push(value); } }
  finally { reader.releaseLock(); }
  const all = new Uint8Array(size); let offset = 0; for (const p of parts) { all.set(p, offset); offset += p.length; }
  try { return decode.decode(all); } catch { throw new ApiError(400, 'invalid_json'); }
}
export class DeliveryStore {
  constructor(sql, env = {}, clock = Date.now, emit = () => {}) {
    this.sql = sql; this.env = env; this.clock = clock; this.emit = emit;
    this.exec('CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, code TEXT UNIQUE NOT NULL, identity_key TEXT NOT NULL, name TEXT NOT NULL, key_bundle TEXT NOT NULL, push_token TEXT, created INTEGER NOT NULL)');
    this.exec('CREATE TABLE IF NOT EXISTS dedup (id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL, digest TEXT NOT NULL, expires INTEGER NOT NULL)');
    this.exec('CREATE INDEX IF NOT EXISTS dedup_expiry ON dedup(expires)');
    this.exec('CREATE TABLE IF NOT EXISTS nonces (account TEXT NOT NULL, nonce TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(account, nonce))');
    this.exec('CREATE INDEX IF NOT EXISTS nonces_expiry ON nonces(expires)');
    this.exec('CREATE TABLE IF NOT EXISTS contacts (sender TEXT NOT NULL, recipient TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(sender, recipient))');
    this.exec('CREATE INDEX IF NOT EXISTS contacts_recipient ON contacts(recipient,state)');
    this.exec('CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL, envelope TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL)');
    this.exec('CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient,created,id)');
    this.exec('CREATE INDEX IF NOT EXISTS messages_expiry ON messages(expires)');
    this.exec('CREATE TABLE IF NOT EXISTS quotas (bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL)');
    this.exec('CREATE TABLE IF NOT EXISTS tickets (hash TEXT PRIMARY KEY, account TEXT NOT NULL, expires INTEGER NOT NULL)');
    this.exec('CREATE INDEX IF NOT EXISTS tickets_expiry ON tickets(expires)');
  }
  exec(query, ...parameters) { return this.sql.exec(query, ...parameters); }
  rows(query, ...parameters) { return [...this.exec(query, ...parameters)]; }
  first(query, ...parameters) { return this.rows(query, ...parameters)[0]; }
  purge() {
    const now = this.clock();
    for (const table of ['messages', 'dedup', 'nonces', 'quotas', 'tickets']) this.exec(`DELETE FROM ${table} WHERE expires <= ?`, now);
    this.exec("DELETE FROM contacts WHERE state = 'pending' AND created <= ?", now - DAY);
  }
  quota(bucket, limit, expires) {
    const previous = this.first('SELECT count FROM quotas WHERE bucket = ?', bucket);
    require(!previous || previous.count < limit, 429, 'pilot_limit_reached');
    this.exec('INSERT INTO quotas(bucket,count,expires) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET count=count+1', bucket, expires);
  }
  allowed(sender, recipient) {
    const rows = this.rows('SELECT sender,recipient,state FROM contacts WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)', sender, recipient, recipient, sender);
    if (rows.some(row => row.state === 'blocked')) return false;
    return rows.some(row => row.state === 'accepted');
  }
  get health() { return { service: 'ChitChat delivery', version: '0.1.1', status: 'ok', queueTTLSeconds: DAY / 1000, registrationConfigured: Boolean(this.env.PILOT_INVITE_CODE), pushConfigured: Boolean(this.env.FCM_SERVICE_ACCOUNT_JSON), pushPayload: 'data-only-v1', appIntegration: 'android-beta1' }; }
  admit(ipHash) {
    const now = this.clock(), day = Math.floor(now / DAY), minute = Math.floor(now / 60000);
    this.purge();
    this.quota(`global:${day}`, number(this.env, 'DAILY_REQUEST_LIMIT', 20000, 50000), (day + 2) * DAY);
    this.quota(`ip:${ipHash}:${minute}`, 120, now + 120000);
  }
  async handle(request, ipHash = 'unknown') {
    try { return await this.route(request, ipHash); }
    catch (e) { return json({ error: e instanceof ApiError ? e.code : 'service_error' }, e instanceof ApiError ? e.status : 500); }
  }
  async route(request, ipHash) {
    const url = new URL(request.url), path = url.pathname, now = this.clock(), day = Math.floor(now / DAY), minute = Math.floor(now / 60000);
    if (request.method === 'GET' && path === '/v1/health') return json({ ...this.health, serverTime: now });
    this.admit(ipHash);
    const body = await readBody(request);
    const registering = request.method === 'POST' && path === '/v1/register';
    if (registering) this.quota(`registration:${ipHash}:${day}`, 10, (day + 2) * DAY);
    const actor = await verifyRequest(request, body, now);
    const existing = this.first('SELECT * FROM accounts WHERE id = ?', actor.id);
    require(registering || existing, 401, 'authentication_required');
    require(!this.first('SELECT nonce FROM nonces WHERE account = ? AND nonce = ?', actor.id, actor.nonce), 409, 'request_replayed');
    this.exec('INSERT INTO nonces(account,nonce,expires) VALUES(?,?,?)', actor.id, actor.nonce, now + 600001);
    this.quota(`account:${actor.id}:${minute}`, 60, now + 120000);
    let input;
    if (['POST', 'PUT'].includes(request.method)) {
      require(request.headers.get('Content-Type')?.split(';')[0].trim() === 'application/json', 415, 'json_required');
      try { input = JSON.parse(body); } catch { throw new ApiError(400, 'invalid_json'); }
      require(input && typeof input === 'object' && !Array.isArray(input));
    }
    if (registering) {
      if (existing) return json({ account: profile(existing) });
      require(this.env.PILOT_INVITE_CODE, 503, 'pilot_registration_not_configured');
      const invite = request.headers.get('X-ChitChat-Invite') || '';
      require(invite.length <= 256, 403, 'invite_required');
      const expected = await hash(enc.encode(this.env.PILOT_INVITE_CODE)), provided = await hash(enc.encode(invite));
      let difference = 0; for (let i = 0; i < expected.length; i++) difference |= expected[i] ^ provided[i];
      require(difference === 0, 403, 'invite_required');
      // Recheck after crypto awaits. DO requests may interleave at await points.
      const registered = this.first('SELECT * FROM accounts WHERE id = ?', actor.id);
      if (registered) return json({ account: profile(registered) });
      require(this.first('SELECT count(*) AS n FROM accounts').n < number(this.env, 'MAX_ACCOUNTS', 100, 1000), 429, 'pilot_account_limit');
      require(!this.first('SELECT id FROM accounts WHERE code = ?', actor.code), 409, 'contact_code_collision');
      const name = displayName(input.name), bundle = publicBundle(input.keyBundle);
      this.exec('INSERT INTO accounts(id,code,identity_key,name,key_bundle,created) VALUES(?,?,?,?,?,?)', actor.id, actor.code, actor.key, name, bundle, now);
      return json({ account: profile(this.first('SELECT * FROM accounts WHERE id = ?', actor.id)) }, 201);
    }
    if (request.method === 'GET' && path === '/v1/me') return json({ account: profile(existing) });
    if (request.method === 'DELETE' && path === '/v1/me') {
      this.exec('DELETE FROM messages WHERE sender=? OR recipient=?', actor.id, actor.id);
      this.exec('DELETE FROM dedup WHERE sender=? OR recipient=?', actor.id, actor.id);
      this.exec('DELETE FROM contacts WHERE sender=? OR recipient=?', actor.id, actor.id);
      this.exec('DELETE FROM nonces WHERE account=?', actor.id); this.exec('DELETE FROM tickets WHERE account=?', actor.id);
      this.exec('DELETE FROM accounts WHERE id=?', actor.id); this.emit(actor.id, { type: 'account-deleted' }); return json({ deleted: true });
    }
    if (request.method === 'PUT' && path === '/v1/me') {
      const name = displayName(input.name), bundle = publicBundle(input.keyBundle);
      this.exec('UPDATE accounts SET name = ?, key_bundle = ? WHERE id = ?', name, bundle, actor.id);
      return json({ account: profile(this.first('SELECT * FROM accounts WHERE id = ?', actor.id)) });
    }
    const lookup = path.match(/^\/v1\/lookup\/([A-Z2-9]{8})$/);
    if (request.method === 'GET' && lookup) {
      require(CODE.test(lookup[1])); this.quota(`lookup:${actor.id}:${day}`, 30, (day + 2) * DAY);
      const found = this.first('SELECT * FROM accounts WHERE code = ?', lookup[1]); require(found, 404, 'not_found');
      return json({ account: profile(found) });
    }
    if (request.method === 'POST' && path === '/v1/requests') {
      require(typeof input.code === 'string' && CODE.test(input.code));
      const recipient = this.first('SELECT id FROM accounts WHERE code = ?', input.code); require(recipient && recipient.id !== actor.id, 404, 'not_found');
      const state = this.rows('SELECT sender,state FROM contacts WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)', actor.id, recipient.id, recipient.id, actor.id);
      require(!state.some(row => row.state === 'blocked'), 403, 'contact_not_available');
      if (state.some(row => row.state === 'accepted')) return json({ state: 'accepted' });
      if (state.some(row => row.sender === actor.id && row.state === 'pending')) return json({ state: 'pending' });
      this.quota(`requests:${actor.id}:${day}`, 10, (day + 2) * DAY);
      this.exec("INSERT INTO contacts(sender,recipient,state,created) VALUES(?,?,'pending',?) ON CONFLICT(sender,recipient) DO UPDATE SET state='pending',created=excluded.created", actor.id, recipient.id, now);
      this.emit(recipient.id, { type: 'requests' }); return json({ state: 'pending' }, 201);
    }
    if (request.method === 'GET' && path === '/v1/requests') {
      const requests = this.rows("SELECT a.*,c.created AS requested_at FROM contacts c JOIN accounts a ON a.id=c.sender WHERE c.recipient=? AND c.state='pending' AND c.created > ? ORDER BY c.created DESC LIMIT 100", actor.id, now - DAY);
      return json({ requests: requests.map(row => ({ account: profile(row), requestedAt: row.requested_at })) });
    }
    const decision = path.match(/^\/v1\/requests\/([A-Za-z0-9_-]{43})\/(accept|decline)$/);
    if (request.method === 'POST' && decision) {
      const [, sender, action] = decision; require(sender !== actor.id);
      const pending = this.first("SELECT state FROM contacts WHERE sender=? AND recipient=? AND state='pending' AND created > ?", sender, actor.id, now - DAY);
      require(pending, 404, 'not_found');
      require(!this.first("SELECT state FROM contacts WHERE sender=? AND recipient=? AND state='blocked'", actor.id, sender), 403, 'contact_not_available');
      this.exec('UPDATE contacts SET state=? WHERE sender=? AND recipient=?', action === 'accept' ? 'accepted' : 'declined', sender, actor.id);
      this.emit(sender, { type: 'contacts' }); return json({ state: action === 'accept' ? 'accepted' : 'declined' });
    }
    if (request.method === 'GET' && path === '/v1/contacts') {
      const rows = this.rows("SELECT * FROM contacts WHERE state='accepted' AND (sender=? OR recipient=?) LIMIT 200", actor.id, actor.id);
      const ids = [...new Set(rows.map(row => row.sender === actor.id ? row.recipient : row.sender))];
      return json({ contacts: ids.filter(id => this.allowed(actor.id, id)).map(id => profile(this.first('SELECT * FROM accounts WHERE id=?', id))) });
    }
    const block = path.match(/^\/v1\/contacts\/([A-Za-z0-9_-]{43})\/block$/);
    if (request.method === 'POST' && block) {
      const other = block[1]; require(other !== actor.id && this.first('SELECT id FROM accounts WHERE id=?', other), 404, 'not_found');
      this.exec("INSERT INTO contacts(sender,recipient,state,created) VALUES(?,?,'blocked',?) ON CONFLICT(sender,recipient) DO UPDATE SET state='blocked',created=excluded.created", actor.id, other, now);
      this.exec('DELETE FROM messages WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)', actor.id, other, other, actor.id);
      this.emit(other, { type: 'contacts' }); return json({ state: 'blocked' });
    }
    if (request.method === 'PUT' && path === '/v1/push') {
      require(typeof input.token === 'string' && input.token.length >= 20 && input.token.length <= 4096 && !/[\s\u0000-\u001f]/.test(input.token));
      this.exec('UPDATE accounts SET push_token=? WHERE id=?', input.token, actor.id); return json({ registered: true, pushConfigured: this.health.pushConfigured });
    }
    if (request.method === 'DELETE' && path === '/v1/push') { this.exec('UPDATE accounts SET push_token=NULL WHERE id=?', actor.id); return json({ registered: false }); }
    if (request.method === 'POST' && path === '/v1/messages') {
      require(typeof input.to === 'string' && ID.test(input.to) && typeof input.id === 'string' && MESSAGE_ID.test(input.id));
      const envelope = input.envelope; unb64(envelope, 48, 8192);
      const digest = b64(await hash(enc.encode(envelope)));
      require(this.allowed(actor.id, input.to), 403, 'contact_approval_required');
      const duplicate = this.first('SELECT * FROM dedup WHERE id=?', input.id);
      if (duplicate) {
        require(duplicate.sender === actor.id && duplicate.recipient === input.to && duplicate.digest === digest, 409, 'message_id_conflict');
        return json({ id: duplicate.id, expires: duplicate.expires, state: this.first('SELECT id FROM messages WHERE id=?', input.id) ? 'queued' : 'delivered' }, 202);
      }
      require(this.first('SELECT count(*) AS n FROM messages').n < number(this.env, 'GLOBAL_QUEUE_LIMIT', 5000, 10000), 429, 'queue_full');
      require(this.first('SELECT count(*) AS n FROM messages WHERE recipient=?', input.to).n < number(this.env, 'INBOX_MESSAGE_LIMIT', 200, 500), 429, 'inbox_full');
      this.quota(`messages:${day}`, number(this.env, 'DAILY_MESSAGE_LIMIT', 5000, 10000), (day + 2) * DAY);
      this.quota(`sent:${actor.id}:${day}`, number(this.env, 'ACCOUNT_DAILY_MESSAGE_LIMIT', 100, 500), (day + 2) * DAY);
      this.exec('INSERT INTO messages(id,sender,recipient,envelope,created,expires) VALUES(?,?,?,?,?,?)', input.id, actor.id, input.to, envelope, now, now + DAY);
      this.exec('INSERT INTO dedup(id,sender,recipient,digest,expires) VALUES(?,?,?,?,?)', input.id, actor.id, input.to, digest, now + DAY);
      this.emit(input.to, { type: 'inbox' }); return json({ id: input.id, expires: now + DAY, state: 'queued' }, 202);
    }
    if (request.method === 'GET' && path === '/v1/inbox') {
      const messages = this.rows('SELECT id,sender,envelope,created,expires FROM messages WHERE recipient=? AND expires > ? ORDER BY created,id LIMIT 20', actor.id, now);
      return json({ messages });
    }
    const ack = path.match(/^\/v1\/messages\/([0-9a-f-]{36})\/ack$/i);
    if (request.method === 'POST' && ack) {
      const message = this.first('SELECT sender FROM messages WHERE id=? AND recipient=? AND expires > ?', ack[1], actor.id, now);
      if (message) { this.exec('DELETE FROM messages WHERE id=? AND recipient=?', ack[1], actor.id); this.emit(message.sender, { type: 'delivered', id: ack[1] }); }
      return json({ acknowledged: true });
    }
    if (request.method === 'POST' && path === '/v1/socket-ticket') {
      const ticket = b64(crypto.getRandomValues(new Uint8Array(32))); const ticketHash = b64(await hash(enc.encode(ticket)));
      this.exec('INSERT INTO tickets(hash,account,expires) VALUES(?,?,?)', ticketHash, actor.id, now + 60000);
      return json({ ticket, expires: now + 60000 });
    }
    throw new ApiError(404, 'not_found');
  }
  async consumeTicket(ticket) {
    try { unb64(ticket, 32, 32); } catch { return null; }
    const key = b64(await hash(enc.encode(ticket))), now = this.clock();
    const row = this.first('SELECT account FROM tickets WHERE hash=? AND expires > ?', key, now);
    this.exec('DELETE FROM tickets WHERE hash=?', key); return row?.account || null;
  }
  pushToken(account) { return this.first('SELECT push_token FROM accounts WHERE id=?', account)?.push_token || null; }
}
