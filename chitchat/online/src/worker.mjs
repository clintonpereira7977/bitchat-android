import { DurableObject } from 'cloudflare:workers';
import { ApiError, b64, hash, json, DeliveryStore } from './core.mjs';
import { sendPush } from './push.mjs';
const ALLOWED_HEADERS = 'Content-Type,X-ChitChat-Key,X-ChitChat-Time,X-ChitChat-Nonce,X-ChitChat-Signature,X-ChitChat-Invite';
const HOME = "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n  <meta name=\"theme-color\" content=\"#000000\">\n  <title>ChitChat — a little closer</title>\n  <style>\n    :root{color-scheme:dark;background:#000;color:#fff;font:17px system-ui,-apple-system,sans-serif}\n    *{box-sizing:border-box}body{max-width:500px;margin:0 auto;padding:34px 24px 50px}\n    header,small,.label{font:12px ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.04em}\n    header{padding:12px 0 22px;border-bottom:1px solid #383838}.label{color:#aaa;margin-top:38px}\n    h1{font-size:clamp(48px,12vw,64px);font-weight:300;line-height:1.12;letter-spacing:-2px;margin:20px 0}\n    h2{font-size:25px;font-weight:400;margin:0 0 14px}p,li{color:#bbb;line-height:1.65}\n    a{color:#fff;text-underline-offset:5px}.download{display:block;background:#fff;color:#000;border-radius:14px;padding:20px;text-decoration:none;font-weight:500;margin:28px 0 12px}\n    .meta{font-size:12px;color:#999}.modes{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:30px 0}\n    .mode{border:1px solid #444;border-radius:14px;padding:18px 14px}.mode strong{display:block;font-weight:400;margin-bottom:5px}.mode span{color:#aaa;font-size:13px}\n    section{border-top:1px solid #383838;padding-top:28px;margin-top:30px}ol{padding-left:22px}li{padding:5px 0 5px 4px}footer{margin-top:34px;color:#999;font-size:12px}\n    a:focus-visible{outline:2px solid #fff;outline-offset:5px}\n  </style>\n</head>\n<body>\n  <header>chitchat / near + far</header>\n  <p class=\"label\">ANDROID · PRIVATE PILOT</p>\n  <h1>Small talk.<br>Any distance.</h1>\n  <p>A little closer. A little quieter.<br>A nickname, a friend, and a fresh page.</p>\n  <a class=\"download\" href=\"https://raw.githubusercontent.com/clintonpereira7977/bitchat-android/refs/heads/codex/chitchat-download/downloads/ChitChat-0.3-beta1.apk\">Get the Android beta &nbsp; ↗</a>\n  <p class=\"meta\">ChitChat 0.3 beta · Android 8 or newer · APK download</p>\n  <div class=\"modes\">\n    <div class=\"mode\"><strong>Nearby</strong><span>Direct Bluetooth.<br>Both apps open.</span></div>\n    <div class=\"mode\"><strong>Online</strong><span>Across the internet.<br>Connect by ChitChat ID.</span></div>\n  </div>\n  <section id=\"install\">\n    <h2>A little hello, in five steps.</h2>\n    <ol>\n      <li>Download the APK on each Android phone. Open it from <strong>Files / Downloads</strong> and allow installation when Android asks.</li>\n      <li>Choose <strong>Online</strong>. Enter a nickname and the private pilot invitation supplied by the person inviting you.</li>\n      <li>Share your public eight-character <strong>ChitChat ID</strong>. Your friend enters it under <strong>Add a friend</strong>.</li>\n      <li>Accept the connection request, then open the contact and say hello.</li>\n      <li>Enable optional notifications in Online mode. Android may ask for notification permission.</li>\n    </ol>\n    <p class=\"meta\">If an older Bluetooth-only test APK causes a signature conflict, uninstall that old build first. Once you have an Online ID, uninstalling or clearing app data loses it. This pilot has no account recovery.</p>\n  </section>\n  <section>\n    <h2>A fresh page.</h2>\n    <p>Opened messages and drafts clear from this phone when you leave or background the app. Unopened messages can wait encrypted for up to 24 hours. Each person controls their own copy.</p>\n    <p class=\"meta\">Messages are encrypted on the phones. This pilot has not had an independent security audit. Notifications require Google Play services and depend on the phone and network.</p>\n  </section>\n  <section>\n    <h2>On iPhone?</h2>\n    <p>For nearby Bluetooth chats, open ChitChat in the free Bluefy browser on your iPhone. An Android phone hosts the room.</p><p><a href=\"/nearby/\">Open iPhone Bluetooth option ↗</a></p><p class=\"meta\">The Online iPhone version is still being prepared. The Android APK above installs on Android.</p>\n  </section>\n  <footer>\n    <a href=\"/v1/health\">Service status ↗</a>\n    &nbsp; · &nbsp;\n    <a href=\"https://github.com/clintonpereira7977/bitchat-android/tree/codex/chitchat-online/chitchat\">Source &amp; pilot notes ↗</a>\n  </footer>\n</body>\n</html>\n";
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
      if (request.method === 'GET' && url.pathname === '/nearby') return Response.redirect(url.origin + '/nearby/', 302);
      if (request.method === 'GET' && url.pathname === '/nearby/' && env.ASSETS) return env.ASSETS.fetch(new Request(new URL('/nearby/index.html', url), request));
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
