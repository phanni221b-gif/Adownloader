package com.adownloader.app.playback;

import java.util.*;

/**
 * Last line of defence before export: every string in the report is re-checked, and a string that
 * could carry a URL, query, credential or header value is replaced. The report is built from
 * reduced values already; this pass guarantees it even if a future field forgets.
 */
public final class Privacy {
  private Privacy() {}

  public static final String REDACTED = "[redacted]";

  @SuppressWarnings("unchecked")
  public static <T> T scrub(T value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach(
          (k, v) -> {
            String key = String.valueOf(k);
            if (!key.matches("[A-Za-z0-9_.\\[\\]()-]{1,80}")) return;
            out.put(key, scrub(v));
          });
      return (T) out;
    }
    if (value instanceof Collection<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object v : list) out.add(scrub(v));
      return (T) out;
    }
    if (value instanceof String s) return (T) (safe(s) ? s : REDACTED);
    if (value == null || value instanceof Number || value instanceof Boolean) return value;
    return (T) REDACTED;
  }

  static boolean safe(String s) {
    if (s.length() > 400) return false;
    String l = s.toLowerCase(Locale.ROOT);
    if (l.contains("://") || l.contains("www.") && l.contains("/")) return false;
    if (s.matches("(?s).*[?&][A-Za-z0-9_.-]+=.*")) return false;
    if (l.matches("(?s).*\\b(?:bearer|basic) [a-z0-9._~+/-]{8,}.*")) return false;
    if (l.matches("(?s).*(?:set-)?cookie\\s*[:=].*")) return false;
    // Long opaque tokens (session ids, signatures) are not part of any report value.
    if (s.matches("(?s).*[A-Za-z0-9_-]{48,}.*")) return false;
    return true;
  }
}
