package com.adownloader.app.download;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Recent successful playback evidence, not a general HTTP cache. No segment bodies. */
public final class ManifestResponseCache implements AutoCloseable {
  public static final int MAX_BODY = 8 * 1024 * 1024, MAX_BYTES = 16 * 1024 * 1024;
  public static final long TTL = 60000;

  private record Key(int tab, long frame, String page, String url) {}

  private record Entry(
      MediaNetworkSession.Bootstrap response, long at, long order, List<String> aliases) {}

  private static final class Pending {
    final Key key;
    final MediaNetworkSession.Observation observation;
    final Map<String, String> metadata;
    final List<String> cookies;
    final List<String> aliases;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int index;
    final long order;

    Pending(
        Key key,
        MediaNetworkSession.Observation o,
        Map<String, String> metadata,
        List<String> cookies,
        long order) {
      this.order = order;
      this.key = key;
      observation = o;
      this.metadata = Map.copyOf(metadata);
      this.cookies = List.copyOf(cookies);
      this.aliases = redirectAliases(metadata, key.url);
    }
  }

  private long starts, chunkCalls, ends, peak;
  private String lastReject = "NONE";

  public synchronized Map<String, Object> metrics() {
    return Map.of(
        "starts",
        starts,
        "chunkCalls",
        chunkCalls,
        "ends",
        ends,
        "ready",
        ready.size(),
        "pending",
        pending.size(),
        "boundPages",
        pages.size(),
        "logicalBytes",
        weight(),
        "peakBytes",
        peak,
        "lastReject",
        lastReject);
  }

  private boolean reject(String reason) {
    lastReject = reason;
    return false;
  }

  private final LongSupplier clock;
  private final Map<Integer, String> pages = new HashMap<>();
  private final LinkedHashMap<Key, Entry> ready = new LinkedHashMap<>();
  private final Map<String, Pending> pending = new LinkedHashMap<>();

  public ManifestResponseCache() {
    this(System::currentTimeMillis);
  }

