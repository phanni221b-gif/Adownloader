(() => {
  const sent = new Set();

  function report(url, source, contentType) {
    if (!url || typeof url !== "string") return;
    if (url.startsWith("blob:") || url.startsWith("data:")) return;
    const key = source + "|" + url;
    if (sent.has(key)) return;
    if (sent.size >= 500) sent.delete(sent.values().next().value);
    sent.add(key);
    browser.runtime.sendMessage({
      kind: "candidate",
      url,
      contentType: contentType || "",
      source: source || "page-scan",
      pageUrl: location.href
    }).catch(() => {});
  }

  function looksLikeMedia(url) {
    if (!url || typeof url !== "string") return false;
    const u = url.toLowerCase();
    return u.includes(".m3u8") || u.includes(".mpd") ||
      /\.(mp4|webm|m4v|mov|mkv|m4a|mp3|aac|ogg|opus)(\?|#|$)/i.test(u);
  }

  function scanDom() {
    try {
      document.querySelectorAll("video,audio,source").forEach(el => {
        const src = el.currentSrc || el.src || el.getAttribute("src");
        if (src && !src.startsWith("blob:")) report(src, "dom-media", "");
      });
    } catch (_) {}
  }

  function scanPerformance() {
    try {
      performance.getEntriesByType("resource").forEach(entry => {
        if (entry && looksLikeMedia(entry.name)) report(entry.name, "performance-resource", "");
      });
    } catch (_) {}
  }

  function scan() {
    scanDom();
    scanPerformance();
  }

  try {
    const observer = new MutationObserver(scanDom);
    observer.observe(document.documentElement || document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src"]
    });
  } catch (_) {}

  window.addEventListener("load", scan, { once: false });
  document.addEventListener("play", scanDom, true);
  setInterval(scanPerformance, 1500);
  scan();
})();
