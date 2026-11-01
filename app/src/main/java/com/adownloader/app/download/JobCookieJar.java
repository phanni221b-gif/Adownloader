package com.adownloader.app.download;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * No global CookieHandler, browser export, or cross-job state. Snapshot is encrypted by JobJournal.
 */
public final class JobCookieJar implements AutoCloseable {
  private static final class Entry {
    String name, value, domain, path, sameSite;
    boolean hostOnly, secure;
    long expires;
  }

  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
  private final String sourceOrigin;
  private final URI sourceUri;
  private String pageSite;

  public synchronized void pageContext(String page) {
    try {
      URI u = URI.create(page);
      if (u.getHost() != null && Set.of("http", "https").contains(u.getScheme()))
        pageSite = CookieSites.site(u);
    } catch (Exception ignored) {
    }
  }

  private String initial;

  public JobCookieJar(String source, Map<String, String> headers) {
    sourceUri = URI.create(source);
    sourceOrigin = origin(sourceUri);
    initial = header(headers, "cookie");
    String referer = header(headers, "referer");
    URI page;
    try {
      page = URI.create(referer.isEmpty() ? source : referer);
      if (page.getHost() == null) page = URI.create(source);
    } catch (Exception e) {
      page = URI.create(source);
    }
    pageSite = CookieSites.site(page);
  }

