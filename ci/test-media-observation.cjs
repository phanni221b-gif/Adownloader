'use strict';
// Independent verification of the media-request observation pipeline on a NORMAL public HLS stream:
// a successful (200/206) media request must be observed as a success, a 2xx must NOT raise the
// failure signal, and a 403 must raise exactly one privacy-safe failure signal (host + status, no
// cookie/authorization). This proves the observation feature works end-to-end in the extension,
// independent of any one site, so that an absence of observation on the problem site means the
// player's own segment request was not a successful observable network response — not that the
// pipeline is broken.
const fs = require('fs'), vm = require('vm'), assert = require('assert/strict');
let now = 100000;
class Clock extends Date { static now() { return now; } }

function setup() {
  const callbacks = {}, sent = [], filters = new Map(), timers = new Map(); let tid = 0;
  const event = n => ({ addListener(fn, options) { (callbacks[n] ??= []).push(d => { if (!options?.types || options.types.includes(d.type)) return fn(d); }); } });
  const browser = {
    webRequest: {
      onBeforeSendHeaders: event('before'), onHeadersReceived: event('response'),
      onBeforeRedirect: event('redirect'), onCompleted: event('complete'), onErrorOccurred: event('error'),
      filterResponseData(id) { const f = { written: [], write(d) { this.written.push(Buffer.from(d)); }, disconnect() { this.disconnected = true; }, close() { this.closed = true; } }; filters.set(id, f); return f; }
    },
    runtime: { onMessage: { addListener(fn) { (callbacks.message ??= []).push(fn); } }, sendNativeMessage(_, m) { sent.push(m); return Promise.resolve(true); } },
    tabs: { onRemoved: event('removed') }
  };
  const ctx = vm.createContext({ browser, URL, Date: Clock, Map, Set, performance, TextDecoder, Uint8Array, ArrayBuffer, btoa: s => Buffer.from(s, 'binary').toString('base64'), setTimeout: (fn, delay) => { timers.set(++tid, { fn, delay }); return tid; }, clearTimeout: id => timers.delete(id) });
  for (const name of ['request_context', 'background']) vm.runInContext(fs.readFileSync('app/src/main/assets/media_sniffer/' + name + '.js', 'utf8'), ctx);
  const fire = (n, d, s) => callbacks[n]?.map(fn => fn(d, s));
  const sender = { tab: { id: 6, url: 'https://page.test/watch' }, frameId: 0 };
  fire('message', { kind: 'page-meta', pageUrl: sender.tab.url, pageTitle: 'Normal HLS' }, sender);
  function request(id, url, mime, status) {
    const d = { requestId: id, url, tabId: 6, frameId: 0, type: 'media', statusCode: status, requestHeaders: [{ name: 'Referer', value: sender.tab.url }, { name: 'Cookie', value: 'sid=PRIVATE_COOKIE' }, { name: 'Authorization', value: 'Bearer PRIVATE_TOKEN' }], responseHeaders: [{ name: 'Content-Type', value: mime }] };
    fire('before', d); fire('response', d); return d;
  }
  return { sent, request };
}

// 1) Load the real master manifest so the segment host is an established media origin.
const r = setup();
r.request('master', 'https://cdn.test/movie/master.m3u8', 'application/vnd.apple.mpegurl', 200);

// 2) A normal segment served 200, and a byte-range segment served 206 — both must be observed as success.
r.request('seg200', 'https://cdn.test/movie/seg1.ts', 'video/mp2t', 200);
r.request('seg206', 'https://cdn.test/movie/seg2.ts', 'video/mp2t', 206);

const success = r.sent.filter(x => x.kind === 'resource-context' && x.mediaSuccess === true);
assert(success.some(x => x.url.endsWith('/seg1.ts') && x.geckoStatus === 200), 'A 200 HLS segment must be observed as a media success');
assert(success.some(x => x.url.endsWith('/seg2.ts') && x.geckoStatus === 206), 'A 206 (byte-range) HLS segment must be observed as a media success');

// A successful media request must NOT raise the failure signal.
assert(!r.sent.some(x => x.kind === 'media-observed'), 'A 2xx media request must not raise a failure (media-observed) signal');

// 3) A blocked (403) segment raises exactly one privacy-safe failure signal: host + status only.
r.request('seg403', 'https://block.test/movie/seg3.ts', 'text/html', 403);
const failures = r.sent.filter(x => x.kind === 'media-observed');
assert.equal(failures.length, 1, 'A failed media request raises exactly one media-observed signal');
assert.equal(failures[0].host, 'block.test');
assert.equal(failures[0].status, 403);
// The failure signal must never carry a URL, cookie, authorization or any header.
const serialized = JSON.stringify(failures[0]);
assert(!/PRIVATE_COOKIE|PRIVATE_TOKEN|Authorization|Cookie|seg3\.ts/i.test(serialized), 'The failure signal must not leak URL, cookie or authorization values');
assert.equal(failures[0].url, undefined);
assert.equal(failures[0].headers, undefined);

// A 403 is never a success observation.
assert(!r.sent.some(x => x.kind === 'resource-context' && x.url.includes('block.test') && x.mediaSuccess), 'A 403 segment is never a media success');

console.log('Media-request observation on normal HLS: 200 and 206 observed as success, 2xx raises no failure, 403 raises one privacy-safe failure: PASS');
