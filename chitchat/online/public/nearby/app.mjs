import { GuestLink } from './link.mjs';
import { nickname } from './protocol.mjs';
const $ = id => document.getElementById(id);
const link = new GuestLink(), receipts = new Map(); let toastTimer, wakeLock;
const savedName = (() => { try { return localStorage.getItem('chitchat.nickname') || ''; } catch { return ''; } })();
$('nickname').value = savedName;
$('nickname').addEventListener('change', () => { $('nickname').value = nickname($('nickname').value); try { localStorage.setItem('chitchat.nickname', $('nickname').value); } catch {} });
function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 6500); }
function clearChat() { for (const receipt of receipts.values()) clearTimeout(receipt.timer); receipts.clear(); $('messages').replaceChildren(); const hint = document.createElement('p'); hint.className = 'beginning'; hint.textContent = 'Just the two of you. Say something sweet.'; $('messages').append(hint); $('message').value = ''; $('message').style.height = ''; $('friend').textContent = 'Friend'; }
async function keepAwake(on) {
  try { if (on) { if (navigator.bluetooth?.setScreenDimEnabled) navigator.bluetooth.setScreenDimEnabled(false); if (navigator.wakeLock) wakeLock = await navigator.wakeLock.request('screen'); } else { navigator.bluetooth?.setScreenDimEnabled?.(true); await wakeLock?.release(); wakeLock = null; } } catch {}
}
link.addEventListener('phase', ({ detail: { state, message } }) => {
  $('home').hidden = state !== 'home'; $('waiting').hidden = state === 'home' || state === 'chat'; $('chat').hidden = state !== 'chat';
  $('section').textContent = state === 'chat' ? '/ conversation' : state === 'home' ? '/ nearby' : '/ your request';
  if (state === 'home') { clearChat(); keepAwake(false); if ($('confirm').open) $('confirm').close(); }
  else if (state === 'chat') { $('friend').textContent = message; }
  else { $('waiting-title').innerText = state === 'pending' ? 'Request sent.' : state === 'handshake' ? 'A little closer.' : 'Finding\nyour friend.'; $('waiting-status').textContent = message; keepAwake(true); }
});
link.addEventListener('message', ({ detail: message }) => {
  const list = $('messages'), nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 100;
  const block = document.createElement('div'); block.className = 'message' + (message.own ? ' own' : '');
  const bubble = document.createElement('div'); bubble.className = 'bubble'; bubble.textContent = message.text;
  const receipt = document.createElement('div'); receipt.className = 'receipt'; receipt.textContent = message.own ? 'sending…' : new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  block.append(bubble, receipt); list.append(block);
  if (message.own) { const timer = setTimeout(() => { if (receipts.has(message.id)) receipt.textContent = 'unconfirmed · reconnect if needed'; }, 20000); receipts.set(message.id, { element: receipt, timer }); }
  if (message.own || nearBottom) list.scrollTop = list.scrollHeight;
});
link.addEventListener('receipt', ({ detail: id }) => { const receipt = receipts.get(id); if (receipt) { clearTimeout(receipt.timer); receipt.element.textContent = 'delivered ✓'; receipts.delete(id); } });
for (const type of ['error', 'notice', 'ended', 'declined']) link.addEventListener(type, ({ detail }) => toast(detail));
$('connect').addEventListener('click', () => link.connect($('nickname').value));
$('cancel').addEventListener('click', () => link.close());
$('leave').addEventListener('click', () => $('confirm').showModal());
$('stay').addEventListener('click', () => $('confirm').close());
$('confirm-leave').addEventListener('click', () => { $('confirm').close(); link.close(); });
$('help').addEventListener('click', () => $('info').showModal());
$('composer').addEventListener('submit', async event => {
  event.preventDefault(); if (link.state !== 'chat' || !$('message').value.trim()) return;
  const text = $('message').value;
  if (new TextEncoder().encode(text.trim()).length > 1000) { toast('Try a shorter note—up to 1,000 UTF-8 bytes.'); return; }
  if (link.receipts.size >= 8 || link.sentCount >= 500) { toast('Wait for delivery, or open a fresh room.'); return; }
  $('message').value = ''; $('message').style.height = '';
  await link.send(text);
});
$('message').addEventListener('input', () => { $('message').style.height = 'auto'; $('message').style.height = Math.min($('message').scrollHeight, 130) + 'px'; });
function pageURL() { const url = new URL('./', location.href); return url.href; }
async function copyLink() { try { await navigator.clipboard.writeText(pageURL()); toast('Link copied. Paste it into Bluefy’s address bar.'); } catch { toast('Copy the address above, then paste it into Bluefy.'); } }
$('copy-link').addEventListener('click', copyLink);
$('share').addEventListener('click', async () => { try { if (navigator.share) await navigator.share({ title: 'ChitChat', text: 'A little closer. Chat nearby over Bluetooth. On iPhone, open this link in Bluefy.', url: pageURL() }); else await copyLink(); } catch {} });
const supported = Boolean(navigator.bluetooth && crypto?.subtle);
$('unsupported').hidden = supported; $('connect').hidden = !supported; $('first-tip').hidden = !supported;
window.addEventListener('pagehide', () => link.close());
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && link.state !== 'home') { link.close(); toast('You left the app. This session’s messages have been cleared.'); } });
window.addEventListener('beforeunload', event => { if (link.state === 'chat') { event.preventDefault(); event.returnValue = ''; } });
async function offline() {
  const label = $('offline-status');
  if (!('serviceWorker' in navigator) || !('caches' in window)) { label.textContent = 'Download once online. Keep this page open while offline.'; return; }
  try {
    await navigator.serviceWorker.register('./sw.js', { scope: './' }); await navigator.serviceWorker.ready;
    const cache = await caches.open('chitchat-bluefy-v1');
    const required = ['./', './index.html', './protocol.mjs', './link.mjs', './app.mjs', './style.css'];
    const ready = (await Promise.all(required.map(file => cache.match(new URL(file, location.href).href)))).every(Boolean);
    label.textContent = ready ? '● Offline copy ready. Bookmark this page in Bluefy.' : 'Keep this page open offline. Offline reopening isn’t ready.';
  } catch { label.textContent = 'Offline copy unavailable. Load this page online before chatting.'; }
}
offline();
