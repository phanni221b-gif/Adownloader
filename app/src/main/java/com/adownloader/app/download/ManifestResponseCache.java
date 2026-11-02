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

  private record Key(int tab, int frame, String page, String url) {}

  private record Entry(MediaNetworkSession.Bootstrap response, long at) {}

  private static final class Pending {
    final Key key;
    final MediaNetworkSession.Observation observation;
    final Map<String, String> metadata;
    final List<String> cookies;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int index;

    Pending(
        Key key,
        MediaNetworkSession.Observation o,
        Map<String, String> metadata,
        List<String> cookies) {
      this.key = key;
      observation = o;
      this.metadata = Map.copyOf(metadata);
      this.cookies = List.copyOf(cookies);
    }
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
    expire();
    if (id == null
        || !id.matches("response-[0-9]{1,18}")
        || pending.containsKey(id)
        || pending.size() >= 4
        || o.tab() < 0
        || o.frame() < 0
        || o.status() != 200
        || !validUrl(o.url())
        || !Objects.equals(pages.get(o.tab()), o.page())
        || Math.abs(clock.getAsLong() - o.at()) > 10000) return false;
    Key key = new Key(o.tab(), o.frame(), o.page(), o.url());
    Entry previous = ready.get(key);
    if (previous != null && previous.at > o.at()) return false;
    if (pending.values().stream().anyMatch(p -> p.key.equals(key) && p.observation.at() > o.at()))
      return false;
    // A newer request replaces evidence for this exact candidate binding.
    pending.values().removeIf(p -> p.key.equals(key));
    ready.remove(key);
    pending.put(id, new Pending(key, o, metadata, cookies));
    notifyAll();
    return true;
  }

  public synchronized boolean chunk(String id, int tab, int index, byte[] bytes) {
    expire();
    Pending p = pending.get(id);
    if (p == null || p.key.tab != tab) return false;
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
    return true;
  }

  public synchronized boolean end(String id, int tab, int chunks, int bytes) {
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
            p.key.url, p.observation.status(), 0, metadata, body, cookies, p.observation.headers());
    ready.put(p.key, new Entry(response, p.observation.at()));
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
      String url, String page, int tab, int frame, TransferControl control) throws Exception {
    if (tab < 0 || frame < 0 || !Objects.equals(pages.get(tab), page)) return null;
    Key key = new Key(tab, frame, page, url);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while (true) {
      control.check();
      expire();
      if (!Objects.equals(pages.get(tab), page)) return null;
      Entry hit = ready.get(key);
      if (hit != null) return hit.response;
      if (pending.values().stream().noneMatch(p -> p.key.equals(key))
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
