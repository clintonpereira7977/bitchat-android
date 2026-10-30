/* Runs only against a disposable local workerd process in CI. No cloud account,
 * real device, real push token, or external Google request is used. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { b64, signingText } from '../src/core.mjs';
const origin = 'http://127.0.0.1:8787';
const persistence = await mkdtemp(join(tmpdir(), 'chitchat-relay-'));
const child = spawn('node_modules/.bin/wrangler', ['dev', '--local', '--ip', '127.0.0.1', '--port', '8787', '--persist-to', persistence, '--var', 'PILOT_INVITE_CODE:synthetic-runtime-invite'], { env: { ...process.env, WRANGLER_SEND_METRICS: 'false', CI: 'true' }, stdio: ['ignore', 'pipe', 'pipe'] });
let log = ''; const sockets = [];
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { log = (log + chunk).slice(-12000); });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function wait(condition, label) { const end = Date.now() + 15000; while (Date.now() < end) { if (await condition()) return; await pause(25); } throw Error('Timed out: ' + label); }
async function actor() { const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']); return { keys, raw: b64(new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey))) }; }
async function request(who, method, path, input, extra = {}) {
  const body = input === undefined ? '' : JSON.stringify(input), timestamp = String(Date.now()), nonce = b64(crypto.getRandomValues(new Uint8Array(16)));
  const signature = b64(new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, who.keys.privateKey, await signingText(method, origin + path, timestamp, nonce, body))));
  const response = await fetch(origin + path, { method, headers: { 'Content-Type': 'application/json', 'X-ChitChat-Key': who.raw, 'X-ChitChat-Time': timestamp, 'X-ChitChat-Nonce': nonce, 'X-ChitChat-Signature': signature, ...extra }, ...(body ? { body } : {}) });
  return { status: response.status, body: await response.json(), headers: response.headers };
}
async function register(who) { const r = await request(who, 'POST', '/v1/register', { name: 'Synthetic Friend', keyBundle: { protocol: 'synthetic-only', public: b64(crypto.getRandomValues(new Uint8Array(32))) } }, { 'X-ChitChat-Invite': 'synthetic-runtime-invite' }); assert.equal(r.status, 201, JSON.stringify(r.body)); who.account = r.body.account; }
async function connect(who) {
  const result = await request(who, 'POST', '/v1/socket-ticket', {}); assert.equal(result.status, 200, JSON.stringify(result.body));
  const events = []; const socket = new WebSocket(origin.replace('http:', 'ws:') + '/v1/events', ['chitchat.v1', 'ticket.' + result.body.ticket]); sockets.push(socket);
  socket.addEventListener('message', event => events.push(event.data === 'pong' ? 'pong' : JSON.parse(event.data)));
  await Promise.race([once(socket, 'open'), new Promise((_, reject) => setTimeout(() => reject(Error('Socket open timeout')), 10000))]);
  await wait(() => events.some(e => e.type === 'connected'), 'socket setup'); return { socket, events, ticket: result.body.ticket };
}
try {
  await wait(async () => { if (child.exitCode !== null) throw Error('workerd exited: ' + log); try { return (await fetch(origin + '/v1/health')).ok; } catch { return false; } }, 'local runtime');
  const home = await fetch(origin + '/'); assert.equal(home.status, 200); assert.ok((await home.text()).includes('Get the Android beta'));
  const health = await (await fetch(origin + '/v1/health')).json(); assert.equal(health.appIntegration, 'android-beta1'); assert.equal(health.pushConfigured, false); assert.equal(health.pushPayload, 'data-only-v1');
  const nearby = await fetch(origin + '/nearby/'); assert.equal(nearby.status, 200); assert.ok((await nearby.text()).includes('Small talk.'));
  const module = await fetch(origin + '/nearby/protocol.mjs'); assert.equal(module.status, 200); assert.ok((await module.text()).includes('ChitChat/v2'));
  const guide = await fetch(origin + '/nearby/install.html'); assert.equal(guide.status, 200); assert.equal(guide.redirected, false);
  const iphone = await fetch(origin + '/nearby/downloads/ChitChat-iPhone-unsigned.ipa'); assert.equal(iphone.status, 200); assert.ok((await iphone.arrayBuffer()).byteLength > 100000);
  const preflight = await fetch(origin + '/v1/messages', { method: 'OPTIONS', headers: { Origin: 'https://synthetic-client.test', 'Access-Control-Request-Method': 'POST' } }); assert.equal(preflight.status, 204); assert.equal(preflight.headers.get('Access-Control-Allow-Origin'), '*');
  const a = await actor(), b = await actor(); await register(a); await register(b);
  const pushSetup=await request(a,'PUT','/v1/push',{token:'synthetic-runtime-device-token'});assert.equal(pushSetup.status,200);
  const pushStatus=await request(a,'GET','/v1/push/status');assert.equal(pushStatus.body.registered,true);assert.equal(pushStatus.body.result,'NOT_CHECKED');
  const pushTest=await request(a,'POST','/v1/push/test',{});assert.equal(pushTest.body.accepted,false);assert.equal(pushTest.body.result,'NOT_CONFIGURED');
  const owner = await connect(b), sender = await connect(a);
  let r = await request(a, 'POST', '/v1/requests', { code: b.account.code }); assert.equal(r.status, 201);
  await wait(() => owner.events.some(e => e.type === 'requests'), 'request notification');
  r = await request(b, 'POST', `/v1/requests/${a.account.id}/accept`, {}); assert.equal(r.status, 200);
  const id = crypto.randomUUID(), envelope = b64(crypto.getRandomValues(new Uint8Array(100)));
  r = await request(a, 'POST', '/v1/messages', { to: b.account.id, id, envelope }); assert.equal(r.status, 202, JSON.stringify(r.body));
  await wait(() => owner.events.some(e => e.type === 'inbox'), 'inbox event');
  r = await request(b, 'GET', '/v1/inbox'); assert.equal(r.body.messages.length, 1); assert.equal(r.body.messages[0].envelope, envelope);
  await request(b, 'POST', `/v1/messages/${id}/ack`, {}); await wait(() => sender.events.some(e => e.type === 'delivered' && e.id === id), 'delivery receipt');
  assert.equal((await request(b, 'GET', '/v1/inbox')).body.messages.length, 0);
  owner.socket.send('ping'); await wait(() => owner.events.includes('pong'), 'hibernation-compatible heartbeat');
  const reused = new WebSocket(origin.replace('http:', 'ws:') + '/v1/events', ['chitchat.v1', 'ticket.' + owner.ticket]); sockets.push(reused);
  let opened = false; reused.addEventListener('open', () => { opened = true; }); await Promise.race([once(reused, 'error').catch(() => {}), pause(1500)]); assert.equal(opened, false, 'Used socket ticket cannot authenticate a second connection');
  assert.equal((await request(a, 'DELETE', '/v1/me')).status, 200);
  console.log('PASS: actual Cloudflare workerd/SQLite migration, signed registration, contact approval, opaque queue, recipient ACK/deletion, WebSocket events, single-use tickets, CORS and service status');
} catch (e) {
  console.error('Cloudflare runtime check failed:', String(e));
  // These are local synthetic runtime logs, never production logs or credentials.
  console.error(log.split('\n').filter(line => /error|exception|failed|not found|unknown|invalid/i.test(line)).slice(-20).join('\n'));
  process.exitCode = 1;
} finally {
  for (const socket of sockets) try { socket.close(); } catch {}
  child.kill('SIGTERM'); await Promise.race([once(child, 'exit'), pause(3000)]); if (child.exitCode === null) child.kill('SIGKILL');
  await rm(persistence, { recursive: true, force: true });
}
