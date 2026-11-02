package com.adownloader.app.download;

import java.net.URI;
import java.util.*;

/** Values are internal request context. Only names may cross the diagnostics boundary. */
public final class MediaHeaders {
  private MediaHeaders() {}

  private static final Set<String> BASIC =
      Set.of("cookie", "referer", "origin", "user-agent", "accept", "accept-language");
  private static final Set<String> NEVER =
      Set.of(
          "host",
          "connection",
          "content-length",
          "transfer-encoding",
          "accept-encoding",
          "range",
          "proxy-authorization");

  public static boolean allowed(String name) {
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    return n.matches("[a-z0-9!#$%&'*+.^_`|~-]{1,64}")
        && !NEVER.contains(n)
        && (BASIC.contains(n)
            || n.equals("authorization")
            || n.matches(
                "x-(?:playback|media|stream|video|auth|access|session|cdn|api|token|signature|signed|csrf|xsrf)(?:-.*)?")
            || n.equals("x-requested-with"));
  }

  public static boolean sensitive(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    return n.equals("cookie") || n.equals("authorization") || n.startsWith("x-");
  }

  public static Map<String, String> select(Map<String, String> source) {
    Map<String, String> out = new LinkedHashMap<>();
    int bytes = 0;
    for (var e : source.entrySet()) {
      String n = e.getKey(), v = e.getValue();
      if (!allowed(n)
          || v == null
          || v.length() > 32768
          || v.chars().anyMatch(c -> c < 32 || c == 127)
          || out.size() >= 16
          || bytes + n.length() + v.length() > 65536) continue;
      out.put(canonical(n), v);
      bytes += n.length() + v.length();
    }
    return out;
  }

  public static String canonical(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "cookie" -> "Cookie";
      case "authorization" -> "Authorization";
      case "referer" -> "Referer";
      case "origin" -> "Origin";
      case "user-agent" -> "User-Agent";
      case "accept" -> "Accept";
      case "accept-language" -> "Accept-Language";
      default -> name;
    };
  }

  public static String origin(String value) {
    try {
      URI u = URI.create(value);
      if (u.getHost() == null
          || u.getUserInfo() != null
          || !Set.of("http", "https").contains(u.getScheme().toLowerCase(Locale.ROOT))) return "";
      return u.getScheme().toLowerCase(Locale.ROOT)
          + "://"
          + u.getHost().toLowerCase(Locale.ROOT)
          + ":"
          + (u.getPort() < 0 ? (u.getScheme().equalsIgnoreCase("https") ? 443 : 80) : u.getPort());
    } catch (Exception e) {
      return "";
    }
  }

  public static List<String> names(Collection<String> source) {
    TreeSet<String> out = new TreeSet<>();
    for (String n : source)
      if (n != null && n.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}") && out.size() < 48)
        out.add(n.toLowerCase(Locale.ROOT));
    return new ArrayList<>(out);
  }
}
