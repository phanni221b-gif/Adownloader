package com.adownloader.app.playback;

import java.net.URI;
import java.util.Locale;

/**
 * Session-salted key of a URL's origin and path, identical to the one the extension computes
 * (playback_diag.js / playback_diag_bg.js). It lets a report say "the player fetched the same
 * segment the manifest lists" without carrying either URL: the query and fragment are never part
 * of the key, and the salt is random per diagnostic session, so keys do not link across sessions.
 */
public final class DiagKeys {
  private DiagKeys() {}

  public static String key(String salt, String url) {
    String base = base(url);
    if (base.isEmpty() || salt == null || salt.isEmpty()) return "";
    return fnv(salt + "|" + base) + fnv(base + "|" + salt);
  }

  /** scheme://host[:non-default-port]/path, as the WHATWG URL parser serializes it. */
  static String base(String url) {
    try {
      URI u = URI.create(url.trim());
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https") || u.getHost() == null) return "";
      int port = u.getPort();
      boolean defaultPort =
          port == -1 || scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443;
      String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
      return scheme
          + "://"
          + u.getHost().toLowerCase(Locale.ROOT)
          + (defaultPort ? "" : ":" + port)
          + path;
    } catch (RuntimeException invalid) {
      return "";
    }
  }

  /** 32-bit FNV-1a over UTF-16 code units, matching Math.imul in the extension. */
  static String fnv(String value) {
    int h = 0x811c9dc5;
    for (int i = 0; i < value.length(); i++) h = (h ^ value.charAt(i)) * 16777619;
    return String.format(Locale.ROOT, "%08x", h);
  }
}
