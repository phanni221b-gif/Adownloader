package com.adownloader.app.download;

import java.net.URI;
import java.util.*;

/** Real observed media requests only. Cookies never generalize beyond the exact request URL. */
public final class CapturedRequestContexts {
  private final LinkedHashMap<String, Map<String, String>> values = new LinkedHashMap<>();

  public CapturedRequestContexts(Map<String, Map<String, String>> source) {
    this(source, false);
  }

  public CapturedRequestContexts(Map<String, Map<String, String>> source, boolean verified) {
    for (var e : source.entrySet()) {
      if (values.size() >= 128) break;
      try {
        URI uri = URI.create(e.getKey());
        if (uri.getHost() == null
            || uri.getUserInfo() != null
            || !Set.of("http", "https").contains(uri.getScheme())) continue;
        Map<String, String> safe = MediaHeaders.select(e.getValue());
        if (!verified)
          safe.keySet().removeIf(n -> MediaHeaders.sensitive(n) && !n.equalsIgnoreCase("cookie"));
        values.put(e.getKey(), safe);
      } catch (Exception ignored) {
      }
    }
  }

  private static String normalized(String url) {
    try {
      URI u = URI.create(url);
      String origin = MediaHeaders.origin(url);
      if (origin.isEmpty()) return "";
      String path = u.getRawPath();
      return origin
          + (path == null || path.isEmpty() ? "/" : path)
          + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String directory(URI uri) {
    String p = uri.getRawPath();
    return p == null ? "/" : p.substring(0, p.lastIndexOf('/') + 1);
  }

  public synchronized Map<String, String> forUrl(String target) {
    Map<String, String> exact = values.get(target);
    if (exact != null) return new LinkedHashMap<>(exact);
    String normalized = normalized(target);
    if (!normalized.isEmpty())
      for (var e : values.entrySet())
        if (normalized.equals(normalized(e.getKey()))) return new LinkedHashMap<>(e.getValue());
    Map<String, String> result = new LinkedHashMap<>();
    int longest = -1;
    try {
      URI dest = URI.create(target);
      for (var e : values.entrySet()) {
        URI uri = URI.create(e.getKey());
        String dir = directory(uri);
        if (Objects.equals(uri.getScheme(), dest.getScheme())
            && Objects.equals(uri.getRawAuthority(), dest.getRawAuthority())
            && dest.getRawPath().startsWith(dir)
            && dir.length() > longest) {
          result.clear();
          for (var h : e.getValue().entrySet())
            if (!MediaHeaders.sensitive(h.getKey())) result.put(h.getKey(), h.getValue());
          longest = dir.length();
        }
      }
    } catch (Exception ignored) {
    }
    return result;
  }

  public synchronized void update(String url, Map<String, String> headers) {
    if (MediaHeaders.origin(url).isEmpty()) return;
    values.put(url, MediaHeaders.select(headers));
    while (values.size() > 128) values.remove(values.keySet().iterator().next());
  }

  public synchronized void close() {
    values.clear();
  }
}
