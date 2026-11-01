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

  private final GeckoRuntime runtime;
  private final MediaDetector detector;
  private final StatusListener statusListener;

  public MediaExtensionManager(
      GeckoRuntime runtime, MediaDetector detector, StatusListener statusListener) {
    this.runtime = runtime;
    this.detector = detector;
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
                      handleMessage((JSONObject) message);
                      return null;
                    }
                  },
                  NATIVE_APP);
              if (statusListener != null) statusListener.onExtensionReady();
            },
            error -> {
              if (closed) return;
              Log.e(TAG, "Unable to install built-in media extension");
              if (statusListener != null) statusListener.onExtensionError(error.getMessage());
            });
  }

  private void handleMessage(JSONObject json) {
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
        if (java.util.Set.of("cookie", "referer", "origin", "user-agent")
                .contains(key.toLowerCase(java.util.Locale.ROOT))
            && !value.isBlank()
            && value.length() <= 32768
            && !value.contains("\r")
            && !value.contains("\n")) headers.put(key, value);
      }
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    JSONObject meta = json.optJSONObject("metadata");
    if (meta != null)
      for (String key :
          java.util.List.of("title", "mime", "disposition", "etag", "modified", "length")) {
        String value = meta.optString(key);
        if (!value.isBlank() && value.length() <= 4096) metadata.put(key, value);
      }
    detector.capture(url, contentType, source, pageUrl, headers, metadata);
  }

  @Override
  public void close() {
    closed = true;
    if (installed != null && ticket == generation.get())
      installed.setMessageDelegate(null, NATIVE_APP);
    installed = null;
  }
}