  private static String header(Map<String, String> headers, String key) {
    return headers.entrySet().stream()
        .filter(
            e ->
                e.getKey().equalsIgnoreCase(key)
                    && e.getValue() != null
                    && e.getValue().length() <= 32768
                    && !e.getValue().contains("\r")
                    && !e.getValue().contains("\n"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("");
  }

  private static String origin(URI u) {
    return u.getScheme()
        + "://"
        + u.getHost()
        + ":"
        + (u.getPort() < 0 ? ("https".equals(u.getScheme()) ? 443 : 80) : u.getPort());
  }

  private static boolean domain(String host, String domain) {
    return host.equals(domain) || host.endsWith("." + domain);
  }

  private static boolean path(String target, String cookie) {
    return target.equals(cookie)
        || target.startsWith(cookie)
            && (cookie.endsWith("/")
                || target.length() > cookie.length() && target.charAt(cookie.length()) == '/');
  }

  public synchronized boolean accept(URI response, Map<String, List<String>> headers) {
    boolean changed = false;
    for (var h : headers.entrySet())
      if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie"))
        for (String raw : h.getValue()) {
          if (raw == null || raw.length() > 4096 || raw.contains("\r") || raw.contains("\n"))
            continue;
          try {
            for (HttpCookie c : HttpCookie.parse(raw)) {
              String host = CookieSites.normal(response.getHost()), d = c.getDomain();
              boolean hostOnly = d == null;
              d = hostOnly ? host : CookieSites.normal(d.startsWith(".") ? d.substring(1) : d);
              if (d.isEmpty()
                  || !domain(host, d)
                  || !hostOnly && CookieSites.publicSuffix(d)
                  || (host.matches("[0-9.]+") || host.contains(":")) && !host.equals(d)) continue;
              boolean secure = c.getSecure();
              if (secure && !"https".equalsIgnoreCase(response.getScheme())) continue;
              String p = c.getPath();
              if (p == null || !p.startsWith("/")) {
                p = response.getPath();
                int slash = p.lastIndexOf('/');
                p = slash <= 0 ? "/" : p.substring(0, slash);
              }
              String same = "lax";
              for (String a : raw.split(";")) {
                String[] kv = a.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equalsIgnoreCase("samesite"))
                  same = kv[1].trim().toLowerCase(Locale.ROOT);
              }
              if (same.equals("none") && !secure) continue;
              if (c.getName().startsWith("__Secure-") && !secure
                  || c.getName().startsWith("__Host-") && (!secure || !hostOnly || !p.equals("/")))
                continue;
              if (domain(CookieSites.normal(sourceUri.getHost()), d)
                  && path(sourceUri.getPath().isEmpty() ? "/" : sourceUri.getPath(), p)) {
                String before = initial;
                List<String> kept = new ArrayList<>();
                for (String pair : initial.split(";"))
                  if (!pair.trim().split("=", 2)[0].equals(c.getName())) kept.add(pair.trim());
                initial = String.join("; ", kept);
                changed |= !initial.equals(before);
              }
              String key = d + "|" + p + "|" + c.getName();
              if (c.getMaxAge() == 0) {
                changed |= entries.remove(key) != null;
                continue;
              }
              Entry e = new Entry();
              e.name = c.getName();
              e.value = c.getValue();
              e.domain = d;
              e.path = p;
              e.hostOnly = hostOnly;
              e.secure = secure;
              e.sameSite = same;
              long max = c.getMaxAge();
              e.expires =
                  max < 0
                      ? Long.MAX_VALUE
                      : max > (Long.MAX_VALUE - System.currentTimeMillis()) / 1000
                          ? Long.MAX_VALUE
                          : System.currentTimeMillis() + max * 1000;
              entries.remove(key);
              entries.put(key, e);
              while (entries.size() > 128) entries.remove(entries.keySet().iterator().next());
              changed = true;
            }
          } catch (IllegalArgumentException ignored) {
          }
        }
    return changed;
  }

  public synchronized String header(URI target) {
    return header(target, null);
  }

  public synchronized String header(URI target, String observed) {
    LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
    if (observed != null || sourceOrigin.equals(origin(target)))
      for (String pair : (observed == null ? initial : observed).split(";")) {
        String[] kv = pair.trim().split("=", 2);
        if (kv.length == 2 && !kv[0].isEmpty()) cookies.put(kv[0], kv[1]);
      }
    entries.values().removeIf(e -> e.expires <= System.currentTimeMillis());
    List<Entry> selected = new ArrayList<>();
    String host = CookieSites.normal(target.getHost()),
        p = target.getPath().isEmpty() ? "/" : target.getPath();
    for (Entry e : entries.values())
      if ((e.hostOnly ? host.equals(e.domain) : domain(host, e.domain))
          && path(p, e.path)
          && (!e.secure || "https".equals(target.getScheme()))
          && (e.sameSite.equals("none") || pageSite.equals(CookieSites.site(target))))
        selected.add(e);
    selected.sort((a, b) -> Integer.compare(b.path.length(), a.path.length()));
    Set<String> set = new HashSet<>();
    for (Entry e : selected) if (set.add(e.name)) cookies.put(e.name, e.value);
    List<String> pairs = new ArrayList<>();
    cookies.forEach((k, v) -> pairs.add(k + "=" + v));
    return String.join("; ", pairs);
  }

  public synchronized boolean hasResponseCookies() {
    return !entries.isEmpty();
  }

  public synchronized String snapshot() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeUTF(sourceOrigin);
    out.writeUTF(initial);
    out.writeInt(entries.size());
    for (Entry e : entries.values()) {
      out.writeUTF(e.name);
      out.writeUTF(e.value);
      out.writeUTF(e.domain);
      out.writeUTF(e.path);
      out.writeUTF(e.sameSite);
      out.writeBoolean(e.hostOnly);
      out.writeBoolean(e.secure);
      out.writeLong(e.expires);
    }
    out.close();
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  public synchronized void restore(String state) throws IOException {
    if (state == null || state.isEmpty()) return;
    if (state.length() > 1048576) throw new IOException("COOKIE_STATE_INVALID");
    try {
      DataInputStream in =
          new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(state)));
      if (!in.readUTF().equals(sourceOrigin)) return;
      String seed = in.readUTF();
      if (seed.length() > 32768 || seed.contains("\r") || seed.contains("\n"))
        throw new IOException("COOKIE_STATE_INVALID");
      initial = seed;
      int n = in.readInt();
      if (n < 0 || n > 128) throw new IOException("COOKIE_STATE_INVALID");
      for (int i = 0; i < n; i++) {
        Entry e = new Entry();
        e.name = in.readUTF();
        e.value = in.readUTF();
        e.domain = in.readUTF();
        e.path = in.readUTF();
        e.sameSite = in.readUTF();
        e.hostOnly = in.readBoolean();
        e.secure = in.readBoolean();
        e.expires = in.readLong();
        entries.put(e.domain + "|" + e.path + "|" + e.name, e);
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("COOKIE_STATE_INVALID");
    }
  }

  @Override
  public synchronized void close() {
    entries.clear();
    initial = "";
  }
}
