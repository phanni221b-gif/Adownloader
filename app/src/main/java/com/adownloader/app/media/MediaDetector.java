package com.adownloader.app.media;

import com.adownloader.app.MediaCandidate;
import java.net.URI;
import java.util.*;

/** A bounded logical list; every entry retains its newest complete URL and per-request context. */
public final class MediaDetector {
  public interface Listener {
    void onCandidateAdded(MediaCandidate candidate, int totalCount);
  }

  private final LinkedHashMap<String, MediaCandidate> groups = new LinkedHashMap<>();
  private final Map<String, String> exact = new HashMap<>();
  private final Listener listener;

  public MediaDetector(Listener listener) {
    this.listener = listener;
  }

  public synchronized void reset() {
    groups.clear();
    exact.clear();
    if (listener != null) listener.onCandidateAdded(null, 0);
  }

  public void capture(
      String url, String mime, String source, String page, Map<String, String> headers) {
    capture(url, mime, source, page, headers, Collections.emptyMap());
  }

  public synchronized void capture(
      String url,
      String mime,
      String source,
      String page,
      Map<String, String> headers,
      Map<String, String> metadata) {
    if (url == null) return;
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
        if ("network-response".equals(prior.getSource()) && !"network-response".equals(source))
          return;
        if ("network-response".equals(source) && !"network-response".equals(prior.getSource())) {
          exact.remove(prior.getExactKey());
          iterator.remove();
        }
      }
    }
    Map<String, String> safe = new LinkedHashMap<>();
    if (headers != null)
      headers.forEach(
          (k, v) -> {
            if (k != null
                && v != null
                && Set.of("cookie", "referer", "origin", "user-agent")
                    .contains(k.toLowerCase(Locale.ROOT))
                && !v.contains("\r")
                && !v.contains("\n")) safe.put(k, v);
          });
    Map<String, String> meta = new LinkedHashMap<>(metadata);
    if (mime != null && !mime.isBlank()) meta.put("mime", mime);
    MediaCandidate next =
        new MediaCandidate(clean, type, source, page, safe, System.currentTimeMillis(), meta);
    String exactKey = next.getExactKey(), key = exact.getOrDefault(exactKey, next.getIdentityKey());
    MediaCandidate previous = groups.get(key);
    boolean authoritative =
        previous != null
            && "network-response".equals(previous.getSource())
            && !"network-response".equals(source);
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
    if (title == null || title.isBlank()) return;
    MediaCandidate latest = null;
    for (Map.Entry<String, MediaCandidate> entry : groups.entrySet()) {
      MediaCandidate previous = entry.getValue();
      if (Objects.equals(previous.getPageUrl(), page)) {
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
    return new ArrayList<>(groups.values());
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
