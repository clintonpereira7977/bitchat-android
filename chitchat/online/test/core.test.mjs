import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { DeliveryStore, DAY, b64, unb64, signingText, hash, contactCode } from '../src/core.mjs';
const enc = new TextEncoder();
function fixture(env = {}) {
  const db = new DatabaseSync(':memory:');
  const sql = { exec(query, ...args) { const statement = db.prepare(query); if (statement.columns().length) return statement.all(...args); statement.run(...args); return []; } };
  const f = { now: Date.UTC(2026, 9, 29, 12), events: [], db };
  f.store = new DeliveryStore(sql, { PILOT_INVITE_CODE: 'synthetic-invite-only', ...env }, () => f.now, (id, event) => f.events.push({ account: id, ...event }));
  return f;
}
async function actor() {
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey)), digest = await hash(raw);
  return { keys, publicKey: b64(raw), id: b64(digest), code: contactCode(digest) };
}
async function signed(f, who, method, path, input, extra = {}) {
  const body = input === undefined ? '' : typeof input === 'string' ? input : JSON.stringify(input);
  const timestamp = String(f.now), nonce = b64(crypto.getRandomValues(new Uint8Array(16)));
  const signature = b64(new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, who.keys.privateKey, await signingText(method, 'https://chitchat.test' + path, timestamp, nonce, body))));
  return new Request('https://chitchat.test' + path, { method, headers: { 'Content-Type': 'application/json', 'X-ChitChat-Key': who.publicKey, 'X-ChitChat-Time': timestamp, 'X-ChitChat-Nonce': nonce, 'X-ChitChat-Signature': signature, ...extra }, ...(body ? { body } : {}) });
}
async function call(f, who, method, path, input, extra = {}) {
  const response = await f.store.handle(await signed(f, who, method, path, input, extra), 'synthetic-address');
  return { status: response.status, body: await response.json() };
}
async function register(f, who) { const r = await call(f, who, 'POST', '/v1/register', { name: 'Sweet Tooth', keyBundle: { protocol: 'synthetic-test-only', public: b64(crypto.getRandomValues(new Uint8Array(32))) } }, { 'X-ChitChat-Invite': 'synthetic-invite-only' }); assert.equal(r.status, 201, JSON.stringify(r)); return r.body.account; }
async function friends(f, a, b) { assert.equal((await call(f, a, 'POST', '/v1/requests', { code: b.code })).status, 201); assert.equal((await call(f, b, 'POST', `/v1/requests/${a.id}/accept`, {})).status, 200); }
const envelope = () => b64(crypto.getRandomValues(new Uint8Array(160)));

