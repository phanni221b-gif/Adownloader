package com.adownloader.app.browser;

import android.os.*;
import com.adownloader.app.download.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.mozilla.geckoview.*;

/** Frame-bound, recent-success-only manifest bridge. Never transports video segments. */
public final class GeckoManifestBridge implements MediaNetworkSession.Provider, AutoCloseable {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final BrowserSessionManager browser;
  private final Map<String, WebExtension.Port> ports = new LinkedHashMap<>();

  private record Bound(MediaNetworkSession.Observation observation, int tabId) {}

  private final Map<String, Bound> observations = new LinkedHashMap<>();

  private static final class Pending {
    final WebExtension.Port port;
    final CompletableFuture<MediaNetworkSession.Bootstrap> future = new CompletableFuture<>();
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    String url = "";
    int status;
    Map<String, String> metadata = new LinkedHashMap<>();

    Pending(WebExtension.Port port) {
      this.port = port;
    }
  }

  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private final ManifestResponseCache captures = new ManifestResponseCache();
  private boolean closed;

  public GeckoManifestBridge(BrowserSessionManager browser) {
    this.browser = browser;
    MediaNetworkSession.install(this);
  }

  private static String key(String url, String page) {
    return page + "\n" + url;
  }

  public synchronized void observe(JSONObject j) {
    if (closed) return;
    int tab = j.optInt("tabId", -1);
    if (j.optString("kind").equals("manifest-invalidate")) {
      discardTab(tab);
      return;
    }
    if (j.optString("kind").equals("page-meta")) {
      captures.page(tab, j.optString("pageUrl"));
      String page = j.optString("pageUrl");
      observations
          .entrySet()
          .removeIf(
              e -> e.getValue().tabId == tab && !e.getValue().observation.page().equals(page));
      return;
    }
    if (!j.optBoolean("mediaSuccess")) return;
    String url = j.optString("url"), page = j.optString("pageUrl");
    int status = j.optInt("geckoStatus");
    if (tab < 0
        || MediaHeaders.origin(url).isEmpty()
        || MediaHeaders.origin(page).isEmpty()
        || status < 200
        || status >= 300) return;
    Map<String, String> headers = new LinkedHashMap<>();
    JSONObject h = j.optJSONObject("headers");
    if (h != null)
      for (Iterator<String> it = h.keys(); it.hasNext(); ) {
        String n = it.next();
        headers.put(n, h.optString(n));
      }
    List<String> names = new ArrayList<>();
    JSONArray ns = j.optJSONArray("headerNames");
    if (ns != null) for (int i = 0; i < Math.min(48, ns.length()); i++) names.add(ns.optString(i));
    var o =
        new MediaNetworkSession.Observation(
            url,
            page,
            j.optInt("frameId"),
            status,
            System.currentTimeMillis(),
            Collections.unmodifiableMap(MediaHeaders.select(headers)),
            MediaHeaders.names(names),
            tab);
    observations.remove(key(url, page) + "|" + tab + ":" + o.frame());
    observations.put(key(url, page) + "|" + tab + ":" + o.frame(), new Bound(o, tab));
    int bytes = 0;
    for (var b : observations.values())
      for (var v : b.observation.headers().values()) bytes += v.length();
    while (observations.size() > 128 || bytes > 524288) {
      Bound old = observations.remove(observations.keySet().iterator().next());
      for (String v : old.observation.headers().values()) bytes -= v.length();
    }
  }

  public synchronized void discardTab(int tab) {
    captures.discard(tab);
    observations.values().removeIf(b -> b.tabId == tab);
  }

