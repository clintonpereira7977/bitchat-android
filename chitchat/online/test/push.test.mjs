import test from 'node:test';
import assert from 'node:assert/strict';
import { sendPush, invalidatePushCache } from '../src/push.mjs';
import { b64 } from '../src/core.mjs';

test('notifications stay disabled until Firebase credentials are configured', async () => {
  const calls = []; const fetcher = (...args) => { calls.push(args); throw Error('No external requests expected'); };
  assert.deepEqual(await sendPush({}, 'synthetic-push-token', 'inbox', fetcher), { sent: false, configured: false });
  assert.equal((await sendPush({ FCM_SERVICE_ACCOUNT_JSON: 'invalid-json' }, 'synthetic-push-token', 'inbox', fetcher)).sent, false); assert.equal(calls.length, 0);
});
test('push uses data-only hints, caches OAuth safely, and never includes message ciphertext or plaintext', async () => {
  invalidatePushCache();
  const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  let binary = ''; for (const byte of pkcs8) binary += String.fromCharCode(byte);
  const account = { project_id: 'synthetic-chitchat', client_email: 'notifications@synthetic-chitchat.iam.gserviceaccount.com', private_key: '-----BEGIN PRIVATE KEY-----\n' + btoa(binary) + '\n-----END PRIVATE KEY-----' };
  const calls = [], now = Date.UTC(2026, 9, 29);
  const fetcher = async (url, options) => {
    calls.push({ url, options });
    if (url === 'https://oauth2.googleapis.com/token') return Response.json({ access_token: 'synthetic-oauth-token', expires_in: 3600 });
    return Response.json({ name: 'synthetic-success' });
  };
  const env = { FCM_SERVICE_ACCOUNT_JSON: JSON.stringify(account) };
  assert.equal((await sendPush(env, 'synthetic-push-token', 'inbox', fetcher, now)).sent, true);
  assert.equal((await sendPush(env, 'synthetic-push-token', 'requests', fetcher, now + 1000)).sent, true);
  assert.equal(calls.filter(c => c.url === 'https://oauth2.googleapis.com/token').length, 1);
  const notification = JSON.parse(calls[1].options.body).message;
  assert.deepEqual(notification.data, { kind: 'inbox' }); assert.equal(notification.notification, undefined);
  assert.deepEqual(Object.keys(notification).sort(), ['android', 'data', 'token']);
  assert.equal(notification.android.ttl, '86400s'); assert.equal(notification.android.collapse_key, 'chitchat-inbox');
  assert.ok(!calls[1].options.body.includes('private_key')); assert.ok(!calls[1].options.body.includes('envelope'));
  assert.equal(calls[1].options.headers.Authorization, 'Bearer synthetic-oauth-token');
  assert.equal((await sendPush(env, 'synthetic-push-token', 'delivered', fetcher, now)).sent, false); assert.equal(calls.length, 3);
  invalidatePushCache();
  const failed = async url => url.includes('oauth2') ? Response.json({ access_token: 'synthetic', expires_in: 3600 }) : new Response(null, { status: 503 });
  assert.equal((await sendPush(env, 'synthetic-push-token', 'inbox', failed, now)).sent, false);
  invalidatePushCache();
});
