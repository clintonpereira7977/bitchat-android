/* FCM is optional until the owner's Firebase setup is complete. Never log tokens,
 * payloads, service account keys, OAuth responses or per-user identifiers. */
import { b64 } from './core.mjs';
const enc = new TextEncoder();
let cached;
export function invalidatePushCache() { cached = null; }
async function accessToken(raw, fetcher, now) {
  if (cached?.source === raw && cached.expires > now + 60000) return cached.token;
  const account = JSON.parse(raw);
  if (typeof account.project_id !== 'string' || !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(account.project_id) || typeof account.client_email !== 'string' || !account.client_email.endsWith('.iam.gserviceaccount.com') || typeof account.private_key !== 'string') throw Error('Invalid push configuration');
  const pem = account.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
  const bytes = Uint8Array.from(atob(pem), n => n.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', bytes, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']); bytes.fill(0);
  const seconds = Math.floor(now / 1000);
  const header = b64(enc.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64(enc.encode(JSON.stringify({ iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: seconds, exp: seconds + 3600 })));
  const unsigned = header + '.' + claims;
  const signature = b64(new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, enc.encode(unsigned))));
  const response = await fetcher('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: unsigned + '.' + signature }), signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw Error('Push authentication unavailable');
  const token = await response.json();
  if (typeof token.access_token !== 'string' || !Number.isFinite(token.expires_in)) throw Error('Push authentication unavailable');
  cached = { source: raw, token: token.access_token, project: account.project_id, expires: now + Math.min(token.expires_in, 3600) * 1000 };
  return cached.token;
}
export async function sendPush(env, token, kind, fetcher = fetch, now = Date.now()) {
  if (!env.FCM_SERVICE_ACCOUNT_JSON || !token || !['inbox', 'requests', 'contacts'].includes(kind)) return { sent: false, configured: Boolean(env.FCM_SERVICE_ACCOUNT_JSON) };
  try {
    const bearer = await accessToken(env.FCM_SERVICE_ACCOUNT_JSON, fetcher, now);
    const response = await fetcher(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(cached.project)}/messages:send`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + bearer }, signal: AbortSignal.timeout(5000),
      body: JSON.stringify({ message: { token, notification: { title: 'ChitChat', body: kind === 'requests' ? 'You have a new chat request.' : kind === 'contacts' ? 'Your connection request has an update.' : 'A new message is waiting.' }, data: { kind }, android: { priority: 'high', ttl: '86400s', collapse_key: 'chitchat-inbox' } } })
    });
    if (response.status === 401) invalidatePushCache();
    // A provider accepting a request is not proof that a notification was shown.
    return { sent: response.ok, configured: true };
  } catch { return { sent: false, configured: true }; }
}
