(() => {
  if (window.__ADOWNLOADER_GECKO_HOOKED__) return;
  window.__ADOWNLOADER_GECKO_HOOKED__ = true;

  const send = (payload) => {
    try {
      browser.runtime.sendNativeMessage('adownloader', payload).catch(() => {});
    } catch (_) {}
  };

  const report = (url, kind) => {
    try {
      if (!url) return;
      const value = String(url);
      if (!(value.startsWith('http://') || value.startsWith('https://') || value.startsWith('blob:'))) return;
      send({ event: 'content', url: value, kind, page: location.href });
    } catch (_) {}
  };

  try {
    const nativeFetch = window.fetch;
    window.fetch = function(input, init) {
      try { report(input && input.url ? input.url : input, 'fetch'); } catch (_) {}
      return nativeFetch.apply(this, arguments).then(response => {
        try { report(response.url, 'fetch-response'); } catch (_) {}
        return response;
      });
    };
  } catch (_) {}

  try {
    const nativeOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      report(url, 'xhr');
      return nativeOpen.apply(this, arguments);
    };
  } catch (_) {}

  try {
    const nativeCreateObjectURL = URL.createObjectURL;
    URL.createObjectURL = function(value) {
      const result = nativeCreateObjectURL.apply(this, arguments);
      report(result, 'blob');
      return result;
    };
  } catch (_) {}

  try {
    const nativeWindowOpen = window.open;
    window.open = function(url, target, features) {
      if (url) send({ event: 'popup', url: String(url), page: location.href });
      return null;
    };
    window.__ADOWNLOADER_NATIVE_OPEN__ = nativeWindowOpen;
  } catch (_) {}

  const inspect = element => {
    try {
      if (!element) return;
      if (element.currentSrc) report(element.currentSrc, 'currentSrc');
      if (element.src) report(element.src, 'src');
      if (element.querySelectorAll) {
        element.querySelectorAll('video[src],audio[src],source[src]').forEach(node => {
          report(node.currentSrc || node.src, 'dom');
        });
      }
    } catch (_) {}
  };

  const scan = () => {
    try {
      document.querySelectorAll('video,audio,source').forEach(inspect);
    } catch (_) {}
    try {
      performance.getEntriesByType('resource').forEach(entry => report(entry.name, 'performance'));
    } catch (_) {}
  };

  document.addEventListener('play', event => inspect(event.target), true);
  document.addEventListener('loadedmetadata', event => inspect(event.target), true);
  document.addEventListener('canplay', event => inspect(event.target), true);

  try {
    new PerformanceObserver(list => {
      for (const entry of list.getEntries()) report(entry.name, 'performance-observer');
    }).observe({ type: 'resource', buffered: true });
  } catch (_) {}

  try {
    new MutationObserver(scan).observe(document.documentElement || document, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['src', 'href']
    });
  } catch (_) {}

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scan, { once: true });
  } else {
    scan();
  }
  setTimeout(scan, 500);
  setTimeout(scan, 1500);
})();
