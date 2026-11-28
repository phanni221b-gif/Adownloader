package com.adownloader.app.playback;

import com.adownloader.app.MediaCandidate;
import com.adownloader.app.download.FailureDiagnostics;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * One user-started live playback diagnostic session. It records, side by side, what the in-page
 * player did (element state, buffering, errors, EME, the player's own network requests including
 * successful ones, Gecko's MediaSession state) and what the download request stack received for
 * the same candidate (PlaybackProbe), so a report shows exactly where the two diverge.
 *
 * <p>Everything stored is already reduced: hosts, enum-like states, numbers and session-salted
 * URL keys. No URL, query, header value, cookie or body is accepted from the extension; anything
 * that does not match the expected shape is dropped on intake.
 */
public final class PlaybackDiagnostics {
  private static final Object LOCK = new Object();
  private static Session current;

  private PlaybackDiagnostics() {}

  public static Session start() {
    synchronized (LOCK) {
      current = new Session();
      return current;
    }
  }

  public static Session current() {
    synchronized (LOCK) {
      return current;
    }
  }

  public static boolean active() {
    Session s = current();
    return s != null && s.stoppedAt == 0;
  }

  /** Ends collection; the session stays available for its report until the next start. */
  public static Session stop() {
    synchronized (LOCK) {
      if (current != null && current.stoppedAt == 0) current.stoppedAt = System.currentTimeMillis();
      return current;
    }
  }

  public static void discard() {
    synchronized (LOCK) {
      current = null;
    }
  }

  /** The extension's poll: ingests its batch and answers the state it must apply. */
  public static Map<String, Object> poll(Map<String, Object> batch) {
    Session s = current();
    boolean live = s != null && s.stoppedAt == 0;
    if (live) s.polled();
    if (live && batch != null && s.id.equals(batch.get("session"))) s.ingest(batch);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("active", live);
    state.put("salt", live ? s.salt : "");
    state.put("session", live ? s.id : "");
    return state;
  }

  /** GeckoView's official MediaSession.Delegate callbacks for the tab's active media. */
  public static void mediaSession(String event, double position, double duration) {
    Session s = current();
    if (s != null && s.stoppedAt == 0) s.mediaSessionEvent(event, position, duration);
  }

  public static void contentProcess(String event) {
    Session s = current();
    if (s != null && s.stoppedAt == 0) s.processEvent(event);
  }

  static final class Element {
    String tag = "", firstKind = "", lastKind = "";
    final SortedSet<String> srcKinds = new TreeSet<>(), srcHosts = new TreeSet<>();
    final Set<String> srcKeys = new LinkedHashSet<>();
    final Map<String, Long> events = new TreeMap<>();
    final SortedSet<String> initDataTypes = new TreeSet<>();
    final SortedSet<Integer> errorCodes = new TreeSet<>();
    final List<String> errorMessages = new ArrayList<>();
    double firstTime = -1, maxTime = -1, lastTime = -1, duration = -1, maxAhead, maxBuffered, advanced;
    int samples, maxReady, lastReady, lastNetwork, width, height, frame;
    long totalFrames, droppedFrames, corruptedFrames;
    boolean mediaKeys, top, everPlaying;
    Map<String, Object> debug;
  }

  public static final class Session {
    public final String id, salt;
    public final long startedAt = System.currentTimeMillis();
    volatile long stoppedAt;
    final Map<String, Element> elements = new LinkedHashMap<>();
    final List<Map<String, Object>> net = new ArrayList<>();
    final Map<String, Long> mediaSession = new TreeMap<>();
    final Map<String, Long> process = new TreeMap<>();
    double sessionPosition = -1, sessionDuration = -1;
    long netDropped;
    // Whether the extension is actually connected to this session: polls answered while active,
    // batches carrying this session's id, and the records those batches held.
    long polls, batches, netReceived, mediaReceived;
    List<Map<String, Object>> candidates = List.of();
    PlaybackProbe probe;

    Session() {
      byte[] b = new byte[12];
      new SecureRandom().nextBytes(b);
      StringBuilder hex = new StringBuilder();
      for (byte x : b) hex.append(String.format(Locale.ROOT, "%02x", x));
      salt = hex.substring(0, 16);
      id = "P-" + hex.substring(16);
    }

    public boolean stopped() {
      return stoppedAt != 0;
    }

    synchronized void polled() {
      polls++;
    }

    public synchronized void ingest(Map<String, Object> batch) {
      batches++;
      if (batch.get("net") instanceof List<?> l) netReceived += l.size();
      if (batch.get("media") instanceof List<?> l) mediaReceived += l.size();
      if (batch.get("dropped") instanceof Number n) netDropped += Math.max(0, n.longValue());
      if (batch.get("net") instanceof List<?> records)
        for (Object o : records) {
          if (!(o instanceof Map<?, ?> r)) continue;
          if (net.size() >= 4000) {
            netDropped++;
            continue;
          }
          Map<String, Object> rec = new LinkedHashMap<>();
          rec.put("tab", integer(r.get("tab"), -1, 1 << 20));
          rec.put("frame", integer(r.get("frame"), 0, 1 << 30));
          rec.put("type", token(r.get("type"), "media|xmlhttprequest|other|object|main_frame|sub_frame"));
          rec.put("status", integer(r.get("status"), 0, 999));
          rec.put("mime", mimeToken(r.get("mime")));
          rec.put("host", FailureDiagnostics.host("https://" + string(r.get("host"), 253) + "/"));
          rec.put("ext", token(r.get("ext"), "[a-z0-9]{1,5}"));
          rec.put("key", token(r.get("key"), "[a-f0-9]{16}"));
          rec.put("error", token(r.get("error"), "[A-Z0-9_]{1,48}"));
          for (String flag : List.of("fromCache", "cookie", "referer", "origin", "range", "thirdParty"))
            rec.put(flag, Boolean.TRUE.equals(r.get(flag)));
          rec.put("ms", integer(r.get("ms"), -1, 600000));
          net.add(rec);
        }
      if (batch.get("media") instanceof List<?> frames)
        for (Object o : frames) {
          if (!(o instanceof Map<?, ?> f)) continue;
          int tab = integer(f.get("tab"), -1, 1 << 20), frame = integer(f.get("frame"), 0, 1 << 30);
          boolean top = Boolean.TRUE.equals(f.get("top"));
          if (!(f.get("samples") instanceof List<?> samples)) continue;
          for (Object so : samples)
            if (so instanceof Map<?, ?> s) sample(tab, frame, top, s);
        }
    }

    private void sample(int tab, int frame, boolean top, Map<?, ?> s) {
      String key = tab + ":" + frame + ":" + integer(s.get("index"), 0, 16);
      if (!elements.containsKey(key) && elements.size() >= 24) return;
      Element e = elements.computeIfAbsent(key, k -> new Element());
      e.samples++;
      e.top = top;
      e.frame = frame;
      e.tag = token(s.get("tag"), "video|audio");
      String kind = token(s.get("srcKind"), "none|blob|data|http-hls|http-dash|http-file|other");
      if (!kind.isEmpty()) {
        if (e.firstKind.isEmpty()) e.firstKind = kind;
        e.lastKind = kind;
        e.srcKinds.add(kind);
      }
      String host = FailureDiagnostics.host("https://" + string(s.get("srcHost"), 253) + "/");
      if (!host.isEmpty() && e.srcHosts.size() < 6) e.srcHosts.add(host);
      String srcKey = token(s.get("srcKey"), "[a-f0-9]{16}");
      if (!srcKey.isEmpty() && e.srcKeys.size() < 8) e.srcKeys.add(srcKey);
      double t = decimal(s.get("currentTime"));
      if (t >= 0) {
        if (e.firstTime < 0) e.firstTime = t;
        // Time actually played between consecutive samples (~1 s apart); a loop or seek back is
        // not negative progress and a jump forward is not playback.
        if (e.lastTime >= 0 && t > e.lastTime && t - e.lastTime <= 10) e.advanced += t - e.lastTime;
        e.maxTime = Math.max(e.maxTime, t);
        e.lastTime = t;
      }
      double d = decimal(s.get("duration"));
      if (d >= 0 || Double.isInfinite(d)) e.duration = d;
      e.maxAhead = Math.max(e.maxAhead, Math.max(0, decimal(s.get("bufferedAhead"))));
      e.maxBuffered = Math.max(e.maxBuffered, Math.max(0, decimal(s.get("bufferedSeconds"))));
      e.lastReady = integer(s.get("readyState"), 0, 4);
      e.maxReady = Math.max(e.maxReady, e.lastReady);
      e.lastNetwork = integer(s.get("networkState"), 0, 3);
      e.width = Math.max(e.width, integer(s.get("videoWidth"), 0, 16384));
      e.height = Math.max(e.height, integer(s.get("videoHeight"), 0, 16384));
      if (Boolean.FALSE.equals(s.get("paused")) && t > 0) e.everPlaying = true;
      int code = integer(s.get("errorCode"), 0, 9);
      if (code > 0) {
        e.errorCodes.add(code);
        String message = string(s.get("errorMessage"), 120);
        if (!message.isEmpty() && e.errorMessages.size() < 4 && safeText(message)
            && !e.errorMessages.contains(message)) e.errorMessages.add(message);
      }
      if (Boolean.TRUE.equals(s.get("mediaKeys"))) e.mediaKeys = true;
      if (s.get("encryptedInitData") instanceof List<?> list)
        for (Object x : list) {
          String v = token(x, "[A-Za-z0-9_-]{1,16}");
          if (!v.isEmpty() && e.initDataTypes.size() < 4) e.initDataTypes.add(v);
        }
      if (s.get("events") instanceof Map<?, ?> ev)
        ev.forEach(
            (k, v) -> {
              String name = token(k, "[a-z]{3,24}");
              if (!name.isEmpty() && v instanceof Number n && e.events.size() < 24)
                e.events.merge(name, Math.max(0, Math.min(100000, n.longValue())), Long::sum);
            });
      if (s.get("quality") instanceof Map<?, ?> q) {
        e.totalFrames = Math.max(e.totalFrames, integer(q.get("total"), 0, Integer.MAX_VALUE));
        e.droppedFrames = Math.max(e.droppedFrames, integer(q.get("dropped"), 0, Integer.MAX_VALUE));
        e.corruptedFrames = Math.max(e.corruptedFrames, integer(q.get("corrupted"), 0, Integer.MAX_VALUE));
      }
      if (s.get("debug") instanceof Map<?, ?> dbg) {
        Map<String, Object> clean = new TreeMap<>();
        dbg.forEach(
            (k, v) -> {
              String name = token(k, "[A-Za-z0-9_.\\[\\]-]{1,80}");
              if (name.isEmpty() || clean.size() >= 48) return;
              if (v instanceof Boolean || v instanceof Number) clean.put(name, v);
              else if (v instanceof String str && str.length() <= 80 && safeText(str)) clean.put(name, str);
            });
        if (!clean.isEmpty()) e.debug = clean;
      }
    }

    synchronized void mediaSessionEvent(String event, double position, double duration) {
      if (!event.matches("[a-z-]{2,24}")) return;
      mediaSession.merge(event, 1L, Long::sum);
      if (position >= 0) sessionPosition = position;
      if (duration >= 0) sessionDuration = duration;
    }

    synchronized void processEvent(String event) {
      if (event.matches("[a-z-]{2,24}")) process.merge(event, 1L, Long::sum);
    }

    public synchronized void candidates(List<MediaCandidate> list) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (MediaCandidate c : list) {
        if (out.size() >= 8) break;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", c.getType().name());
        m.put("source", token(c.getSource(), "[a-z-]{2,32}"));
        m.put("host", FailureDiagnostics.host(c.getUrl()));
        m.put("pageHost", FailureDiagnostics.host(c.getPageUrl() == null ? "" : c.getPageUrl()));
        m.put("key", DiagKeys.key(salt, c.getUrl()));
        m.put("requestContextVerified", "true".equals(c.getMetadata().get("requestContextVerified")));
        m.put("headerNames", headerNames(c.getHeaders().keySet()));
        out.add(m);
      }
      candidates = out;
    }

    public synchronized void probe(PlaybackProbe value) {
      probe = value;
    }

    public synchronized PlaybackProbe probeResult() {
      return probe;
    }

    /** The complete report; every value is a host, an enum-like token, a number or a key. */
    public synchronized Map<String, Object> report(Map<String, Object> app) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("schema", "adownloader-playback-diagnostic");
      r.put("schemaVersion", 1);
      r.put("sessionId", id);
      r.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
      r.put("durationSeconds", ((stoppedAt == 0 ? System.currentTimeMillis() : stoppedAt) - startedAt) / 1000);
      if (app != null) r.put("environment", app);
      r.put("stages", stages());
      r.put("playback", playback());
      r.put("playerNetwork", network());
      Map<String, Object> ext = new LinkedHashMap<>();
      ext.put("pollsWhileActive", polls);
      ext.put("sessionBatches", batches);
      ext.put("networkRecordsReceived", netReceived);
      ext.put("mediaFramesReceived", mediaReceived);
      r.put("extensionLink", ext);
      Map<String, Object> det = new LinkedHashMap<>();
      det.put("candidateCount", candidates.size());
      det.put("candidates", candidates);
      r.put("detection", det);
      r.put("download", download());
      r.put("comparison", comparison());
      r.put("conclusions", Verdicts.derive(this));
      Map<String, Object> privacy = new LinkedHashMap<>();
      privacy.put("containsUrls", false);
      privacy.put("containsUrlQuery", false);
      privacy.put("containsHeaderValues", false);
      privacy.put("containsCookies", false);
      privacy.put("containsPageTitle", false);
      privacy.put(
          "urlKeys",
          "salted per session (FNV-1a of origin+path); comparable only inside this report");
      r.put("privacy", privacy);
      return Privacy.scrub(r);
    }

    Map<String, Object> playback() {
      Map<String, Object> p = new LinkedHashMap<>();
      List<Object> list = new ArrayList<>();
      for (var entry : elements.entrySet()) {
        Element e = entry.getValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("element", entry.getKey().replaceFirst("^-?\\d+:", ""));
        m.put("tag", e.tag);
        m.put("topFrame", e.top);
        m.put("path", path(e));
        m.put("srcKinds", new ArrayList<>(e.srcKinds));
        m.put("srcHosts", new ArrayList<>(e.srcHosts));
        m.put("srcKeys", new ArrayList<>(e.srcKeys));
        m.put("samples", e.samples);
        m.put("progressSeconds", round(progress(e)));
        m.put("maxCurrentTime", round(e.maxTime));
        m.put("duration", Double.isInfinite(e.duration) ? "live" : round(e.duration));
        m.put("maxReadyState", e.maxReady);
        m.put("lastReadyState", e.lastReady);
        m.put("lastNetworkState", e.lastNetwork);
        m.put("maxBufferedAheadSeconds", round(e.maxAhead));
        m.put("maxBufferedSeconds", round(e.maxBuffered));
        m.put("videoWidth", e.width);
        m.put("videoHeight", e.height);
        m.put("everPlaying", e.everPlaying);
        m.put("events", new TreeMap<>(e.events));
        m.put("errorCodes", new ArrayList<>(e.errorCodes));
        m.put("errorNames", errorNames(e.errorCodes));
        m.put("errorMessages", new ArrayList<>(e.errorMessages));
        m.put("emeMediaKeys", e.mediaKeys);
        m.put("encryptedInitDataTypes", new ArrayList<>(e.initDataTypes));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("totalFrames", e.totalFrames);
        q.put("droppedFrames", e.droppedFrames);
        q.put("corruptedFrames", e.corruptedFrames);
        m.put("frames", q);
        m.put("geckoDebugInfo", e.debug == null ? Map.of("available", false) : e.debug);
        list.add(m);
      }
      p.put("elementCount", elements.size());
      p.put("elements", list);
      Map<String, Object> ms = new LinkedHashMap<>();
      ms.put("events", new TreeMap<>(mediaSession));
      ms.put("lastPosition", round(sessionPosition));
      ms.put("lastDuration", round(sessionDuration));
      p.put("geckoMediaSession", ms);
      p.put("contentProcess", new TreeMap<>(process));
      return p;
    }

    static String path(Element e) {
      if (e.srcKinds.contains("blob")) return "mse";
      if (e.srcKinds.contains("http-hls")) return "native-hls";
      if (e.srcKinds.contains("http-dash")) return "native-dash";
      if (e.srcKinds.contains("http-file")) return "progressive";
      return e.srcKinds.isEmpty() || e.srcKinds.equals(Set.of("none")) ? "none" : "other";
    }

    static double progress(Element e) {
      return e.advanced;
    }

    static List<String> errorNames(Collection<Integer> codes) {
      List<String> out = new ArrayList<>();
      for (int c : codes)
        out.add(
            switch (c) {
              case 1 -> "MEDIA_ERR_ABORTED";
              case 2 -> "MEDIA_ERR_NETWORK";
              case 3 -> "MEDIA_ERR_DECODE";
              case 4 -> "MEDIA_ERR_SRC_NOT_SUPPORTED";
              default -> "OTHER";
            });
      return out;
    }

    boolean playerRequest(Map<String, Object> r) {
      String type = (String) r.get("type");
      return type.equals("media") || type.equals("xmlhttprequest") || type.equals("other") || type.equals("object");
    }

    Map<String, Object> network() {
      Map<String, Object> n = new LinkedHashMap<>();
      Map<String, Long> byType = new TreeMap<>();
      Map<String, Map<String, Object>> byHost = new TreeMap<>();
      long unattributed = 0, player = 0;
      for (Map<String, Object> r : net) {
        byType.merge((String) r.get("type"), 1L, Long::sum);
        if ((Integer) r.get("tab") < 0) unattributed++;
        if (!playerRequest(r)) continue;
        player++;
        String host = (String) r.get("host");
        Map<String, Object> h = byHost.computeIfAbsent(host.isEmpty() ? "(unknown)" : host, k -> new LinkedHashMap<>());
        h.merge("requests", 1L, (a, b) -> (Long) a + (Long) b);
        int status = (Integer) r.get("status");
        String outcome = status >= 200 && status < 400 ? "ok" : status == 0 ? "error" : "failed";
        h.merge(outcome, 1L, (a, b) -> (Long) a + (Long) b);
        @SuppressWarnings("unchecked")
        Map<String, Long> statuses = (Map<String, Long>) h.computeIfAbsent("statuses", k -> new TreeMap<String, Long>());
        statuses.merge(String.valueOf(status), 1L, Long::sum);
        @SuppressWarnings("unchecked")
        Map<String, Long> exts = (Map<String, Long>) h.computeIfAbsent("extensions", k -> new TreeMap<String, Long>());
        String ext = (String) r.get("ext");
        if (exts.size() < 8 || exts.containsKey(ext)) exts.merge(ext.isEmpty() ? "(none)" : ext, 1L, Long::sum);
        @SuppressWarnings("unchecked")
        Map<String, Long> mimes = (Map<String, Long>) h.computeIfAbsent("mimes", k -> new TreeMap<String, Long>());
        String mime = (String) r.get("mime");
        if (mimes.size() < 8 || mimes.containsKey(mime)) mimes.merge(mime.isEmpty() ? "(none)" : mime, 1L, Long::sum);
        for (String flag : List.of("cookie", "referer", "origin", "range"))
          if (Boolean.TRUE.equals(r.get(flag))) h.merge(flag + "Sent", 1L, (a, b) -> (Long) a + (Long) b);
        if (Boolean.TRUE.equals(r.get("fromCache"))) h.merge("fromCache", 1L, (a, b) -> (Long) a + (Long) b);
        String error = (String) r.get("error");
        if (!error.isEmpty()) {
          @SuppressWarnings("unchecked")
          Map<String, Long> errors = (Map<String, Long>) h.computeIfAbsent("networkErrors", k -> new TreeMap<String, Long>());
          if (errors.size() < 6 || errors.containsKey(error)) errors.merge(error, 1L, Long::sum);
        }
      }
      n.put("observedRequests", net.size());
      n.put("playerTypeRequests", player);
      n.put("unattributedRequests", unattributed);
      n.put("droppedRecords", netDropped);
      n.put("byType", byType);
      List<Object> hosts = new ArrayList<>();
      for (var e : byHost.entrySet()) {
        if (hosts.size() >= 16) break;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", e.getKey());
        m.putAll(e.getValue());
        hosts.add(m);
      }
      n.put("hosts", hosts);
      n.put(
          "scope",
          "requests visible to the WebExtension webRequest API (successes and failures)");
      return n;
    }

    Map<String, Object> download() {
      Map<String, Object> d = new LinkedHashMap<>();
      if (probe == null) {
        d.put("probed", false);
        return d;
      }
      d.put("probed", true);
      d.put("stoppedReason", probe.stoppedReason);
      d.put("steps", probe.stepMaps());
      if (probe.root != null) d.put("rootPlaylist", probe.root.toMap());
      if (probe.media != null && probe.media != probe.root) d.put("mediaPlaylist", probe.media.toMap());
      d.put("requestsSkippedAfterBlock", probe.requestsSkippedAfterBlock);
      d.put("transportEvents", probe.transportEvents);
      return d;
    }

    /** Whether the player fetched the segments the downloaded playlist lists. */
    Map<String, Object> comparison() {
      Map<String, Object> c = new LinkedHashMap<>();
      Set<String> manifestKeys = probe == null ? Set.of() : probe.segmentKeys;
      Set<String> manifestHosts =
          probe == null || probe.media == null ? Set.of() : probe.media.segmentHosts;
      long matched = 0, matchedOk = 0, matchedFailed = 0, segmentLike = 0;
      boolean cookie = false, referer = false;
      SortedSet<String> playerHosts = new TreeSet<>();
      for (Map<String, Object> r : net) {
        if (!playerRequest(r)) continue;
        String ext = (String) r.get("ext"), mime = (String) r.get("mime");
        boolean seg =
            ext.matches("ts|m4s|m4v|m4a|mp4|aac|cmfv|cmfa|fmp4")
                || mime.startsWith("video/")
                || mime.startsWith("audio/")
                || r.get("type").equals("media");
        if (seg) {
          segmentLike++;
          if (playerHosts.size() < 8 && !((String) r.get("host")).isEmpty()) playerHosts.add((String) r.get("host"));
        }
        if (!manifestKeys.isEmpty() && manifestKeys.contains(r.get("key"))) {
          matched++;
          int status = (Integer) r.get("status");
          if (status >= 200 && status < 400) matchedOk++;
          else matchedFailed++;
          cookie |= Boolean.TRUE.equals(r.get("cookie"));
          referer |= Boolean.TRUE.equals(r.get("referer"));
        }
      }
      c.put("manifestSegmentKeys", manifestKeys.size());
      c.put("manifestSegmentHosts", new ArrayList<>(manifestHosts));
      c.put("playerSegmentLikeRequests", segmentLike);
      c.put("playerSegmentHosts", new ArrayList<>(playerHosts));
      c.put("playerRequestsMatchingManifest", matched);
      c.put("matchingOk", matchedOk);
      c.put("matchingFailed", matchedFailed);
      c.put("playerSentCookieForManifestSegments", cookie);
      c.put("playerSentRefererForManifestSegments", referer);
      Set<String> shared = new TreeSet<>(manifestHosts);
      shared.retainAll(playerHosts);
      c.put("sharedSegmentHosts", new ArrayList<>(shared));
      return c;
    }

    Map<String, Object> stages() {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("candidateDetection", candidates.isEmpty() ? "none" : "detected");
      String manifest = "not-probed", segment = "not-probed";
      if (probe != null)
        for (PlaybackProbe.Step step : probe.steps) {
          if (step.stage.equals("ROOT_MANIFEST") || step.stage.equals("VARIANT_PLAYLIST"))
            manifest = step.status.equals("ok") ? "verified" : "failed:" + step.kind;
          if (step.stage.equals("VIDEO_SEGMENT") && !segment.startsWith("failed"))
            segment = step.status.equals("ok") ? "verified" : "failed:" + step.kind;
        }
      if (probe != null && "drm".equals(probe.stoppedReason)) segment = "skipped:DRM";
      s.put("manifestValidation", manifest);
      s.put("segmentValidation", segment);
      double best = 0;
      for (Element e : elements.values()) best = Math.max(best, progress(e));
      s.put(
          "playback",
          elements.isEmpty() ? "no-media-element" : best >= 2 ? "progressing" : "not-progressing");
      return s;
    }

    List<Element> elementList() {
      return new ArrayList<>(elements.values());
    }

    List<Map<String, Object>> netRecords() {
      return net;
    }
  }

  // ---- intake helpers: only expected shapes survive ----

  static int integer(Object v, int min, int max) {
    if (!(v instanceof Number n)) return min < 0 ? min : 0;
    double d = n.doubleValue();
    if (!Double.isFinite(d)) return min < 0 ? min : 0;
    return (int) Math.max(min, Math.min(max, Math.round(d)));
  }

  static double decimal(Object v) {
    if (!(v instanceof Number n)) return -1;
    double d = n.doubleValue();
    return Double.isNaN(d) ? -1 : d;
  }

  static String string(Object v, int max) {
    return v instanceof String s && s.length() <= max ? s : "";
  }

  static String token(Object v, String pattern) {
    return v instanceof String s && s.length() <= 80 && s.matches(pattern) ? s : "";
  }

  static String mimeToken(Object v) {
    String s = string(v, 101);
    return s.matches("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}") || s.equals("other") ? s : "";
  }

  static boolean safeText(String s) {
    return !s.contains("://") && !s.contains("?") && !s.contains("=") && !s.contains("@")
        && !s.toLowerCase(Locale.ROOT).matches(".*(cookie|token|bearer|password|secret|auth).*");
  }

  static List<String> headerNames(Collection<String> names) {
    List<String> out = new ArrayList<>();
    for (String n : names)
      if (n != null && n.matches("[A-Za-z0-9-]{1,48}") && out.size() < 16)
        out.add(n.toLowerCase(Locale.ROOT));
    Collections.sort(out);
    return out;
  }

  static double round(double v) {
    return v < 0 ? -1 : Math.round(v * 10) / 10.0;
  }
}