test('deployment starts healthy and closed to registration without its pilot secret', async () => {
  const f = fixture({ PILOT_INVITE_CODE: '' }), a = await actor();
  const response = await f.store.handle(new Request('https://chitchat.test/v1/health'));
  const health = await response.json(); assert.equal(health.registrationConfigured, false); assert.equal(health.pushConfigured, false); assert.equal(health.appIntegration, 'android-beta1');
  assert.equal((await call(f, a, 'POST', '/v1/register', { name: 'A', keyBundle: {} })).status, 503); f.db.close();
});
test('notification validation and self-tests are authenticated, account-bound, rate-limited and disclose only safe codes', async () => {
  const f = fixture({ FCM_SERVICE_ACCOUNT_JSON: 'synthetic-config-only' }), a = await actor(), b = await actor();await register(f,a);await register(f,b);
  const calls=[];let result={sent:true,code:'ACCEPTED'};
  f.store.pushSender=async (token,kind,validateOnly)=>{calls.push({token,kind,validateOnly});return result;};
  assert.equal((await f.store.handle(new Request('https://chitchat.test/v1/push/test',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}))).status,401);
  assert.equal((await call(f,b,'POST','/v1/push/test',{})).status,409);assert.equal(calls.length,0);
  assert.equal((await call(f,a,'PUT','/v1/push',{token:'synthetic-owner-device-token'})).status,200);assert.equal(calls[0].validateOnly,true);
  let status=await call(f,a,'GET','/v1/push/status');assert.equal(status.body.result,'ACCEPTED');assert.equal(status.body.validationOnly,true);assert.equal(JSON.stringify(status).includes('synthetic-owner-device-token'),false);
  assert.equal((await call(f,b,'GET','/v1/push/status')).body.registered,false);
  result={sent:false,code:'PERMISSION_DENIED',secret:'PRIVATE_PROVIDER_DETAILS'};
  let test=await call(f,a,'POST','/v1/push/test',{token:'someone-elses-device-token'});assert.equal(test.body.result,'PERMISSION_DENIED');assert.equal(test.body.accepted,false);assert.equal(calls[1].token,'synthetic-owner-device-token');assert.equal(calls[1].validateOnly,false);assert.equal(JSON.stringify(test).includes('PRIVATE_PROVIDER_DETAILS'),false);
  assert.equal(f.store.health.pushLastResult,'PERMISSION_DENIED');
  await call(f,a,'POST','/v1/push/test',{});await call(f,a,'POST','/v1/push/test',{});assert.equal((await call(f,a,'POST','/v1/push/test',{})).status,429);
  result={sent:false,code:'SENDER_ID_MISMATCH'};
  let setup=await call(f,a,'PUT','/v1/push',{token:'synthetic-new-device-token'});assert.equal(setup.status,503);assert.equal(setup.body.pushResult,'SENDER_ID_MISMATCH');
  await call(f,a,'DELETE','/v1/push');assert.equal((await call(f,a,'GET','/v1/push/status')).body.result,'NOT_CHECKED');assert.equal((await call(f,a,'GET','/v1/push/status')).body.registered,false);
  f.db.close();
});
test('late push callbacks cannot restore deleted registrations and diagnostic state expires after one day', async () => {
  const f=fixture({ FCM_SERVICE_ACCOUNT_JSON:'synthetic-config-only' }),a=await actor();await register(f,a);
  f.store.pushSender=async()=>({sent:true,code:'ACCEPTED'});await call(f,a,'PUT','/v1/push',{token:'synthetic-owner-device-token'});
  let finish;f.store.pushSender=()=>new Promise(resolve=>{finish=resolve;});const pending=f.store.deliverPush(a.id,'inbox');
  await call(f,a,'DELETE','/v1/push');finish({sent:true,code:'ACCEPTED'});await pending;assert.equal(f.store.first('SELECT count(*) AS n FROM push_status').n,0);
  f.store.pushSender=async()=>({sent:true,code:'ACCEPTED'});f.now+=120000;await call(f,a,'PUT','/v1/push',{token:'synthetic-owner-device-token'});
  f.now+=DAY+1;f.store.purge();assert.equal(f.store.first('SELECT count(*) AS n FROM push_status').n,0);
  await call(f,a,'DELETE','/v1/me');assert.equal(f.store.pushToken(a.id),null);f.db.close();
});
test('signatures authenticate keys and bind the method, body, path and clock; requests cannot replay', async () => {
  const f = fixture(), a = await actor(), b = await actor(); await register(f, a);
  assert.equal((await f.store.handle(new Request('https://chitchat.test/v1/me'))).status, 401);
  const request = await signed(f, a, 'GET', '/v1/me');
  assert.equal((await f.store.handle(request.clone())).status, 200); assert.equal((await f.store.handle(request.clone())).status, 409);
  const original = await signed(f, a, 'PUT', '/v1/me', { name: 'A', keyBundle: {} });
  assert.equal((await f.store.handle(new Request(original.url, { method: 'PUT', headers: original.headers, body: JSON.stringify({ name: 'Attacker', keyBundle: {} }) }))).status, 401);
  const changedPath = await signed(f, a, 'GET', '/v1/me'); assert.equal((await f.store.handle(new Request('https://chitchat.test/v1/contacts', { headers: changedPath.headers }))).status, 401);
  const crossOrigin = await signed(f, a, 'GET', '/v1/me'); assert.equal((await f.store.handle(new Request('https://other-service.test/v1/me', { headers: crossOrigin.headers }))).status, 401);
  assert.equal((await call(f, b, 'GET', '/v1/me')).status, 401);
  const stale = await signed(f, a, 'GET', '/v1/me'); f.now += 300001; assert.equal((await f.store.handle(stale)).status, 401); f.db.close();
});
test('registration requires an invite, validates public-only key material, enforces account cap, returns safe profiles', async () => {
  const f = fixture({ MAX_ACCOUNTS: '1' }), a = await actor(), b = await actor();
  assert.equal((await call(f, a, 'POST', '/v1/register', { name: 'A', keyBundle: {} })).status, 403);
  assert.equal((await call(f, a, 'POST', '/v1/register', { name: 'A', keyBundle: { privateKey: 'never publish' } }, { 'X-ChitChat-Invite': 'synthetic-invite-only' })).status, 400);
  await register(f, a);
  const me = await call(f, a, 'GET', '/v1/me'); assert.equal(me.body.account.code.length, 8); assert.equal(me.body.account.id, a.id); assert.equal('push_token' in me.body.account, false);
  assert.equal((await call(f, b, 'POST', '/v1/register', { name: 'B', keyBundle: {} }, { 'X-ChitChat-Invite': 'synthetic-invite-only' })).status, 429); f.db.close();
});
test('contact approval is required; requests can be declined, accepted and expire', async () => {
  const f = fixture(), a = await actor(), b = await actor(); await register(f, a); await register(f, b);
  assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: envelope() })).status, 403);
  await call(f, a, 'POST', '/v1/requests', { code: b.code });
  assert.equal((await call(f, b, 'GET', '/v1/requests')).body.requests[0].account.id, a.id);
  assert.equal((await call(f, a, 'POST', `/v1/requests/${a.id}/accept`, {})).status, 400);
  assert.equal((await call(f, b, 'POST', `/v1/requests/${a.id}/decline`, {})).status, 200);
  assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: envelope() })).status, 403);
  await friends(f, a, b);
  assert.equal((await call(f, a, 'GET', '/v1/contacts')).body.contacts[0].id, b.id);
  assert.equal((await call(f, b, 'GET', '/v1/contacts')).body.contacts[0].id, a.id);
  const c = await actor(); await register(f, c); await call(f, c, 'POST', '/v1/requests', { code: b.code }); f.now += DAY + 1;
  assert.equal((await call(f, b, 'GET', '/v1/requests')).body.requests.length, 0); assert.equal((await call(f, b, 'POST', `/v1/requests/${c.id}/accept`, {})).status, 404); f.db.close();
});
test('opaque messages await the recipient, are isolated across inboxes and delete only after recipient ACK', async () => {
  const f = fixture(), a = await actor(), b = await actor(), c = await actor(); for (const who of [a, b, c]) await register(f, who); await friends(f, a, b);
  const id = crypto.randomUUID(), payload = envelope();
  assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id, envelope: payload })).status, 202);
  assert.equal((await call(f, a, 'GET', '/v1/inbox')).body.messages.length, 0);
  assert.equal((await call(f, c, 'GET', '/v1/inbox')).body.messages.length, 0);
  let inbox = (await call(f, b, 'GET', '/v1/inbox')).body.messages; assert.equal(inbox.length, 1); assert.equal(inbox[0].envelope, payload);
  await call(f, c, 'POST', `/v1/messages/${id}/ack`, {}); assert.equal((await call(f, b, 'GET', '/v1/inbox')).body.messages.length, 1);
  await call(f, b, 'POST', `/v1/messages/${id}/ack`, {}); assert.equal((await call(f, b, 'GET', '/v1/inbox')).body.messages.length, 0);
  assert.equal(f.store.first('SELECT count(*) AS n FROM messages').n, 0); assert.ok(f.events.some(e => e.account === a.id && e.type === 'delivered')); f.db.close();
});
test('message retries remain idempotent, including after delivery; IDs cannot replace another ciphertext', async () => {
  const f = fixture(), a = await actor(), b = await actor(); await register(f, a); await register(f, b); await friends(f, a, b);
  const input = { to: b.id, id: crypto.randomUUID(), envelope: envelope() };
  const first = await call(f, a, 'POST', '/v1/messages', input); f.now += 1000;
  const retry = await call(f, a, 'POST', '/v1/messages', input); assert.equal(retry.body.expires, first.body.expires);
  assert.equal(f.store.first('SELECT count(*) AS n FROM messages').n, 1);
  assert.equal((await call(f, a, 'POST', '/v1/messages', { ...input, envelope: envelope() })).status, 409);
  await call(f, b, 'POST', `/v1/messages/${input.id}/ack`, {});
  assert.equal((await call(f, a, 'POST', '/v1/messages', input)).body.state, 'delivered'); assert.equal(f.store.first('SELECT count(*) AS n FROM messages').n, 0); f.db.close();
});
test('24-hour expiry is enforced even without the background alarm and removes payloads and dedup metadata', async () => {
  const f = fixture(), a = await actor(), b = await actor(); await register(f, a); await register(f, b); await friends(f, a, b);
  await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: envelope() });
  f.now += DAY - 1; assert.equal((await call(f, b, 'GET', '/v1/inbox')).body.messages.length, 1);
  f.now += 2; assert.equal((await call(f, b, 'GET', '/v1/inbox')).body.messages.length, 0);
  assert.equal(f.store.first('SELECT count(*) AS n FROM messages').n, 0); assert.equal(f.store.first('SELECT count(*) AS n FROM dedup').n, 0); f.db.close();
});
test('blocking removes queued messages and stops future requests and sends in both directions', async () => {
  const f = fixture(), a = await actor(), b = await actor(); await register(f, a); await register(f, b); await friends(f, a, b);
  await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: envelope() }); await call(f, b, 'POST', `/v1/contacts/${a.id}/block`, {});
  assert.equal((await call(f, a, 'GET', '/v1/contacts')).body.contacts.length, 0); assert.equal((await call(f, b, 'GET', '/v1/inbox')).body.messages.length, 0);
  assert.equal((await call(f, a, 'POST', '/v1/requests', { code: b.code })).status, 403);
  assert.equal((await call(f, b, 'POST', '/v1/messages', { to: a.id, id: crypto.randomUUID(), envelope: envelope() })).status, 403); f.db.close();
});
test('pilot quotas bound stored messages and queued payload sizes', async () => {
  const f = fixture({ ACCOUNT_DAILY_MESSAGE_LIMIT: '1', INBOX_MESSAGE_LIMIT: '1' }), a = await actor(), b = await actor(); await register(f, a); await register(f, b); await friends(f, a, b);
  const tooLarge = b64(new Uint8Array(8193)); assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: tooLarge })).status, 400);
  const id = crypto.randomUUID(); assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id, envelope: envelope() })).status, 202);
  assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: envelope() })).status, 429);
  await call(f, b, 'POST', `/v1/messages/${id}/ack`, {}); assert.equal((await call(f, a, 'POST', '/v1/messages', { to: b.id, id: crypto.randomUUID(), envelope: envelope() })).status, 429); f.db.close();
});
test('push tokens never appear in public lookup, profiles, contacts or inbox responses', async () => {
  const f = fixture(), a = await actor(), b = await actor(); await register(f, a); await register(f, b); await friends(f, a, b);
  const token = 'synthetic-private-push-token-not-real'; await call(f, b, 'PUT', '/v1/push', { token }); assert.equal(f.store.pushToken(b.id), token);
  for (const path of ['/v1/me', '/v1/contacts', '/v1/inbox', '/v1/lookup/' + b.code]) assert.ok(!JSON.stringify((await call(f, a, 'GET', path)).body).includes(token));
  await call(f, b, 'DELETE', '/v1/push'); assert.equal(f.store.pushToken(b.id), null); f.db.close();
});
test('WebSocket tickets are single use and expire; deleting an account removes owned data and credentials', async () => {
  const f = fixture(), a = await actor(); await register(f, a);
  let ticket = (await call(f, a, 'POST', '/v1/socket-ticket', {})).body.ticket;
  assert.equal(await f.store.consumeTicket(ticket), a.id); assert.equal(await f.store.consumeTicket(ticket), null);
  ticket = (await call(f, a, 'POST', '/v1/socket-ticket', {})).body.ticket; f.now += 60001; assert.equal(await f.store.consumeTicket(ticket), null);
  await call(f, a, 'PUT', '/v1/push', { token: 'synthetic-private-push-token-not-real' });
  assert.equal((await call(f, a, 'DELETE', '/v1/me')).status, 200); assert.equal(f.store.first('SELECT count(*) AS n FROM accounts').n, 0); assert.equal((await call(f, a, 'GET', '/v1/me')).status, 401); f.db.close();
});
test('malformed bodies and encodings fail without leaking raw payloads', async () => {
  const f = fixture(), a = await actor(); await register(f, a);
  assert.equal((await call(f, a, 'PUT', '/v1/me', '{broken-json')).status, 400);
  assert.equal((await call(f, a, 'PUT', '/v1/me', { name: 'A', keyBundle: {} }, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await call(f, a, 'PUT', '/v1/me', { name: 'A'.repeat(18000), keyBundle: {} })).status, 413);
  assert.throws(() => unb64('not+base64')); assert.throws(() => unb64('A')); assert.equal(contactCode(new Uint8Array(32)), 'AAAAAAAA'); f.db.close();
});
