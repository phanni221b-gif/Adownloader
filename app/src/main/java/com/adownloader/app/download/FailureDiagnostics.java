package com.adownloader.app.download;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Explicit report schema. Raw URLs, bodies, credentials and exception messages never enter it. */
public final class FailureDiagnostics {
  public enum Stage {
    DETECTION,
    ROOT_MANIFEST,
    MASTER_MANIFEST,
    VARIANT_PLAYLIST,
    AUDIO_PLAYLIST,
    DASH_MPD,
    AES_KEY,
    INIT_SEGMENT,
    VIDEO_SEGMENT,
    AUDIO_SEGMENT,
    DIRECT_MEDIA,
    RANGE_RESUME,
    MUX,
    FINAL_INSPECTION,
    MEDIASTORE,
    STORAGE,
    GECKO_BRIDGE,
    UNKNOWN
  }

  private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
  private static volatile Consumer<Map<String, Object>> sink;

  public static void install(Consumer<Map<String, Object>> value) {
    sink = value;
  }

  public static DownloadError.Category category(Throwable error) {
    if (error instanceof DownloadError d) return d.category;
    if (error instanceof DownloadError.ManifestFailure m) return m.category;
    if (error instanceof java.net.SocketException
        || error instanceof java.net.SocketTimeoutException
        || error instanceof java.net.UnknownHostException) return DownloadError.Category.NETWORK;
    if (error instanceof java.security.GeneralSecurityException) return DownloadError.Category.AUTH;
    if (error instanceof org.xml.sax.SAXException || error instanceof NumberFormatException)
      return DownloadError.Category.MANIFEST_INVALID;
    return DownloadError.Category.UNKNOWN;
  }

  public static Session current() {
    return ACTIVE.get();
  }

  public static final class Scope implements AutoCloseable {
    private final Session previous = ACTIVE.get();

    private Scope(Session session) {
      if (session == null) ACTIVE.remove();
      else ACTIVE.set(session);
    }

    @Override
    public void close() {
      if (previous == null) ACTIVE.remove();
      else ACTIVE.set(previous);
    }
  }

  public static Scope activate(Session session) {
    return new Scope(session);
  }

  public static void phase(Stage stage) {
    Session s = current();
    if (s != null) s.phase(stage);
  }

  public static void event(Map<String, Object> event) {
    Session s = current();
    if (s != null) s.event(event);
  }

  public static void event(Session session, Map<String, Object> event) {
    if (session != null) session.event(event);
  }

  public static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(Character.forDigit((b >>> 4) & 15, 16));
        hex.append(Character.forDigit(b & 15, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      return "";
    }
  }

  public static String host(String raw) {
    try {
      return safeHost(URI.create(raw).getHost());
    } catch (Exception e) {
      return "";
    }
  }

  private static String safeHost(String value) {
    if (value == null || value.length() > 253 || !value.matches("[A-Za-z0-9.:-]+")) return "";
    for (String label : value.split("\\."))
      if (label.length() > 48 || label.matches("[A-Za-z0-9]{24,}")) return "";
    return value.toLowerCase(Locale.ROOT);
  }

