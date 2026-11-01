package com.adownloader.app.download;

import java.net.URI;
import java.util.*;

/** Real observed media requests only. Cookies never generalize beyond the exact request URL. */
public final class CapturedRequestContexts {
  private final LinkedHashMap<String, Map<String, String>> values = new LinkedHashMap<>();

  public CapturedRequestContexts(Map<String, Map<String, String>> source) {
    for (var e : source.entrySet()) {
      if (values.size() >= 128) break;
      try {
        URI uri = URI.create(e.getKey());
        if (uri.getHost() == null
            || uri.getUserInfo() != null
            || !Set.of("http", "https").contains(uri.getScheme())) continue;
        Map<String, String> safe = new LinkedHashMap<>();
        for (var h : e.getValue().entrySet())
          if (Set.of("cookie", "referer", "origin", "user-agent")
                  .contains(h.getKey().toLowerCase(Locale.ROOT))
              && h.getValue() != null
              && h.getValue().length() <= 32768
              && !h.getValue().contains("\r")
              && !h.getValue().contains("\n")) safe.put(h.getKey(), h.getValue());
        values.put(e.getKey(), safe);
      } catch (Exception ignored) {
      }
    }
  }

  private static String directory(URI uri) {
    String p = uri.getRawPath();
    return p == null ? "/" : p.substring(0, p.lastIndexOf('/') + 1);
  }

  public Map<String, String> forUrl(String target) {
    Map<String, String> exact = values.get(target);
    if (exact != null) return exact;
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
            if (!h.getKey().equalsIgnoreCase("cookie")) result.put(h.getKey(), h.getValue());
          longest = dir.length();
        }
      }
    } catch (Exception ignored) {
    }
    return result;
  }

  public void close() {
    values.clear();
  }
}
