const requestState = new Map();

function safeHeaders(headers) {
  const out = {};
  if (!headers) return out;
  for (const h of headers) {
    if (!h || !h.name) continue;
    const lower = String(h.name).toLowerCase();
    if (lower === 'referer' || lower === 'user-agent' || lower === 'origin') {
      out[h.name] = h.value || '';
    }
  }
  return out;
}

function send(payload) {
  try {
    browser.runtime.sendNativeMessage('adownloader', payload).catch(() => {});
  } catch (_) {}
}

browser.webRequest.onBeforeRequest.addListener(
  details => {
    requestState.set(details.requestId, {
      url: details.url,
      pageUrl: details.originUrl || details.documentUrl || null,
      requestHeaders: {}
    });
    send({
      event: 'request',
      url: details.url,
      requestType: details.type,
      page: details.originUrl || details.documentUrl || null
    });
  },
  { urls: ['<all_urls>'] }
);

browser.webRequest.onBeforeSendHeaders.addListener(
  details => {
    const state = requestState.get(details.requestId) || { url: details.url };
    state.requestHeaders = safeHeaders(details.requestHeaders);
    requestState.set(details.requestId, state);
    send({
      event: 'request-headers',
      url: details.url,
      page: state.pageUrl || details.originUrl || details.documentUrl || null,
      headers: state.requestHeaders
    });
  },
  { urls: ['<all_urls>'] },
  ['requestHeaders']
);

browser.webRequest.onHeadersReceived.addListener(
  details => {
    const state = requestState.get(details.requestId) || {};
    let mime = null;
    if (details.responseHeaders) {
      for (const h of details.responseHeaders) {
        if (h && h.name && String(h.name).toLowerCase() === 'content-type') {
          mime = h.value || null;
          break;
        }
      }
    }
    send({
      event: 'response',
      url: details.url,
      page: state.pageUrl || details.originUrl || details.documentUrl || null,
      mime: mime,
      statusCode: details.statusCode,
      headers: state.requestHeaders || {}
    });
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders']
);

function cleanup(details) {
  requestState.delete(details.requestId);
}

browser.webRequest.onCompleted.addListener(cleanup, { urls: ['<all_urls>'] });
browser.webRequest.onErrorOccurred.addListener(cleanup, { urls: ['<all_urls>'] });