  /** Called only for built-in background messages, never page-world content. */
  public synchronized boolean capture(JSONObject j) {
    if (closed) return false;
    String id = j.optString("captureId"), kind = j.optString("kind");
    int tab = j.optInt("tabId", -1);
    try {
      if (kind.equals("manifest-capture-start")) {
        if (!Set.of("media", "xmlhttprequest", "fetch", "other")
            .contains(j.optString("requestType"))) return false;
        Map<String, String> headers =
            bootstrapHeaders(
                j.has("headers")
                    ? new JSONObject().put("requestHeaders", j.getJSONObject("headers"))
                    : j);
        Map<String, String> metadata = new LinkedHashMap<>();
        JSONObject raw = j.optJSONObject("metadata");
        if (raw != null)
          for (String name : List.of("content-type", "etag", "last-modified")) {
            String value = raw.optString(name);
            if (value.length() <= 4096 && !value.contains("\r") && !value.contains("\n"))
              metadata.put(name, value);
          }
        metadata.put("request-type", j.optString("requestType"));
        List<String> cookies = new ArrayList<>();
        JSONArray list = j.optJSONArray("cookies");
        if (list != null)
          for (int i = 0; i < Math.min(32, list.length()); i++) {
            String c = list.optString(i);
            if (c.length() <= 4096 && !c.contains("\r") && !c.contains("\n")) cookies.add(c);
          }
        var o =
            new MediaNetworkSession.Observation(
                j.optString("url"),
                j.optString("pageUrl"),
                j.optInt("frameId", -1),
                j.optInt("geckoStatus"),
                j.optLong("observedAt"),
                headers,
                List.of(),
                tab);
        return captures.begin(id, o, metadata, cookies);
      }
      if (kind.equals("manifest-capture-chunk")) {
        String data = j.optString("data");
        return data.length() <= 65536
            && captures.chunk(id, tab, j.optInt("index", -1), Base64.getDecoder().decode(data));
      }
      if (kind.equals("manifest-capture-end"))
        return captures.end(id, tab, j.optInt("chunks", -1), j.optInt("bytes", -1));
      if (kind.equals("manifest-capture-abort")) {
        captures.abort(id, tab);
        return true;
      }
    } catch (Exception rejected) {
      captures.abort(id, tab);
    }
    return false;
  }

  @Override
  public MediaNetworkSession.Bootstrap captured(
      String url, String page, int tab, int frame, TransferControl control) throws Exception {
    return captures.get(url, page, tab, frame, control);
  }

  public void connect(GeckoSession session, WebExtension.Port port) {
    if (closed
        || port.sender.session != session
        || port.sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
        || browser.detectorFor(session) == null) return;
    port.setDelegate(
        new WebExtension.PortDelegate() {
          @Override
          public void onPortMessage(Object message, WebExtension.Port actual) {
            if (!(message instanceof JSONObject) || actual != port) return;
            receive((JSONObject) message, port, session);
          }

          @Override
          public void onDisconnect(WebExtension.Port actual) {
            synchronized (GeckoManifestBridge.this) {
              ports.values().removeIf(p -> p == actual);
              pending.values().stream()
                  .filter(p -> p.port == actual)
                  .forEach(
                      p ->
                          p.future.completeExceptionally(
                              new DownloadError(DownloadError.Category.ACCESS_BLOCKED)));
            }
          }
        });
  }

  private synchronized void receive(JSONObject j, WebExtension.Port port, GeckoSession session) {
    if (closed) return;
    String kind = j.optString("kind");
    if (kind.equals("frame-bind")) {
      int tab = j.optInt("tabId", -1), frame = j.optInt("frameId", -1);
      if (tab >= 0 && frame >= 0 && browser.detectorFor(session) != null) {
        if (ports.size() >= 64) ports.remove(ports.keySet().iterator().next());
        ports.put(tab + ":" + frame, port);
      }
      return;
    }
    Pending p = pending.get(j.optString("id"));
    if (p == null || p.port != port || p.future.isDone()) return;
    try {
      switch (kind) {
        case "manifest-start" -> {
          p.url = j.getString("url");
          p.status = j.getInt("status");
          if (MediaHeaders.origin(p.url).isEmpty()) throw new IOException();
          JSONObject m = j.optJSONObject("metadata");
          if (m != null)
            for (String n :
                List.of(
                    "content-type",
                    "content-length",
                    "etag",
                    "last-modified",
                    "content-disposition")) {
              String value = m.optString(n);
              if (value.length() <= 4096 && !value.contains("\r") && !value.contains("\n"))
                p.metadata.put(n, value);
            }
        }
        case "manifest-chunk" -> {
          String raw = j.getString("data");
          if (raw.length() > 90000) throw new IOException();
          byte[] bytes = Base64.getDecoder().decode(raw);
          if (p.body.size() + bytes.length > 8388608) throw new IOException();
          p.body.write(bytes);
        }
        case "manifest-end" -> {
          if (p.status < 200
              || p.status >= 300
              || p.body.size() == 0
              || p.body.size() != j.optLong("bytes", -1)
              || j.optInt("redirects") > 8) throw new IOException();
          Map<String, List<String>> cookies = new LinkedHashMap<>();
          JSONObject c = j.optJSONObject("cookies");
          if (c != null)
            for (Iterator<String> it = c.keys(); it.hasNext() && cookies.size() < 8; ) {
              String url = it.next();
              if (MediaHeaders.origin(url).isEmpty()) continue;
              JSONArray v = c.optJSONArray(url);
              List<String> list = new ArrayList<>();
              if (v != null)
                for (int i = 0; i < Math.min(32, v.length()); i++) {
                  String value = v.optString(i);
                  if (value.length() <= 4096 && !value.contains("\r") && !value.contains("\n"))
                    list.add(value);
                }
              cookies.put(url, list);
            }
          p.future.complete(
              new MediaNetworkSession.Bootstrap(
                  p.url,
                  p.status,
                  j.optInt("redirects"),
                  p.metadata,
                  p.body.toByteArray(),
                  cookies,
                  bootstrapHeaders(j)));
        }
        case "manifest-error" ->
            p.future.completeExceptionally(
                new DownloadError(DownloadError.Category.ACCESS_BLOCKED));
      }
    } catch (Exception rejected) {
      p.future.completeExceptionally(new DownloadError(DownloadError.Category.ACCESS_BLOCKED));
    }
  }