  public ManifestResponseCache(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized void page(int tab, String page) {
    if (!Objects.equals(pages.get(tab), page)) {
      discard(tab);
      if (validUrl(page)) pages.put(tab, page);
    }
  }

  public synchronized void discard(int tab) {
    pages.remove(tab);
    ready.keySet().removeIf(k -> k.tab == tab);
    pending.values().removeIf(p -> p.key.tab == tab);
    notifyAll();
  }

  private static boolean validUrl(String url) {
    try {
      var u = java.net.URI.create(url);
      return Set.of("http", "https").contains(u.getScheme())
          && u.getHost() != null
          && u.getUserInfo() == null
          && url.length() <= 32768;
    } catch (Exception e) {
      return false;
    }
  }

  private static List<String> redirectAliases(Map<String, String> metadata, String finalUrl) {
    List<String> urls = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    int bytes = 0;
    if (metadata.containsKey("redirect-source-8")) return null;
    for (int i = 0; i < 8; i++) {
      String u = metadata.get("redirect-source-" + i);
      if (u == null) {
        for (int j = i + 1; j < 8; j++)
          if (metadata.containsKey("redirect-source-" + j)) return null;
        break;
      }
      if (!validUrl(u) || u.equals(finalUrl) || !unique.add(u) || (bytes += u.length()) > 65536)
        return null;
      urls.add(u);
    }
    for (int i = 0; i < urls.size(); i++) {
      String next = i + 1 < urls.size() ? urls.get(i + 1) : finalUrl;
      if (urls.get(i).startsWith("https:") && !next.startsWith("https:")) return null;
    }
    return List.copyOf(urls);
  }

  private static boolean matches(
      Key key, List<String> aliases, String url, String page, int tab, long frame) {
    return key.tab == tab
        && key.frame == frame
        && key.page.equals(page)
        && (key.url.equals(url) || aliases.contains(url));
  }

  private static boolean overlaps(Key a, List<String> aa, Key b, List<String> ba) {
    return a.tab == b.tab
        && a.frame == b.frame
        && a.page.equals(b.page)
        && (a.url.equals(b.url)
            || aa.contains(b.url)
            || ba.contains(a.url)
            || aa.stream().anyMatch(ba::contains));
  }

  private void expire() {
    long now = clock.getAsLong();
    ready.values().removeIf(e -> now - e.at < 0 || now - e.at > TTL);
    pending
        .values()
        .removeIf(p -> now - p.observation.at() < 0 || now - p.observation.at() > 10000);
  }

  public synchronized long weight() {
    long n = 0;
    for (var e : ready.values()) n += e.response.body().length;
    for (var p : pending.values()) n += p.bytes.size();
    return n;
  }

  public synchronized int size() {
    expire();
    return ready.size();
  }

  public synchronized boolean begin(
      String id,
      MediaNetworkSession.Observation o,
      Map<String, String> metadata,
      List<String> cookies) {
    starts++;
    expire();
    if (!Objects.equals(pages.get(o.tab()), o.page())) return reject("PAGE_NOT_BOUND");
    if (id == null
        || !id.matches("response-[0-9]{1,18}")
        || pending.containsKey(id)
        || pending.size() >= 4
        || o.tab() < 0
        || o.frame() < 0
        || o.status() != 200
        || !validUrl(o.url())
        || !Objects.equals(pages.get(o.tab()), o.page())
        || Math.abs(clock.getAsLong() - o.at()) > 10000) return reject("BEGIN_VALIDATION");
    List<String> aliases = redirectAliases(metadata, o.url());
    if (aliases == null) return reject("REDIRECT_VALIDATION");
    Key key = new Key(o.tab(), o.frame(), o.page(), o.url());
    long order;
    try {
      order = Long.parseLong(metadata.getOrDefault("request-started-at", Long.toString(o.at())));
    } catch (NumberFormatException invalid) {
      return reject("ORDER_PARSE");
    }
    if (order <= 0 || order > o.at()) return reject("ORDER_TIMESTAMP");
    if (ready.entrySet().stream()
        .anyMatch(
            e ->
                overlaps(e.getKey(), e.getValue().aliases, key, aliases)
                    && e.getValue().order > order)) return reject("OLDER_REQUEST");
    if (pending.values().stream()
        .anyMatch(p -> overlaps(p.key, p.aliases, key, aliases) && p.order > order))
      return reject("OLDER_REQUEST");
    pending.values().removeIf(p -> overlaps(p.key, p.aliases, key, aliases));
    ready.entrySet().removeIf(e -> overlaps(e.getKey(), e.getValue().aliases, key, aliases));
    pending.put(id, new Pending(key, o, metadata, cookies, order));
    notifyAll();
    return true;
  }

  public synchronized boolean chunk(String id, int tab, int index, byte[] bytes) {
    expire();
    chunkCalls++;
    Pending p = pending.get(id);
    if (p == null || p.key.tab != tab) return reject("MISSING_PENDING");
    if (index != p.index
        || bytes.length == 0
        || bytes.length > 49152
        || p.bytes.size() + (long) bytes.length > MAX_BODY) {
      abort(id, tab);
      return false;
    }
    while (weight() + bytes.length > MAX_BYTES && !ready.isEmpty())
      ready.remove(ready.keySet().iterator().next());
    if (weight() + bytes.length > MAX_BYTES) {
      abort(id, tab);
      return false;
    }
    p.bytes.write(bytes, 0, bytes.length);
    p.index++;
    peak = Math.max(peak, weight());
    return true;
  }

  public synchronized boolean end(String id, int tab, int chunks, int bytes) {
    ends++;
    expire();
    Pending p = pending.get(id);
    if (p == null || p.key.tab != tab) return false;
    pending.remove(id);
    notifyAll();
    if (chunks != p.index
        || bytes != p.bytes.size()
        || bytes == 0
        || !Objects.equals(pages.get(tab), p.key.page)) return false;
    byte[] body = p.bytes.toByteArray();
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString()
              .replaceFirst("^\\uFEFF", "")
              .stripLeading();
    } catch (CharacterCodingException e) {
      return false;
    }
    if (!text.startsWith("#EXTM3U")
        && !text.matches(
            "(?s)(?:<\\?xml[^>]*>\\s*)?(?:<!--[\\s\\S]*?-->\\s*)*<(?:[\\w.-]+:)?MPD(?:\\s|>).*"))
      return false;
    Map<String, List<String>> cookies =
        p.cookies.isEmpty() ? Map.of() : Map.of(p.key.url, p.cookies);
    Map<String, String> metadata = new LinkedHashMap<>(p.metadata);
    metadata.put("evidence-expires-at", Long.toString(p.observation.at() + TTL));
    var response =
        new MediaNetworkSession.Bootstrap(
            p.key.url,
            p.observation.status(),
            p.aliases.size(),
            metadata,
            body,
            cookies,
            p.observation.headers());
    ready.put(p.key, new Entry(response, p.observation.at(), p.order, p.aliases));
    while (ready.size() > 16 || weight() > MAX_BYTES)
      ready.remove(ready.keySet().iterator().next());
    return true;
  }

  public synchronized void abort(String id, int tab) {
    Pending p = pending.get(id);
    if (p != null && p.key.tab == tab) {
      pending.remove(id);
      notifyAll();
    }
  }

  public synchronized MediaNetworkSession.Bootstrap get(
      String url, String page, int tab, long frame, TransferControl control) throws Exception {
    if (tab < 0 || frame < 0 || !Objects.equals(pages.get(tab), page)) return null;
    Key key = new Key(tab, frame, page, url);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while (true) {
      control.check();
      expire();
      if (!Objects.equals(pages.get(tab), page)) return null;
      Entry hit = null;
      for (var e : ready.entrySet())
        if (matches(e.getKey(), e.getValue().aliases, url, page, tab, frame)
            && (hit == null || e.getValue().order > hit.order)) hit = e.getValue();
      if (hit != null) {
        var response = hit.response;
        if (response.url().equals(url)) return response;
        Map<String, String> meta = new LinkedHashMap<>(response.metadata());
        meta.put("captured-request-url", url);
        return new MediaNetworkSession.Bootstrap(
            response.url(),
            response.status(),
            response.redirects(),
            meta,
            response.body(),
            response.responseCookies(),
            response.requestHeaders());
      }
      if (pending.values().stream().noneMatch(p -> matches(p.key, p.aliases, url, page, tab, frame))
          || System.nanoTime() > deadline) return null;
      wait(50);
    }
  }

  @Override
  public synchronized void close() {
    ready.clear();
    pending.clear();
    pages.clear();
    notifyAll();
  }
}
