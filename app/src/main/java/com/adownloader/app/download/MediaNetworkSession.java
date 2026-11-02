package com.adownloader.app.download;

import java.util.*;

/** Shared probe/download hooks; no Activity, CookieHandler, or cross-job mutable cookie jar. */
public final class MediaNetworkSession {
  private MediaNetworkSession() {}

  public record Observation(
      String url,
      String page,
      int frame,
      int status,
      long at,
      Map<String, String> headers,
      List<String> headerNames,
      int tab) {
    public Observation(
        String url,
        String page,
        int frame,
        int status,
        long at,
        Map<String, String> headers,
        List<String> headerNames) {
      this(url, page, frame, status, at, headers, headerNames, -1);
    }

    public boolean fresh() {
      return status >= 200
          && status < 300
          && System.currentTimeMillis() - at >= 0
          && System.currentTimeMillis() - at <= 120000;
    }
  }

  public record Bootstrap(
      String url,
      int status,
      int redirects,
      Map<String, String> metadata,
      byte[] body,
      Map<String, List<String>> responseCookies,
      Map<String, String> requestHeaders) {}

  public interface Provider {
    Observation latest(String url, String page);

    default Observation latest(String url, String page, int tab, int frame) {
      Observation o = latest(url, page);
      return o != null && (tab < 0 || o.tab() == tab) && (frame < 0 || o.frame() == frame)
          ? o
          : null;
    }

    default Bootstrap manifest(
        String url,
        String page,
        int tab,
        int frame,
        Map<String, String> headers,
        TransferControl control)
        throws Exception {
      if (latest(url, page, tab, frame) == null)
        throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED);
      return manifest(url, page, headers, control);
    }

    default Bootstrap captured(String url, String page, int tab, int frame, TransferControl control)
        throws Exception {
      return null;
    }

    Bootstrap manifest(
        String url, String page, Map<String, String> headers, TransferControl control)
        throws Exception;
  }

  private static volatile java.lang.ref.WeakReference<Provider> provider =
      new java.lang.ref.WeakReference<>(null);
  private static volatile java.util.function.Consumer<Map<String, Object>> sink;

  public static void install(Provider value) {
    provider = new java.lang.ref.WeakReference<>(value);
  }

  public static void remove(Provider value) {
    if (provider.get() == value) provider.clear();
  }

  public static Provider provider() {
    return provider.get();
  }

  public static Observation latest(String url, String page) {
    return latest(url, page, -1, -1);
  }

  public static Observation latest(String url, String page, int tab, int frame) {
    Provider p = provider();
    if (p == null || page == null) return null;
    Observation o = p.latest(url, page, tab, frame);
    return o != null && o.fresh() && o.url().equals(url) && o.page().equals(page) ? o : null;
  }

  public static void diagnostics(java.util.function.Consumer<Map<String, Object>> value) {
    sink = value;
  }

  public static void trace(
      String stage,
      String url,
      String transport,
      int status,
      int redirects,
      String mime,
      String body,
      Collection<String> names,
      Collection<String> geckoNames,
      boolean geckoSuccess,
      String action) {
    trace(
        stage,
        url,
        transport,
        status,
        redirects,
        mime,
        body,
        names,
        geckoNames,
        geckoSuccess,
        action,
        transport.equals("native")
            ? "native-http"
            : transport.equals("gecko") ? "content-fetch" : "playback-response");
  }

  public static void trace(
      String stage,
      String url,
      String transport,
      int status,
      int redirects,
      String mime,
      String body,
      Collection<String> names,
      Collection<String> geckoNames,
      boolean geckoSuccess,
      String action,
      String requestType) {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put(
        "stage",
        Set.of(
                    "ROOT_MANIFEST",
                    "MASTER_MANIFEST",
                    "VARIANT_PLAYLIST",
                    "AUDIO_PLAYLIST",
                    "AES_KEY",
                    "INIT_SEGMENT",
                    "VIDEO_SEGMENT",
                    "AUDIO_SEGMENT",
                    "DIRECT")
                .contains(stage)
            ? stage
            : "DIRECT");
    try {
      safe.put("host", java.net.URI.create(url).getHost());
    } catch (Exception e) {
      safe.put("host", "");
    }
    safe.put(
        "transport",
        Set.of("gecko", "gecko-response-cache").contains(transport) ? transport : "native");
    safe.put(
        "requestType",
        Set.of(
                    "media",
                    "xmlhttprequest",
                    "fetch",
                    "other",
                    "native-http",
                    "content-fetch",
                    "playback-response")
                .contains(requestType)
            ? requestType
            : "UNKNOWN");
    safe.put("status", Math.max(0, Math.min(599, status)));
    safe.put("redirects", Math.max(0, Math.min(8, redirects)));
    String type = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    safe.put("mime", type.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? type : "");
    safe.put(
        "body",
        Set.of("HLS", "DASH", "HTML_CHALLENGE", "HTML", "UNKNOWN", "MEDIA").contains(body)
            ? body
            : "UNKNOWN");
    safe.put("headerNames", MediaHeaders.names(names));
    safe.put("geckoHeaderNames", MediaHeaders.names(geckoNames));
    var missing = new ArrayList<>(MediaHeaders.names(geckoNames));
    missing.removeAll(MediaHeaders.names(names));
    safe.put("missingHeaderNames", missing);
    safe.put("geckoSuccess", geckoSuccess);
    safe.put("manifestCacheUsed", "GECKO_RESPONSE_CACHE".equals(action));
    safe.put("nativeRequestSkipped", "GECKO_RESPONSE_CACHE".equals(action));
    safe.put("nativeSuccess", transport.equals("native") && status >= 200 && status < 300);
    safe.put(
        "action",
        Set.of(
                    "NATIVE_INITIAL",
                    "NATIVE_CONTEXT_RETRY",
                    "GECKO_BOOTSTRAP",
                    "GECKO_RESPONSE_CACHE",
                    "GECKO_CACHE_MISS",
                    "MANIFEST_NATIVE_BLOCKED",
                    "SEGMENT_NATIVE_BLOCKED")
                .contains(action)
            ? action
            : "NATIVE_INITIAL");
    var listener = sink;
    if (listener != null) listener.accept(Collections.unmodifiableMap(safe));
  }
}
