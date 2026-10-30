const NATIVE_APP = "adownloader";
const requestHeaders = new Map();

function lower(value) {
  return (value || "").toLowerCase();
}

function looksLikeMedia(url, contentType, requestType) {
  const u = lower(url);
  const ct = lower(contentType);
  if (u.includes(".m3u8") || u.includes(".mpd")) return true;
  if (/\.(mp4|webm|m4v|mov|mkv|m4a|mp3|aac|ogg|opus)(\?|#|$)/i.test(u)) return true;
  if (ct.startsWith("video/") || ct.startsWith("audio/")) return true;
  if (ct.includes("mpegurl") || ct.includes("dash+xml")) return true;
  return requestType === "media";
}

function headersToObject(list) {
  const out = {};
  if (!Array.isArray(list)) return out;
  for (const item of list) {
    if (!item || !item.name) continue;
    const name = lower(item.name);
    if (["referer", "origin", "user-agent", "cookie", "authorization"].includes(name)) {
      out[item.name] = item.value || "";
    }
  }
  return out;
}

function contentTypeFromHeaders(list) {
  if (!Array.isArray(list)) return "";
  const hit = list.find(h => h && lower(h.name) === "content-type");
  return hit ? (hit.value || "") : "";
}

function sendCandidate(details, contentType, source) {
  if (!details || !details.url) return;
  if (!looksLikeMedia(details.url, contentType, details.type)) return;
  const payload = {
    kind: "candidate",
    url: details.url,
    contentType: contentType || "",
    source: source || "webRequest",
    pageUrl: details.originUrl || details.documentUrl || "",
    headers: requestHeaders.get(details.requestId) || {}
  };
  browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(() => {});
}

browser.webRequest.onBeforeSendHeaders.addListener(
  details => {
    requestHeaders.set(details.requestId, headersToObject(details.requestHeaders));
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders"]
);

browser.webRequest.onBeforeRequest.addListener(
  details => sendCandidate(details, "", "network-request"),
  { urls: ["<all_urls>"] }
);

browser.webRequest.onHeadersReceived.addListener(
  details => {
    const contentType = contentTypeFromHeaders(details.responseHeaders);
    sendCandidate(details, contentType, "network-response");
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

browser.webRequest.onCompleted.addListener(
  details => requestHeaders.delete(details.requestId),
  { urls: ["<all_urls>"] }
);

browser.webRequest.onErrorOccurred.addListener(
  details => requestHeaders.delete(details.requestId),
  { urls: ["<all_urls>"] }
);

browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || message.kind !== "candidate" || !message.url) return;
  const payload = {
    kind: "candidate",
    url: message.url,
    contentType: message.contentType || "",
    source: message.source || "page-scan",
    pageUrl: (sender && sender.tab && sender.tab.url) || message.pageUrl || "",
    headers: {}
  };
  browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(() => {});
});