  @Override
  public synchronized MediaNetworkSession.Observation latest(String url, String page) {
    Bound b = find(url, page, -1, -1);
    return !closed
            && b != null
            && b.observation.fresh()
            && ports.containsKey(b.tabId + ":" + b.observation.frame())
        ? b.observation
        : null;
  }

  private synchronized Bound find(String url, String page, int tab, int frame) {
    Bound found = null;
    for (var b : observations.values())
      if (b.observation.url().equals(url)
          && b.observation.page().equals(page)
          && (tab < 0 || b.tabId == tab)
          && (frame < 0 || b.observation.frame() == frame)) found = b;
    return found;
  }

  @Override
  public synchronized MediaNetworkSession.Observation latest(
      String url, String page, int tab, int frame) {
    Bound b = find(url, page, tab, frame);
    return !closed
            && b != null
            && b.observation.fresh()
            && ports.containsKey(b.tabId + ":" + b.observation.frame())
        ? b.observation
        : null;
  }

  private static Map<String, String> bootstrapHeaders(JSONObject json) {
    JSONObject raw = json.optJSONObject("requestHeaders");
    Map<String, String> out = new LinkedHashMap<>();
    if (raw != null)
      for (Iterator<String> it = raw.keys(); it.hasNext() && out.size() < 16; ) {
        String key = it.next();
        out.put(key, raw.optString(key));
      }
    return MediaHeaders.select(out);
  }

  @Override
  public MediaNetworkSession.Bootstrap manifest(
      String url, String page, Map<String, String> headers, TransferControl control)
      throws Exception {
    return manifest(url, page, -1, -1, headers, control);
  }

  @Override
  public MediaNetworkSession.Bootstrap manifest(
      String url,
      String page,
      int tab,
      int frame,
      Map<String, String> headers,
      TransferControl control)
      throws Exception {
    final Pending p;
    String id = UUID.randomUUID().toString();
    synchronized (this) {
      Bound b = find(url, page, tab, frame);
      if (latest(url, page, tab, frame) == null || b == null || pending.size() >= 2)
        throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED);
      p = new Pending(ports.get(b.tabId + ":" + b.observation.frame()));
      pending.put(id, p);
    }
    main.post(
        () -> {
          try {
            p.port.postMessage(
                new JSONObject().put("kind", "fetch-manifest").put("id", id).put("url", url));
          } catch (Exception e) {
            p.future.completeExceptionally(
                new DownloadError(DownloadError.Category.ACCESS_BLOCKED));
          }
        });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    try {
      while (true) {
        control.check();
        if (System.nanoTime() > deadline)
          throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED);
        try {
          return p.future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException wait) {
        } catch (ExecutionException e) {
          throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED);
        }
      }
    } finally {
      synchronized (this) {
        pending.remove(id);
      }
      main.post(
          () -> {
            try {
              p.port.postMessage(new JSONObject().put("kind", "cancel-manifest").put("id", id));
            } catch (Exception ignored) {
            }
          });
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    MediaNetworkSession.remove(this);
    for (Pending p : pending.values())
      p.future.completeExceptionally(new DownloadError(DownloadError.Category.ACCESS_BLOCKED));
    pending.clear();
    for (WebExtension.Port p : ports.values())
      try {
        p.disconnect();
      } catch (Exception ignored) {
      }
    ports.clear();
    observations.clear();
    captures.close();
  }
}
