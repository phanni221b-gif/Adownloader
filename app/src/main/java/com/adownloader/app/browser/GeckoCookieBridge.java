package com.adownloader.app.browser;

import android.os.Handler;
import android.os.Looper;
import com.adownloader.app.download.BrowserCookies;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.mozilla.geckoview.WebExtension;

/**
 * Native end of the extension's background cookie port (cookie_bridge.js). Answers
 * {@link BrowserCookies.Lookup} by asking the live Gecko cookie store for one exact URL. Values
 * cross only this in-process port and are returned to the caller; nothing here keeps or logs them.
 */
final class GeckoCookieBridge implements BrowserCookies.Lookup {
  private static final long TIMEOUT_MS = 1500;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<String, CompletableFuture<List<BrowserCookies.Cookie>>> pending =
      new ConcurrentHashMap<>();
  private volatile WebExtension.Port port;

  void connect(WebExtension.Port value) {
    WebExtension.Port previous = port;
    port = value;
    value.setDelegate(
        new WebExtension.PortDelegate() {
          @Override
          public void onPortMessage(Object message, WebExtension.Port actual) {
            if (actual == port && message instanceof JSONObject json) receive(json);
          }

          @Override
          public void onDisconnect(WebExtension.Port actual) {
            if (actual != port) return;
            port = null;
            BrowserCookies.remove(GeckoCookieBridge.this);
            failAll();
          }
        });
    if (previous != null && previous != value) failAll();
    BrowserCookies.install(this);
  }

  void close() {
    port = null;
    BrowserCookies.remove(this);
    failAll();
  }

  private void failAll() {
    for (var f : pending.values()) f.complete(List.of());
    pending.clear();
  }

  private void receive(JSONObject json) {
    if (!"cookie-result".equals(json.optString("kind"))) return;
    var future = pending.remove(json.optString("id"));
    if (future == null) return;
    List<BrowserCookies.Cookie> out = new ArrayList<>();
    JSONArray list = json.optJSONArray("cookies");
    if (list != null)
      for (int i = 0; i < Math.min(96, list.length()); i++) {
        JSONObject c = list.optJSONObject(i);
        if (c == null) continue;
        out.add(
            new BrowserCookies.Cookie(
                c.optString("name"),
                c.optString("value"),
                c.optString("sameSite"),
                c.optBoolean("partitioned")));
      }
    future.complete(out);
  }

  @Override
  public String cookieHeader(String url, String topLevelPage) throws Exception {
    WebExtension.Port current = port;
    // The reply is delivered on the main thread; never block it waiting for itself.
    if (current == null || pending.size() >= 16 || Looper.myLooper() == Looper.getMainLooper())
      return "";
    String id = UUID.randomUUID().toString();
    CompletableFuture<List<BrowserCookies.Cookie>> future = new CompletableFuture<>();
    pending.put(id, future);
    try {
      JSONObject request =
          new JSONObject()
              .put("kind", "cookie-lookup")
              .put("id", id)
              .put("url", url)
              .put("topLevelSite", BrowserCookies.topLevelSite(topLevelPage));
      main.post(
          () -> {
            try {
              current.postMessage(request);
            } catch (RuntimeException failed) {
              future.complete(List.of());
            }
          });
      return BrowserCookies.select(
          future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), url, topLevelPage);
    } catch (TimeoutException slow) {
      return "";
    } finally {
      pending.remove(id);
    }
  }
}