  private static final Set<String> TYPES =
      Set.of("HLS", "DASH", "DIRECT_VIDEO", "DIRECT_AUDIO", "MSE", "UNKNOWN");
  private static final Set<String> SOURCES =
      Set.of(
          "network-response",
          "response-signature",
          "redirect-observation",
          "dom-media",
          "dom-video",
          "performance-resource",
          "extension",
          "UNKNOWN");
  private static final Set<String> TRANSPORTS =
      Set.of("native", "gecko", "gecko-response-cache", "UNKNOWN");
  private static final Set<String> ACTIONS =
      Set.of(
          "NATIVE_INITIAL",
          "NATIVE_CONTEXT_RETRY",
          "GECKO_BOOTSTRAP",
          "GECKO_RESPONSE_CACHE",
          "GECKO_CACHE_MISS",
          "MANIFEST_NATIVE_BLOCKED",
          "SEGMENT_NATIVE_BLOCKED",
          "START",
          "PHASE",
          "MANIFEST_PARSED",
          "COOKIE_UPDATE",
          "CONTEXT_UPDATE",
          "RESUME",
          "PAUSE",
          "FAILURE");
  private static final Set<String> BODIES =
      Set.of("HLS", "DASH", "HTML", "HTML_CHALLENGE", "MEDIA", "UNKNOWN");
  private static final Set<String> REASONS =
      Set.of(
          "DOWNLOAD_FAILURE",
          "PROBE_FAILURE",
          "PLAYBACK_WITHOUT_CANDIDATE",
          "MSE_MANIFEST_NOT_FOUND",
          "BRIDGE_INSTALL_FAILED",
          "BRIDGE_DISCONNECTED",
          "MANIFEST_PARSE",
          "UNKNOWN");
  private static final Set<String> MIME =
      Set.of(
          "text/html",
          "text/plain",
          "application/vnd.apple.mpegurl",
          "application/x-mpegurl",
          "application/dash+xml",
          "application/octet-stream",
          "video/mp4",
          "audio/mp4",
          "video/mp2t",
          "video/webm",
          "audio/webm",
          "application/json",
          "");
  private static final Set<String> OBJECTS =
      Set.of(
          "app",
          "device",
          "media",
          "failure",
          "exception",
          "privacy",
          "network",
          "manifest",
          "aggregate",
          "detection",
          "manifestEvidence");
  private static final Set<String> BOOLS =
      Set.of(
          "filterSupported",
          "geckoPlaybackObserved",
          "geckoMediaSuccessObserved",
          "candidateCreated",
          "manifestCachePresent",
          "titlePresent",
          "manifestDetected",
          "audioGroupPresent",
          "cookiePresent",
          "authorizationPresent",
          "customAuthPresent",
          "setCookieObserved",
          "cookieRefreshed",
          "contentLengthPresent",
          "etagPresent",
          "rangeSupported",
          "geckoSuccess",
          "manifestCacheUsed",
          "nativeRequestSkipped",
          "nativeSuccess",
          "vpnTransport",
          "available",
          "validated",
          "metered",
          "proxyConfigured",
          "containsRawCookies",
          "containsAuthorizationValues",
          "containsUrlQuery",
          "safeForDiagnosticSharing");
  private static final Set<String> NUMBERS =
      Set.of(
          "videoElements",
          "blobPlayers",
          "playbackObserved",
          "mediaSegments",
          "fmp4",
          "suspectRequests",
          "filteredResponses",
          "hlsCandidates",
          "dashCandidates",
          "activeDeepFrames",
          "playerFrames",
          "starts",
          "chunkCalls",
          "ends",
          "ready",
          "pending",
          "boundPages",
          "logicalBytes",
          "peakBytes",
          "schemaVersion",
          "versionCode",
          "api",
          "jobId",
          "sequence",
          "elapsedMs",
          "httpStatus",
          "status",
          "redirects",
          "line",
          "segmentIndex",
          "segmentCount",
          "variantCount",
          "hlsVersion",
          "selectedHeight",
          "selectedWidth",
          "cookieEntryCount",
          "occurrenceCount",
          "eventCount");
  private static final Set<String> FILES =
      Set.of(
          "HttpSource.java",
          "ManifestParser.java",
          "DownloadEngine.java",
          "PostProcessor.java",
          "JobRunner.java",
          "JobJournal.java",
          "DownloadJobStore.java",
          "MediaProbeService.java",
          "GeckoManifestBridge.java",
          "MediaExtensionManager.java",
          "ManifestResponseCache.java",
          "StreamingEngine.java",
          "TransferControl.java");
  private static final Set<String> EXCEPTIONS =
      Set.of(
          "DownloadError",
          "ManifestFailure",
          "IOException",
          "FileNotFoundException",
          "SocketException",
          "SocketTimeoutException",
          "UnknownHostException",
          "SSLException",
          "SSLHandshakeException",
          "SAXException",
          "NumberFormatException",
          "IllegalArgumentException",
          "IllegalStateException",
          "SecurityException",
          "JSONException",
          "Exception",
          "RuntimeException",
          "OTHER");

