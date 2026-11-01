package com.adownloader.app.media;

import com.adownloader.app.MediaCandidate;
import java.net.URI;
import java.util.*;

/** A bounded logical list; every entry retains its newest complete URL and per-request context. */
public final class MediaDetector {
  public volatile org.json.JSONObject performanceMetrics;
  public volatile org.json.JSONObject detectionDiagnostics;

  public synchronized void clearPrivateState() {
    resourceHeaders.clear();
    resourcePages.clear();
    pageTitles.clear();
    groups.clear();
    exact.clear();
    performanceMetrics = null;
    detectionDiagnostics = null;
  }

  public interface Listener {
    void onCandidateAdded(MediaCandidate candidate, int totalCount);
  }

  private String boundPage;

  private static String pageKey(String value) {
    return value == null ? "" : value.split("#", 2)[0];
  }

  public synchronized void pageChanged(String page) {
    if (boundPage != null && !pageKey(boundPage).equals(pageKey(page))) reset();
    boundPage = page;
  }

  private final Map<String, String> pageTitles = new LinkedHashMap<>();
  private final Map<String, String> resourcePages = new LinkedHashMap<>();
  private final LinkedHashMap<String, Map<String, String>> resourceHeaders = new LinkedHashMap<>();

  public synchronized void rememberContext(String url, String page, Map<String, String> headers) {
    if (headers.isEmpty() || boundPage != null && !pageKey(boundPage).equals(pageKey(page))) return;
    Map<String, String> merged =
        new LinkedHashMap<>(resourceHeaders.getOrDefault(url, Collections.emptyMap()));
    merged.putAll(headers);
    resourceHeaders.put(url, merged);
    resourcePages.put(url, page);
    while (resourceHeaders.size() > 128) {
      String first = resourceHeaders.keySet().iterator().next();
      resourceHeaders.remove(first);
      resourcePages.remove(first);
    }
  }

  public synchronized MediaCandidate forDownload(MediaCandidate item) {
    MediaCandidate current = latest(item.getIdentityKey());
    if (current == null) return item;
    Map<String, String> meta = new LinkedHashMap<>(current.getMetadata());
    String title = pageTitles.get(pageKey(current.getPageUrl()));
    if (title != null && !title.isBlank()) meta.put("title", title);
    Map<String, Map<String, String>> scoped = new LinkedHashMap<>();
    resourceHeaders.forEach(
        (url, h) -> {
          if (Objects.equals(resourcePages.get(url), current.getPageUrl())) scoped.put(url, h);
        });
    meta.put("requestContextVersion", "2");
    meta.put("requestContexts", new org.json.JSONObject(scoped).toString());
    Map<String, String> origins = new LinkedHashMap<>();
    scoped
        .keySet()
        .forEach(u -> origins.put(u, com.adownloader.app.download.MediaHeaders.origin(u)));
    meta.put("requestContextOrigins", new org.json.JSONObject(origins).toString());
    return new MediaCandidate(
        current.getUrl(),
        current.getType(),
        current.getSource(),
        current.getPageUrl(),
        current.getHeaders(),
        current.getDetectedAt(),
        meta);
  }

  private final LinkedHashMap<String, MediaCandidate> groups = new LinkedHashMap<>();
  private final Map<String, String> exact = new HashMap<>();
  private final Listener listener;

  public MediaDetector(Listener listener) {
    this.listener = listener;
  }

  public synchronized void reset() {
    resourceHeaders.clear();
    resourcePages.clear();
    pageTitles.clear();
    performanceMetrics = null;
    groups.clear();
    exact.clear();
    if (listener != null) listener.onCandidateAdded(null, 0);
  }

  public void capture(
      String url, String mime, String source, String page, Map<String, String> headers) {
    capture(url, mime, source, page, headers, Collections.emptyMap());
  }

  private static boolean networkSource(String source) {
    return "network-response".equals(source) || "response-signature".equals(source);
  }

