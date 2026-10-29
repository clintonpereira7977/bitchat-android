import { DurableObject } from 'cloudflare:workers';
import { ApiError, b64, hash, json, DeliveryStore } from './core.mjs';
import { sendPush } from './push.mjs';
const ALLOWED_HEADERS = 'Content-Type,X-ChitChat-Key,X-ChitChat-Time,X-ChitChat-Nonce,X-ChitChat-Signature,X-ChitChat-Invite';
const HOME = '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#000"><title>ChitChat delivery service</title><style>html{color-scheme:dark;background:#000;color:#fff;font:17px system-ui}body{max-width:420px;margin:12vh auto;padding:24px}small{font:12px monospace;color:#aaa}h1{font-size:48px;font-weight:300;letter-spacing:-2px}p{line-height:1.65;color:#bbb}a{color:#fff}hr{border:0;border-top:1px solid #333;margin:30px 0}</style><small>chitchat / online</small><h1>A little closer.<br>From anywhere.</h1><p>The ChitChat delivery service is online.</p><hr><p>This is the server address. Internet chat is being connected to the app; your existing Bluetooth app works separately.</p><a href="/v1/health">Check service status ↗</a></html>';
function cors(response) {
  if (response.status === 101) return response;
  const headers = new Headers(response.headers);
  headers.set('Access-Control-Allow-Origin', '*'); headers.set('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS'); headers.set('Access-Control-Allow-Headers', ALLOWED_HEADERS); headers.set('Access-Control-Max-Age', '600');
  headers.set('X-Content-Type-Options', 'nosniff'); headers.set('Referrer-Policy', 'no-referrer'); headers.set('Cache-Control', 'no-store');
  return new Response(response.body, { status: response.status, headers });
}
export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));
      if (request.method === 'GET' && url.pathname === '/') return cors(new Response(HOME, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'" } }));
      if (!url.pathname.startsWith('/v1/')) return cors(json({ error: 'not_found' }, 404));
      const forwarded = new Request(request);
      const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
      // Daily-scope hashed address for rate limiting, retained up to two days.
      // This is pseudonymous abuse-control metadata, not an anonymity claim.
      const ipHash = b64(await hash(new TextEncoder().encode(Math.floor(Date.now() / 86400000) + ':' + ip)));
      forwarded.headers.set('X-ChitChat-Internal-IP', ipHash);
      return cors(await env.DELIVERY.getByName('private-pilot-v1').fetch(forwarded));
    } catch { return cors(json({ error: 'service_unavailable' }, 503)); }
  }
};
export class DeliveryService extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env); this.env = env;
    this.initialized = ctx.blockConcurrencyWhile(async () => { this.store = new DeliveryStore(ctx.storage.sql, env, Date.now, (id, event) => this.notify(id, event)); });
  }
  async fetch(request) {
    await this.initialized;
    const path = new URL(request.url).pathname;
    if (path === '/v1/events') return this.socket(request);
    const result = await this.store.handle(request, request.headers.get('X-ChitChat-Internal-IP') || 'unknown');
    if (!(await this.ctx.storage.getAlarm())) await this.ctx.storage.setAlarm(Date.now() + 300000);
    return result;
  }
  async socket(request) {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'websocket_required' }, 426);
    try {
      this.store.admit(request.headers.get('X-ChitChat-Internal-IP') || 'unknown');
      const protocols = (request.headers.get('Sec-WebSocket-Protocol') || '').split(',').map(p => p.trim());
      const encoded = protocols.find(p => p.startsWith('ticket.'));
      if (!protocols.includes('chitchat.v1') || !encoded) throw new ApiError(401, 'authentication_required');
      const now = Date.now(), minute = Math.floor(now / 60000);
      this.store.quota('socket:' + (request.headers.get('X-ChitChat-Internal-IP') || 'unknown') + ':' + minute, 10, now + 120000);
      const account = await this.store.consumeTicket(encoded.slice(7));
      if (!account) throw new ApiError(401, 'authentication_required');
      for (const old of this.ctx.getWebSockets(account)) old.close(1000, 'Connection replaced');
      const pair = new WebSocketPair(); const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, [account]); server.serializeAttachment({ account, window: minute, pings: 0 });
      server.send(JSON.stringify({ type: 'connected' }));
      return new Response(null, { status: 101, webSocket: client, headers: { 'Sec-WebSocket-Protocol': 'chitchat.v1' } });
    } catch (error) { return json({ error: error instanceof ApiError ? error.code : 'authentication_required' }, error instanceof ApiError ? error.status : 401); }
  }
  notify(id, event) {
    for (const socket of this.ctx.getWebSockets(id)) try { socket.send(JSON.stringify(event)); if (event.type === 'account-deleted') socket.close(1000, 'Account deleted'); } catch { try { socket.close(1011, 'Reconnect'); } catch {} }
    if (event.type !== 'delivered') this.ctx.waitUntil(sendPush(this.env, this.store.pushToken(id), event.type));
  }
  webSocketMessage(socket, message) {
    if (typeof message !== 'string' || message !== 'ping') { socket.close(1008, 'Unsupported event'); return; }
    const attachment = socket.deserializeAttachment(), minute = Math.floor(Date.now() / 60000);
    if (!attachment) { socket.close(1008, 'Reconnect'); return; }
    if (attachment.window !== minute) { attachment.window = minute; attachment.pings = 0; }
    if (++attachment.pings > 10) { socket.close(1008, 'Too many pings'); return; }
    socket.serializeAttachment(attachment); socket.send('pong');
  }
  webSocketClose(socket) { try { socket.close(); } catch {} }
  webSocketError(socket) { try { socket.close(1011, 'Reconnect'); } catch {} }
  async alarm() {
    await this.initialized; this.store.purge();
    const work = ['messages', 'dedup', 'nonces', 'tickets', 'quotas'].some(table => this.store.first(`SELECT count(*) AS n FROM ${table}`).n > 0);
    if (work) await this.ctx.storage.setAlarm(Date.now() + 300000);
  }
}