  private static String known(String key, Object value) {
    String v = value instanceof String ? (String) value : "";
    return switch (key) {
      case "type" -> TYPES.contains(v) ? v : "UNKNOWN";
      case "detectionSource" -> SOURCES.contains(v) ? v : "UNKNOWN";
      case "stage" -> {
        try {
          yield Stage.valueOf(v).name();
        } catch (Exception e) {
          yield "UNKNOWN";
        }
      }
      case "category" -> {
        try {
          yield DownloadError.Category.valueOf(v).name();
        } catch (Exception e) {
          yield "UNKNOWN";
        }
      }
      case "transport" -> TRANSPORTS.contains(v) ? v : "UNKNOWN";
      case "action" -> ACTIONS.contains(v) ? v : "PHASE";
      case "body" -> BODIES.contains(v) ? v : "UNKNOWN";
      case "reasonCode" -> REASONS.contains(v) ? v : "UNKNOWN";
      case "mime" -> MIME.contains(v) ? v : "";
      case "tag" ->
          Set.of(
                      "EXT-X-KEY",
                      "EXT-X-MAP",
                      "EXT-X-STREAM-INF",
                      "EXT-X-MEDIA",
                      "EXT-X-BYTERANGE",
                      "EXT-X-DISCONTINUITY",
                      "EXT-X-VERSION",
                      "EXT-X-ENDLIST",
                      "EXTINF",
                      "EXT-X-PART",
                      "EXT-X-SKIP",
                      "EXT-X-SESSION-KEY")
                  .contains(v)
              ? v
              : "";
      case "exceptionClass" -> EXCEPTIONS.contains(v) ? v : "OTHER";
      case "file" -> FILES.contains(v) ? v : "";
      case "pageHost", "mediaHost", "playerHost", "host" -> safeHost(v);
      case "identityHash", "commit", "fingerprint" ->
          v.matches("[a-f0-9]{40}|[a-f0-9]{64}") ? v : "UNKNOWN";
      case "failureId" -> v.matches("F-[a-f0-9-]{36}") ? v : "";
      case "timestamp", "firstSeen", "lastSeen" -> v.matches("[0-9T:.Z+-]{16,40}") ? v : "";
      case "versionName", "geckoVersion", "android" ->
          v.matches("[0-9][A-Za-z0-9.+_-]{0,79}") ? v : "UNKNOWN";
      case "model" ->
          v.matches("[A-Za-z0-9 ._()-]{1,80}")
                  && !v.toLowerCase(Locale.ROOT).matches(".*(secret|token|bearer|password).*")
              ? v
              : "UNKNOWN";
      case "abi" -> Set.of("arm64-v8a", "armeabi-v7a", "x86_64", "x86").contains(v) ? v : "UNKNOWN";
      case "requestType" ->
          Set.of(
                      "media",
                      "xmlhttprequest",
                      "fetch",
                      "other",
                      "native-http",
                      "content-fetch",
                      "playback-response")
                  .contains(v)
              ? v
              : "UNKNOWN";
      default -> null;
    };
  }

