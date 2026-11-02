package com.adownloader.app.browser;

import android.util.Log;
import com.adownloader.app.media.MediaDetector;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;

/** Installs the bundled Gecko WebExtension that observes network/media events. */
public final class MediaExtensionManager implements AutoCloseable {
  private static final java.util.concurrent.atomic.AtomicLong generation =
      new java.util.concurrent.atomic.AtomicLong();
  private final long ticket = generation.incrementAndGet();
  private volatile boolean closed;
  private WebExtension installed;
  private static final String TAG = "MediaExtension";
  private static final String EXTENSION_URI = "resource://android/assets/media_sniffer/";
  private static final String EXTENSION_ID = "media-sniffer@adownloader.local";
  private static final String NATIVE_APP = "adownloader";

  public interface StatusListener {
    void onExtensionReady();

    void onExtensionError(String message);
  }

  private final GeckoManifestBridge bridge;
  private final GeckoRuntime runtime;
  private final BrowserSessionManager browser;
  private final Map<Integer, org.mozilla.geckoview.GeckoSession> tabSessions =
      new LinkedHashMap<>();
  private final Map<Integer, java.util.List<JSONObject>> pending = new LinkedHashMap<>();
  private final StatusListener statusListener;

  public MediaExtensionManager(
      GeckoRuntime runtime, BrowserSessionManager browser, StatusListener statusListener) {
    bridge = new GeckoManifestBridge(browser);
    this.runtime = runtime;
    this.browser = browser;
    this.statusListener = statusListener;
  }

  public void install() {
    runtime
        .getWebExtensionController()
        .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
        .accept(
            extension -> {
              if (closed || ticket != generation.get()) return;
              installed = extension;
              extension.setMessageDelegate(
                  new WebExtension.MessageDelegate() {
                    @Override
                    public GeckoResult<Object> onMessage(
                        String nativeApp, Object message, WebExtension.MessageSender sender) {
                      if (closed
                          || !NATIVE_APP.equals(nativeApp)
                          || !(message instanceof JSONObject)) return null;
                      route((JSONObject) message);
                      return null;
                    }
                  },
                  NATIVE_APP);
              browser.setSessionObserver(this::sessionChanged);
              if (statusListener != null) statusListener.onExtensionReady();
            },
            error -> {
              if (closed) return;
              Log.e(TAG, "Unable to install built-in media extension");
              if (statusListener != null) statusListener.onExtensionError(error.getMessage());
            });
  }

  private void sessionChanged(org.mozilla.geckoview.GeckoSession session, boolean opened) {
    if (installed == null) return;
    if (!opened) {
      session.getWebExtensionController().setMessageDelegate(installed, null, NATIVE_APP);
      tabSessions
          .entrySet()
          .removeIf(
              entry -> {
                if (entry.getValue() != session) return false;
                pending.remove(entry.getKey());
                return true;
              });
      return;
    }
    session
        .getWebExtensionController()
        .setMessageDelegate(
            installed,
            new WebExtension.MessageDelegate() {
              @Override
              public void onConnect(WebExtension.Port port) {
                bridge.connect(session, port);
              }

              @Override
              public GeckoResult<Object> onMessage(
                  String app, Object message, WebExtension.MessageSender sender) {
                if (closed
                    || !NATIVE_APP.equals(app)
                    || sender.session != session
                    || !sender.isTopLevel()
                    || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
                    || !(message instanceof JSONObject)) return null;
                JSONObject binding = (JSONObject) message;
                int id = binding.optInt("tabId", -1);
                if (!"bind".equals(binding.optString("kind"))
                    || id < 0
                    || browser.detectorFor(session) == null) return null;
                tabSessions.put(id, session);
                java.util.List<JSONObject> queued = pending.remove(id);
                if (queued != null)
                  for (JSONObject item : queued) handleMessage(item, browser.detectorFor(session));
                return null;
              }
            },
            NATIVE_APP);
  }

  private void route(JSONObject json) {
    int id = json.optInt("tabId", -1);
    if (id < 0) return;
    MediaDetector detector = browser.detectorFor(tabSessions.get(id));
    if (detector == null) {
      // A direct media document can respond before its isolated content script binds the tab.
      if (pending.size() >= 5 && !pending.containsKey(id))
        pending.remove(pending.keySet().iterator().next());
      java.util.List<JSONObject> queued =
          pending.computeIfAbsent(id, key -> new java.util.ArrayList<>());
      if (queued.size() >= 200) queued.remove(0);
      queued.add(json);
      return;
    }
    handleMessage(json, detector);
  }

  private void handleMessage(JSONObject json, MediaDetector detector) {
    bridge.observe(json);
    if ("detection".equals(json.optString("kind"))) {
      detector.detectionDiagnostics = json.optJSONObject("metrics");
      return;
    }
    if ("performance".equals(json.optString("kind"))) {
      detector.performanceMetrics = json.optJSONObject("metrics");
      return;
    }
    if ("page-meta".equals(json.optString("kind"))) {
      detector.pageTitle(json.optString("pageUrl"), json.optString("title"));
      return;
    }
    String url = json.optString("url", "").trim();
    if (url.isEmpty()) return;

    String contentType = json.optString("contentType", "");
    String source = json.optString("source", "extension");
    String pageUrl = json.optString("pageUrl", null);
    Map<String, String> headers = new LinkedHashMap<>();
    JSONObject headerJson = json.optJSONObject("headers");
    if (headerJson != null) {
      Iterator<String> keys = headerJson.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String value = headerJson.optString(key, "");
        if ((com.adownloader.app.download.MediaHeaders.allowed(key)
                && (json.optBoolean("mediaSuccess")
                    || !com.adownloader.app.download.MediaHeaders.sensitive(key)
                    || key.equalsIgnoreCase("cookie")))
            && !value.isBlank()
            && value.length() <= 32768
            && !value.contains("\r")
            && !value.contains("\n")) headers.put(key, value);
      }
    }
    detector.rememberContext(url, pageUrl, headers);
    if ("resource-context".equals(json.optString("kind"))) return;
    Map<String, String> metadata = new LinkedHashMap<>();
    JSONObject meta = json.optJSONObject("metadata");
    if (meta != null)
      for (String key :
          java.util.List.of(
              "title",
              "mime",
              "disposition",
              "etag",
              "modified",
              "length",
              "frameScore",
              "frameHost",
              "frameId")) {
        String value = meta.optString(key);
        if (!value.isBlank() && value.length() <= 4096) metadata.put(key, value);
      }
    if (json.optBoolean("mediaSuccess")) metadata.put("requestContextVerified", "true");
    metadata.put("browserTabId", String.valueOf(json.optInt("tabId", -1)));
    detector.capture(url, contentType, source, pageUrl, headers, metadata);
  }

  @Override
  public void close() {
    closed = true;
    bridge.close();
    browser.setSessionObserver(null);
    for (var tab : browser.tabs())
      if (installed != null)
        tab.session.getWebExtensionController().setMessageDelegate(installed, null, NATIVE_APP);
    tabSessions.clear();
    pending.clear();
    if (installed != null && ticket == generation.get())
      installed.setMessageDelegate(null, NATIVE_APP);
    installed = null;
  }
}
