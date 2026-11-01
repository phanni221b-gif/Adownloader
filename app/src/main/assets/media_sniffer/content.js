(() => {
  if (window.top === window) browser.runtime.sendMessage({kind:"bind"}).then(binding => {
    if (binding && Number.isInteger(binding.tabId)) return browser.runtime.sendNativeMessage("adownloader",{kind:"bind",tabId:binding.tabId});
  }).catch(()=>{});
  const stats={domScans:0,domNodes:0,resourceScans:0,resourceEntries:0,eventLoopMaxLagMs:0};
  const sent = new Set();
  let lastPage="";
  function reportPage(){const key=location.href+"|"+document.title;if(key===lastPage)return;lastPage=key;browser.runtime.sendMessage({kind:"page-meta",pageUrl:location.href,pageTitle:(document.title||"").slice(0,300)}).catch(()=>{}); }

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
      pageTitle: (document.title||"").slice(0,300),
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
      stats.domScans++;
      document.querySelectorAll("video,audio,source").forEach(el => {
        stats.domNodes++;
        const src = el.currentSrc || el.src || el.getAttribute("src");
        if (src && !src.startsWith("blob:")) report(src, "dom-media", "");
      });
    } catch (_) {}
  }

  function scanPerformance() {
    try {
      stats.resourceScans++;
      performance.getEntriesByType("resource").forEach(entry => {
        stats.resourceEntries++;
        if (entry && looksLikeMedia(entry.name)) report(entry.name, "performance-resource", "");
      });
    } catch (_) {}
  }

  function scan() {
    scanDom();
    scanPerformance();
  }

  try {
    const observer = new MutationObserver(()=>{reportPage();scanDom();});
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
  if(window.top===window){let count=0,last=performance.now();const probe=setInterval(()=>{const now=performance.now();stats.eventLoopMaxLagMs=Math.max(stats.eventLoopMaxLagMs,Math.max(0,now-last-100));last=now;if(++count>=30){clearInterval(probe);const nav=performance.getEntriesByType("navigation")[0];if(nav){stats.navDclMs=nav.domContentLoadedEventEnd;stats.navLoadMs=nav.loadEventEnd;}browser.runtime.sendMessage({kind:"performance",...stats}).catch(()=>{});}},100);window.addEventListener("pagehide",()=>clearInterval(probe),{once:true});}
})();
