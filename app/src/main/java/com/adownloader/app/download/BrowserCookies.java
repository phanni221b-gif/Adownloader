package com.adownloader.app.download;

import java.net.URI;
import java.util.*;

/**
 * Per-request lookup of the live browser cookie store, the counterpart of WebView's
 * CookieManager.getCookie(url) that a WebView-based downloader asks for on every request and
 * redirect hop.
 *
 * <p>Why: a candidate found in the DOM, or a segment/CDN host reached through a redirect, carries
 * no observed Cookie of its own. The job cookie jar only knows the seed observed for the source
 * origin and Set-Cookie answers this job received, so a CDN cookie the player's session holds was
 * never sent by the direct stack (diagnostic {@code cookiePresent:false}). The browser decides
 * which cookies belong to the URL (domain, path, Secure, expiry, partition for the top-level page);
 * this class never generalizes a value to another URL and never persists or logs one.
 */
public final class BrowserCookies {
  private BrowserCookies() {}

  /** Implemented by the browser layer; must return within a short bound and never throw. */
  public interface Lookup {
    /** Cookie header value for {@code url} as the page {@code topLevelPage} would send it. */
    String cookieHeader(String url, String topLevelPage) throws Exception;
  }

  private static volatile Lookup lookup;
  private static final long TTL_MS = 15_000;
  private static final int MAX_ENTRIES = 64;

  private record Cached(String value, long at) {}

  private static final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>();

  public static void install(Lookup value) {
    lookup = value;
    clear();
  }

  public static void remove(Lookup value) {
    if (lookup == value) {
      lookup = null;
      clear();
    }
  }

  public static boolean available() {
    return lookup != null;
  }

  public static void clear() {
    synchronized (cache) {
      cache.clear();
    }
  }

  /**
   * Segments of one playlist share host, path directory and page; cookies are path-scoped, so the
   * directory is part of the key and the same value is not reused for a sibling path.
   */
  static String key(String url, String page) {
    try {
      URI u = URI.create(url);
      String origin = MediaHeaders.origin(url);
      String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
      return origin.isEmpty()
          ? ""
          : origin + path.substring(0, path.lastIndexOf('/') + 1) + "\n" + MediaHeaders.origin(page);
    } catch (RuntimeException invalid) {
      return "";
    }
  }

  /** Returns "" when unavailable, not applicable or failed; never null. */
  public static String forUrl(String url, String page) {
    Lookup current = lookup;
    if (current == null || page == null || page.isEmpty()) return "";
    String key = key(url, page);
    if (key.isEmpty() || MediaHeaders.origin(page).isEmpty()) return "";
    long now = System.currentTimeMillis();
    synchronized (cache) {
      Cached hit = cache.get(key);
      if (hit != null && now - hit.at >= 0 && now - hit.at <= TTL_MS) return hit.value;
    }
    String value;
    try {
      value = sanitize(current.cookieHeader(url, page));
    } catch (Exception unavailable) {
      return "";
    }
    synchronized (cache) {
      cache.remove(key);
      cache.put(key, new Cached(value, now));
      while (cache.size() > MAX_ENTRIES) cache.remove(cache.keySet().iterator().next());
    }
    return value;
  }

  /** One cookie the browser holds for the asked URL (already domain/path/Secure/expiry matched). */
  public record Cookie(String name, String value, String sameSite, boolean partitioned) {}

  /** The top-level site the browser keys partitioned third-party cookies by. */
  public static String topLevelSite(String page) {
    try {
      URI u = URI.create(page);
      return u.getHost() == null ? "" : CookieSites.site(u);
    } catch (RuntimeException invalid) {
      return "";
    }
  }

  /**
   * Which of the browser's cookies for {@code url} a subresource request from {@code page} carries.
   * Same-site: every cookie. Cross-site: the cookies partitioned under this page's site, plus
   * unpartitioned cookies explicitly marked SameSite=None (a Lax/Strict cookie is never sent on a
   * cross-site subresource). A partitioned cookie wins over an unpartitioned one of the same name.
   */
  public static String select(List<Cookie> cookies, String url, String page) {
    String target = topLevelSite(url), top = topLevelSite(page);
    if (target.isEmpty() || top.isEmpty()) return "";
    boolean sameSite = target.equals(top);
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (int pass = 0; pass < 2; pass++)
      for (Cookie c : cookies) {
        if (c == null || c.name() == null || c.value() == null) continue;
        if (c.partitioned() != (pass == 1)) continue;
        if (!c.partitioned()
            && !sameSite
            && !"no_restriction".equalsIgnoreCase(String.valueOf(c.sameSite()))) continue;
        out.remove(c.name());
        out.put(c.name(), c.value());
      }
    List<String> pairs = new ArrayList<>();
    out.forEach((k, v) -> pairs.add(k + "=" + v));
    return sanitize(String.join("; ", pairs));
  }

  static String sanitize(String raw) {
    if (raw == null || raw.length() > 32768) return "";
    List<String> pairs = new ArrayList<>();
    for (String pair : raw.split(";")) {
      String p = pair.trim();
      int eq = p.indexOf('=');
      if (eq <= 0 || p.chars().anyMatch(c -> c < 32 || c == 127)) continue;
      if (!p.substring(0, eq).matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,256}")) continue;
      pairs.add(p);
      if (pairs.size() >= 64) break;
    }
    return String.join("; ", pairs);
  }

  /**
   * Merges cookie headers; a later source overrides an earlier one by name. Callers order them
   * from oldest evidence to newest (observed snapshot, live browser jar, this job's Set-Cookie).
   */
  public static String merge(String... headers) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (String header : headers)
      if (header != null)
        for (String pair : header.split(";")) {
          String[] kv = pair.trim().split("=", 2);
          if (kv.length == 2 && !kv[0].isEmpty()) {
            out.remove(kv[0]);
            out.put(kv[0], kv[1]);
          }
        }
    List<String> pairs = new ArrayList<>();
    out.forEach((k, v) -> pairs.add(k + "=" + v));
    return String.join("; ", pairs);
  }
}