  /** A second invocation at export also rejects unknown fields planted in stored JSON. */
  public static Map<String, Object> sanitize(Map<String, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      String key = e.getKey();
      Object value = e.getValue();
      if (OBJECTS.contains(key) && value instanceof Map<?, ?> m) {
        Map<String, Object> child = new LinkedHashMap<>();
        m.forEach(
            (k, v) -> {
              if (k instanceof String) child.put((String) k, v);
            });
        out.put(key, sanitize(child));
      } else if (key.equals("eventHistory") && value instanceof Collection<?> list) {
        List<Object> events = new ArrayList<>();
        for (Object item : list)
          if (item instanceof Map<?, ?> m) {
            Map<String, Object> event = new LinkedHashMap<>();
            m.forEach(
                (k, v) -> {
                  if (k instanceof String) event.put((String) k, v);
                });
            events.add(sanitize(event));
            if (events.size() == 50) break;
          }
        out.put(key, events);
      } else if (Set.of(
                  "headerNames", "geckoHeaderNames", "nativeHeaderNames", "missingHeaderNames")
              .contains(key)
          && value instanceof Collection<?> list) {
        List<String> names = new ArrayList<>();
        for (Object n : list)
          if (n instanceof String s
              && s.matches("[a-zA-Z0-9!#$%&'*+.^_`|~-]{1,48}")
              && !s.toLowerCase(Locale.ROOT).contains("secret")) {
            names.add(s.toLowerCase(Locale.ROOT));
            if (names.size() == 24) break;
          }
        out.put(key, List.copyOf(names));
      } else if (BOOLS.contains(key) && value instanceof Boolean) out.put(key, value);
      else if (NUMBERS.contains(key) && value instanceof Number n)
        out.put(key, Math.max(0, Math.min(1000000000000L, n.longValue())));
      else {
        String clean = known(key, value);
        if (clean != null) out.put(key, clean);
      }
    }
    return out;
  }

  public static String fingerprint(Map<String, ?> report) {
    Map<?, ?> media = report.get("media") instanceof Map<?, ?> m ? m : Map.of();
    Map<?, ?> failure = report.get("failure") instanceof Map<?, ?> f ? f : Map.of();
    return hash(
        media.get("type")
            + "|"
            + failure.get("stage")
            + "|"
            + failure.get("category")
            + "|"
            + failure.get("transport")
            + "|"
            + failure.get("httpStatus"));
  }

  public static final class Session {
    private final long jobId, started = System.nanoTime();
    private final Map<String, Object> media;
    private final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
    private Map<String, Object> failedEvent, lastTransport, manifest, detection;

    public synchronized void detection(Map<String, Object> metrics) {
      detection = sanitize(metrics);
    }

    private Stage phase = Stage.UNKNOWN;
    private long sequence;
    private boolean finished;

    public Session(long jobId, String type, String url, String page, Map<String, String> metadata) {
      this.jobId = jobId;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put(
          "type",
          type.equals("VIDEO") ? "DIRECT_VIDEO" : type.equals("AUDIO") ? "DIRECT_AUDIO" : type);
      m.put("pageHost", host(page));
      m.put("mediaHost", host(url));
      m.put("playerHost", metadata.getOrDefault("frameHost", ""));
      m.put("detectionSource", metadata.getOrDefault("detectionSource", "UNKNOWN"));
      m.put("identityHash", hash(UUID.randomUUID() + "|" + url));
      m.put("candidateCreated", !url.isEmpty());
      m.put("titlePresent", !metadata.getOrDefault("title", "").isBlank());
      m.put("geckoMediaSuccessObserved", "true".equals(metadata.get("requestContextVerified")));
      m.put("geckoPlaybackObserved", "true".equals(metadata.get("geckoPlaybackObserved")));
      media = sanitize(m);
      event(Map.of("action", "START", "stage", "UNKNOWN"));
    }

    public synchronized void phase(Stage value) {
      phase = value;
      if (Set.of(Stage.MUX, Stage.FINAL_INSPECTION, Stage.MEDIASTORE, Stage.STORAGE)
          .contains(value)) failedEvent = null;
      event(Map.of("action", "PHASE", "stage", value.name()));
    }

    public synchronized void event(Map<String, Object> raw) {
      if (finished) return;
      Map<String, Object> safe = sanitize(raw);
      safe.put("sequence", ++sequence);
      safe.put("elapsedMs", (System.nanoTime() - started) / 1000000);
      if ("DIRECT".equals(raw.get("stage")))
        safe.put("stage", phase == Stage.RANGE_RESUME ? "RANGE_RESUME" : "DIRECT_MEDIA");
      if ("DASH".equals(media.get("type")) && "ROOT_MANIFEST".equals(safe.get("stage")))
        safe.put("stage", "DASH_MPD");
      if (safe.get("stage") instanceof String s)
        try {
          phase = Stage.valueOf(s);
        } catch (Exception ignored) {
        }
      if (raw.containsKey("transport")) lastTransport = safe;
      if (safe.get("manifest") instanceof Map<?, ?> m) {
        Map<String, Object> value = new LinkedHashMap<>();
        m.forEach(
            (k, v) -> {
              if (k instanceof String) value.put((String) k, v);
            });
        manifest = value;
      }
      if (safe.get("headerNames") instanceof Collection<?> names) {
        safe.put("cookiePresent", names.contains("cookie"));
        safe.put("authorizationPresent", names.contains("authorization"));
        safe.put("customAuthPresent", names.stream().anyMatch(n -> n.toString().startsWith("x-")));
      }
      if (raw.get("status") instanceof Number n && n.intValue() >= 400) failedEvent = safe;
      if (Boolean.TRUE.equals(safe.get("manifestCacheUsed")))
        media.put("manifestCachePresent", true);
      if (Boolean.TRUE.equals(safe.get("geckoSuccess")))
        media.put("geckoMediaSuccessObserved", true);
      events.addLast(safe);
      while (events.size() > 50) events.removeFirst();
    }

    public synchronized Map<String, Object> report(
        Throwable error, DownloadError.Category category, String reason) {
      String id = "F-" + UUID.randomUUID();
      Map<String, Object> last = events.peekLast();
      Map<String, Object> evidence =
          Set.of(Stage.MUX, Stage.FINAL_INSPECTION, Stage.MEDIASTORE, Stage.STORAGE).contains(phase)
              ? (last == null ? Map.of() : last)
              : Set.of(
                              DownloadError.Category.AUTH,
                              DownloadError.Category.ACCESS_BLOCKED,
                              DownloadError.Category.EXPIRED_URL,
                              DownloadError.Category.NETWORK,
                              DownloadError.Category.SERVER)
                          .contains(category)
                      && failedEvent != null
                  ? failedEvent
                  : lastTransport != null ? lastTransport : last == null ? Map.of() : last;
      Stage stage = phase;
      if (!Set.of(
                  Stage.MUX,
                  Stage.FINAL_INSPECTION,
                  Stage.MEDIASTORE,
                  Stage.STORAGE,
                  Stage.RANGE_RESUME)
              .contains(stage)
          && evidence.get("stage") instanceof String s)
        try {
          stage = Stage.valueOf(s);
        } catch (Exception ignored) {
        }
      if (error instanceof DownloadError d && d.diagnostic != null) {
        HlsDiagnostic h = d.diagnostic;
        stage =
            switch (h.stage) {
              case ROOT_HTTP, ROOT_BODY ->
                  media.get("type").equals("DASH") ? Stage.DASH_MPD : Stage.ROOT_MANIFEST;
              case MASTER_PARSE -> Stage.MASTER_MANIFEST;
              case VARIANT_HTTP, VARIANT_BODY, VARIANT_PARSE ->
                  Set.of(
                              Stage.AES_KEY,
                              Stage.INIT_SEGMENT,
                              Stage.VIDEO_SEGMENT,
                              Stage.AUDIO_SEGMENT,
                              Stage.AUDIO_PLAYLIST,
                              Stage.DIRECT_MEDIA,
                              Stage.RANGE_RESUME)
                          .contains(stage)
                      ? stage
                      : Stage.VARIANT_PLAYLIST;
              case ATTRIBUTE -> h.tag.equals("EXT-X-STREAM-INF") ? Stage.MASTER_MANIFEST : stage;
              case CHILD_AUTH, SEGMENT_URI -> phase;
              default -> stage;
            };
      }
      if (reason.equals("BRIDGE_DISCONNECTED") || reason.equals("BRIDGE_INSTALL_FAILED")) {
        stage = Stage.GECKO_BRIDGE;
        evidence = Map.of("transport", "gecko", "status", 0);
      }
      Map<String, Object> failure = new LinkedHashMap<>();
      failure.put("stage", stage.name());
      failure.put("category", category.name());
      failure.put("reasonCode", reason);
      failure.put("host", evidence.getOrDefault("host", ""));
      failure.put("redirects", evidence.getOrDefault("redirects", 0));
      failure.put("transport", evidence.getOrDefault("transport", "UNKNOWN"));
      failure.put("httpStatus", evidence.getOrDefault("status", 0));
      if (error instanceof DownloadError d && d.diagnostic != null) {
        failure.put("httpStatus", d.diagnostic.status);
        failure.put("redirects", d.diagnostic.redirects);
        failure.put("mime", d.diagnostic.mime);
        failure.put("host", d.diagnostic.host);
        failure.put("tag", d.diagnostic.tag);
      }
      Map<String, Object> exception = new LinkedHashMap<>();
      exception.put("exceptionClass", error == null ? "OTHER" : error.getClass().getSimpleName());
      if (error != null)
        for (StackTraceElement frame : error.getStackTrace())
          if (frame.getClassName().startsWith("com.adownloader.app.")) {
            exception.put("file", frame.getFileName());
            exception.put("line", frame.getLineNumber());
            break;
          }
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schemaVersion", 1);
      report.put("failureId", id);
      report.put("timestamp", Instant.now().toString());
      report.put("jobId", jobId);
      report.put("media", new LinkedHashMap<>(media));
      report.put("failure", failure);
      report.put("exception", exception);
      if (manifest != null) report.put("manifest", manifest);
      if (detection != null) report.put("detection", detection);
      report.put("eventHistory", new ArrayList<>(events));
      return sanitize(report);
    }

    public String fail(Throwable error, DownloadError.Category category, String reason) {
      Map<String, Object> report = report(error, category, reason);
      Consumer<Map<String, Object>> listener = sink;
      if (listener != null)
        try {
          listener.accept(report);
        } catch (Exception ignored) {
        }
      return (String) report.get("failureId");
    }

    public synchronized void complete() {
      finished = true;
      events.clear();
      failedEvent = null;
    }
  }
}