  public synchronized void capture(
      String url,
      String mime,
      String source,
      String page,
      Map<String, String> headers,
      Map<String, String> metadata) {
    if (url == null || boundPage != null && !pageKey(boundPage).equals(pageKey(page))) return;
    String clean = url.trim(), path;
    try {
      URI uri = URI.create(clean);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null) return;
      path = uri.getPath().toLowerCase(Locale.ROOT);
    } catch (Exception invalid) {
      return;
    }
    if (path.matches(".*[.](m4s|ts|cmfv|cmfa)$")) return;
    MediaCandidate.MediaType type = classify(path, mime);
    if (type == MediaCandidate.MediaType.UNKNOWN) return;
    for (Iterator<Map.Entry<String, MediaCandidate>> iterator = groups.entrySet().iterator();
        iterator.hasNext(); ) {
      MediaCandidate prior = iterator.next().getValue();
      if (prior.getUrl().equals(clean) && prior.getType() != type) {
        if (networkSource(prior.getSource()) && !networkSource(source)) return;
        if (networkSource(source) && !networkSource(prior.getSource())) {
          exact.remove(prior.getExactKey());
          iterator.remove();
        }
      }
    }
    Map<String, String> safe =
        com.adownloader.app.download.MediaHeaders.select(
            headers == null ? Collections.emptyMap() : headers);
    if (!"true".equals(metadata.get("requestContextVerified")))
      safe.keySet()
          .removeIf(
              n ->
                  com.adownloader.app.download.MediaHeaders.sensitive(n)
                      && !n.equalsIgnoreCase("cookie"));
    Map<String, String> meta = new LinkedHashMap<>(metadata);
    if (mime != null && !mime.isBlank()) meta.put("mime", mime);
    MediaCandidate next =
        new MediaCandidate(clean, type, source, page, safe, System.currentTimeMillis(), meta);
    String exactKey = next.getExactKey(), key = exact.getOrDefault(exactKey, next.getIdentityKey());
    MediaCandidate previous = groups.get(key);
    boolean authoritative =
        previous != null && networkSource(previous.getSource()) && !networkSource(source);
    if (authoritative && !previous.getUrl().equals(clean)) return;
    if (previous != null && previous.getUrl().equals(clean)) {
      Map<String, String> merged = new LinkedHashMap<>(previous.getHeaders());
      merged.putAll(safe);
      Map<String, String> combined = new LinkedHashMap<>(previous.getMetadata());
      meta.forEach(
          (k, v) -> {
            if (v != null && !v.isBlank()) combined.put(k, v);
          });
      next =
          new MediaCandidate(
              clean,
              type,
              authoritative ? previous.getSource() : source,
              page == null ? previous.getPageUrl() : page,
              merged,
              System.currentTimeMillis(),
              combined);
    }
    if (previous != null) exact.remove(previous.getExactKey());
    groups.put(key, next);
    exact.put(exactKey, key);
    while (groups.size() > 200) {
      String oldest = groups.keySet().iterator().next();
      exact.remove(groups.remove(oldest).getExactKey());
    }
    if (listener != null) listener.onCandidateAdded(next, groups.size());
  }

  public synchronized void pageTitle(String page, String title) {
    if (page != null && title != null && !title.isBlank()) {
      pageTitles.put(pageKey(page), title.substring(0, Math.min(300, title.length())));
      while (pageTitles.size() > 10) pageTitles.remove(pageTitles.keySet().iterator().next());
    }
    if (title == null || title.isBlank()) return;
    MediaCandidate latest = null;
    for (Map.Entry<String, MediaCandidate> entry : groups.entrySet()) {
      MediaCandidate previous = entry.getValue();
      if (pageKey(previous.getPageUrl()).equals(pageKey(page))) {
        Map<String, String> meta = new LinkedHashMap<>(previous.getMetadata());
        meta.put("title", title.substring(0, Math.min(300, title.length())));
        latest =
            new MediaCandidate(
                previous.getUrl(),
                previous.getType(),
                previous.getSource(),
                previous.getPageUrl(),
                previous.getHeaders(),
                previous.getDetectedAt(),
                meta);
        entry.setValue(latest);
      }
    }
    if (latest != null && listener != null) listener.onCandidateAdded(latest, groups.size());
  }

  public synchronized List<MediaCandidate> snapshot() {
    List<MediaCandidate> ordered = new ArrayList<>(groups.values());
    ordered.sort((a, b) -> Integer.compare(frameScore(b), frameScore(a)));
    return ordered;
  }

  private static int frameScore(MediaCandidate item) {
    try {
      return Math.max(
          0, Math.min(100, Integer.parseInt(item.getMetadata().getOrDefault("frameScore", "0"))));
    } catch (Exception ignored) {
      return 0;
    }
  }

  public synchronized MediaCandidate latest(String identity) {
    MediaCandidate hit = groups.get(identity);
    if (hit != null) return hit;
    for (MediaCandidate item : groups.values())
      if (item.getIdentityKey().equals(identity)) return item;
    return null;
  }

  private static MediaCandidate.MediaType classify(String path, String contentType) {
    String mime =
        contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    if (mime.contains("mpegurl")) return MediaCandidate.MediaType.HLS;
    if (mime.contains("dash+xml")) return MediaCandidate.MediaType.DASH;
    if (mime.startsWith("video/")) return MediaCandidate.MediaType.VIDEO;
    if (mime.startsWith("audio/")) return MediaCandidate.MediaType.AUDIO;
    if (!mime.isEmpty()
        && !Set.of(
                "application/octet-stream",
                "application/force-download",
                "binary/octet-stream",
                "application/mp4")
            .contains(mime)) return MediaCandidate.MediaType.UNKNOWN;
    if (path.endsWith(".m3u8")) return MediaCandidate.MediaType.HLS;
    if (path.endsWith(".mpd")) return MediaCandidate.MediaType.DASH;
    if (path.matches(".*[.](mp4|webm|m4v|mov|mkv)$")) return MediaCandidate.MediaType.VIDEO;
    if (path.matches(".*[.](m4a|mp3|aac|ogg|opus)$")) return MediaCandidate.MediaType.AUDIO;
    return MediaCandidate.MediaType.UNKNOWN;
  }
}
