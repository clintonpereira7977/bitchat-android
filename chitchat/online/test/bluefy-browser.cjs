/* Synthetic UI checks. This fixture never ships with the public client. */
const { chromium } = require(process.env.CHITCHAT_PLAYWRIGHT || 'playwright');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHITCHAT_CHROMIUM || undefined, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const output = path.resolve('dist/web-ui'); fs.mkdirSync(output, { recursive: true });
  const origin = process.env.CHITCHAT_WEB_ORIGIN || 'http://127.0.0.1:4173';
  const errors = [];
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, isMobile: true, hasTouch: true });
  await context.addInitScript(() => {
    class FakeCharacteristic extends EventTarget {
      constructor(host) { super(); this.host = host; }
      async startNotifications() { this.host.notifications = this; this.host.notify(this.host.session.hello()); return this; }
      async writeValueWithResponse(frame) { await this.host.receive(new Uint8Array(frame)); }
    }
    class FakeDevice extends EventTarget {
      constructor(host) {
        super(); this.host = host; this.rx = new FakeCharacteristic(host); this.tx = new FakeCharacteristic(host);
        this.gatt = { connected: false, connect: async () => { this.gatt.connected = true; return { getPrimaryService: async () => ({ getCharacteristic: async type => type.includes('0002-') ? this.rx : this.tx }) }; }, disconnect: () => { this.gatt.connected = false; } };
      }
    }
    const bluetooth = {
      async requestDevice() {
        const { Session, Decoder, split } = await import(new URL('./protocol.mjs', location.href).href);
        const host = { session: await Session.create(), decoder: new Decoder(), ready: false,
          notify(packet) { for (const frame of split(packet)) { this.notifications.value = new DataView(frame.buffer, frame.byteOffset, frame.byteLength); this.notifications.dispatchEvent(new Event('characteristicvaluechanged')); } },
          async send(object) { this.notify(await this.session.seal(JSON.stringify(object))); },
          async receive(frame) { const packet = this.decoder.accept(frame); if (!packet) return; if (packet[0] === 3) { await this.session.receiveHello(packet); return; } const o = JSON.parse(await this.session.open(packet)); if (o.t === 'request') this.request = o; if (o.t === 'ready') this.ready = true; if (o.t === 'message') { if (!this.ready) throw Error('Early message'); this.message = o; await this.send({ t: 'ack', id: o.id }); } }
        };
        window.syntheticHost = host; window.syntheticDevice = new FakeDevice(host); return window.syntheticDevice;
      }
    };
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: bluetooth });
  });
  const page = await context.newPage(); page.on('pageerror', e => errors.push(String(e)));
  await page.goto(origin); await page.getByRole('heading', { name: 'Small talk. Zero signal.' }).waitFor();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.getByRole('textbox', { name: 'Your nickname' }).fill('Sweet Tooth'); await page.getByRole('textbox', { name: 'Your nickname' }).blur();
  await page.screenshot({ path: path.join(output, '01-bluefy-home.png'), fullPage: true });
  await page.getByRole('button', { name: 'Find a friend' }).click(); await page.getByRole('heading', { name: 'Request sent.' }).waitFor();
  assert.equal(await page.locator('#composer').isVisible(), false);
  await page.screenshot({ path: path.join(output, '02-bluefy-request.png'), fullPage: true });
  await page.evaluate(() => syntheticHost.send({ t: 'accept', name: 'Sweet Tooth' }));
  await page.locator('#composer').waitFor({ state: 'visible' });
  await page.getByRole('textbox', { name: 'Your message' }).fill('a little closer');
  await page.evaluate(() => syntheticHost.send({ t: 'message', id: crypto.randomUUID(), text: 'zero signal. still here. 👋' }));
  await page.getByText('zero signal. still here. 👋', { exact: true }).waitFor();
  assert.equal(await page.getByRole('textbox', { name: 'Your message' }).inputValue(), 'a little closer');
  assert.equal(await page.getByRole('textbox', { name: 'Your message' }).evaluate(e => e === document.activeElement), true);
  await page.getByRole('button', { name: 'Send message' }).click(); await page.getByText('delivered ✓', { exact: true }).waitFor();
  await page.evaluate(() => syntheticHost.send({ t: 'message', id: crypto.randomUUID(), text: '<img src=x onerror="window.injected=true">' }));
  await page.getByText('<img src=x onerror="window.injected=true">', { exact: true }).waitFor();
  assert.equal(await page.evaluate(() => window.injected), undefined); assert.equal(await page.locator('#messages img').count(), 0);
  // Remove the injection fixture from the capture, but keep the real escaping assertion.
  await page.locator('#messages .message').last().evaluate(e => e.remove());
  await page.screenshot({ path: path.join(output, '03-bluefy-chat.png'), fullPage: true });
  await page.getByRole('button', { name: 'Leave ↗' }).click(); await page.getByRole('button', { name: 'Stay', exact: true }).click(); assert.equal(await page.locator('#chat').isVisible(), true);
  await page.getByRole('button', { name: 'Leave ↗' }).click(); await page.getByRole('button', { name: 'Leave', exact: true }).click();
  assert.equal(await page.locator('#messages .message').count(), 0); assert.equal(await page.locator('#message').inputValue(), '');
  await page.getByRole('button', { name: 'Find a friend' }).click(); await page.getByRole('heading', { name: 'Request sent.' }).waitFor(); await page.getByRole('button', { name: 'Cancel request' }).click();
  await page.getByRole('button', { name: 'Find a friend' }).click(); await page.getByRole('heading', { name: 'Request sent.' }).waitFor();
  await page.evaluate(() => syntheticHost.send({ t: 'decline' })); await page.getByRole('heading', { name: 'Small talk. Zero signal.' }).waitFor();
  await page.getByRole('button', { name: 'Help and privacy' }).click(); await page.getByRole('button', { name: 'Got it' }).click();
  await page.waitForFunction(() => document.querySelector('#offline-status').textContent.includes('Offline copy ready'));
  await context.setOffline(true); await page.reload(); await page.getByRole('button', { name: 'Find a friend' }).waitFor();
  assert.equal(await page.getByRole('textbox', { name: 'Your nickname' }).inputValue(), 'Sweet Tooth');
  await page.screenshot({ path: path.join(output, '04-bluefy-offline.png'), fullPage: true });
  await context.setOffline(false);
  await page.goto(origin + '/install.html'); await page.getByRole('heading', { name: 'A little setup. A lot of hello.' }).waitFor();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: path.join(output, '05-iphone-options.png'), fullPage: true });
  const unsupported = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await unsupported.addInitScript(() => Object.defineProperty(navigator, 'bluetooth', { value: undefined })); const unsupportedPage = await unsupported.newPage();
  await unsupportedPage.goto(origin); await unsupportedPage.getByRole('heading', { name: 'iPhone? Meet Bluefy.' }).waitFor(); assert.equal(await unsupportedPage.locator('#connect').isVisible(), false);
  await unsupportedPage.screenshot({ path: path.join(output, '06-get-bluefy.png'), fullPage: true });
  assert.deepEqual(errors, []); console.log('PASS: mobile UI, fresh approval, cancellation/decline, encrypted synthetic transport, bidirectional messages/receipts, literal text escaping, draft/focus preservation, clear-on-leave, offline reload and unsupported-browser onboarding');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
