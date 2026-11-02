package com.adownloader.app.download;

import com.adownloader.app.MediaCandidate;
import com.adownloader.app.media.MediaIdentity;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded in-memory sharing. An Activity closing cannot interrupt the shared network owner. */
public final class MediaProbeService implements AutoCloseable {
  private static final MediaProbeService SHARED = new MediaProbeService(60000);

  public static MediaProbeService shared() {
    return SHARED;
  }

  private static final class Entry {
    final CompletableFuture<MediaProbeResult> future = new CompletableFuture<>();
    volatile MediaProbeResult result;
  }

  private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>();
  private final ScheduledExecutorService io;
  private final long ttl;

  public MediaProbeService(long ttl) {
    this.ttl = ttl;
    io =
        Executors.newScheduledThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "media-probe");
              t.setDaemon(true);
              return t;
            });
    io.scheduleAtFixedRate(this::expire, 10, 10, TimeUnit.SECONDS);
  }

  private synchronized void expire() {
    long now = System.currentTimeMillis();
    cache
        .entrySet()
        .removeIf(e -> e.getValue().result != null && e.getValue().result.expiresAt <= now);
  }

  public MediaProbeResult preview(
      String url, MediaCandidate.MediaType type, Map<String, String> headers) throws Exception {
    return get(
        url, type, headers, new TransferControl(), true, null, Collections.emptyMap(), -1, -1, "");
  }

  public MediaProbeResult probe(
      String url,
      MediaCandidate.MediaType type,
      Map<String, String> headers,
      TransferControl control)
      throws Exception {
    return get(url, type, headers, control, false, null, Collections.emptyMap(), -1, -1, "");
  }

  public MediaProbeResult preview(MediaCandidate item) throws Exception {
    return candidate(item, new TransferControl(), true);
  }

  public MediaProbeResult probe(MediaCandidate item, TransferControl control) throws Exception {
    return candidate(item, control, false);
  }

  private MediaProbeResult candidate(MediaCandidate item, TransferControl control, boolean detached)
      throws Exception {
    FailureDiagnostics.Session parent = FailureDiagnostics.current();
    Map<String, String> metadata = new LinkedHashMap<>(item.getMetadata());
    metadata.put("detectionSource", item.getSource());
    FailureDiagnostics.Session local =
        parent == null
            ? new FailureDiagnostics.Session(
                0, item.getType().name(), item.getUrl(), item.getPageUrl(), metadata)
            : null;
    try (var scope = FailureDiagnostics.activate(local == null ? parent : local)) {
      try {
        return get(
            item.getUrl(),
            item.getType(),
            item.getHeaders(),
            control,
            detached,
            item.getPageUrl(),
            contexts(item),
            scope(item, "browserTabId"),
            scope(item, "frameId"),
            item.getMetadata().getOrDefault("mediaObservedAt", ""));
      } catch (Exception failure) {
        if (local != null && !(failure instanceof TransferControl.Stopped))
          local.fail(failure, FailureDiagnostics.category(failure), "PROBE_FAILURE");
        throw failure;
      } finally {
        if (local != null) local.complete();
      }
    }
  }

  public record PlaybackPlan(
      MediaProbeResult probe,
      Map<String, String> headers,
      Map<String, Map<String, String>> contexts,
      String cookieState) {
    public List<ManifestParser.Track> tracks() {
      return probe.tracks();
    }

    public String disposition() {
      return probe.disposition;
    }
  }

  /** Resolve only already captured evidence before the queued job is acknowledged to the UI. */
  public PlaybackPlan prepareCaptured(MediaCandidate item) {
    return prepareCaptured(item, new TransferControl());
  }

  public PlaybackPlan prepareCaptured(MediaCandidate item, TransferControl control) {
    if (item.getType() != MediaCandidate.MediaType.HLS
        && item.getType() != MediaCandidate.MediaType.DASH) return null;
    if (MediaNetworkSession.provider() == null) return null;
    Map<String, Map<String, String>> contexts = contexts(item);
    try (HttpSource source =
        new HttpSource(item.getUrl(), item.getHeaders(), control)
            .pageContext(item.getPageUrl())
            .browserScope(scope(item, "browserTabId"), scope(item, "frameId"))
            .resourceContexts(contexts)
            .capturedManifestsOnly()
            .onContext((url, headers) -> contexts.put(url, new LinkedHashMap<>(headers)))) {
      var result = fetchWithSource(source, item.getUrl(), item.getType(), null);
      Map<String, String> finalHeaders =
          contexts.getOrDefault(
              result.finalManifestUrl,
              result.finalManifestUrl.equals(item.getUrl()) ? item.getHeaders() : Map.of());
      return new PlaybackPlan(
          result,
          finalHeaders,
          contexts,
          source.cookieStateFor(result.finalManifestUrl, finalHeaders));
    } catch (Exception unavailable) {
      return null;
    }
  }

  private static Map<String, Map<String, String>> contexts(MediaCandidate item) {
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    try {
      String raw = item.getMetadata().getOrDefault("requestContexts", "");
      if (raw.length() > 8388608 || raw.isEmpty()) return out;
      org.json.JSONObject all = new org.json.JSONObject(raw);
      boolean verified = "2".equals(item.getMetadata().get("requestContextVersion"));
      for (Iterator<String> it = all.keys(); it.hasNext() && out.size() < 128; ) {
        String url = it.next();
        org.json.JSONObject h = all.optJSONObject(url);
        if (h == null) continue;
        Map<String, String> values = new LinkedHashMap<>();
        for (Iterator<String> keys = h.keys(); keys.hasNext(); ) {
          String k = keys.next();
          if (verified || !MediaHeaders.sensitive(k) || k.equalsIgnoreCase("cookie"))
            values.put(k, h.optString(k));
        }
        out.put(url, MediaHeaders.select(values));
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private static int scope(MediaCandidate item, String key) {
    try {
      return Integer.parseInt(item.getMetadata().getOrDefault(key, "-1"));
    } catch (Exception ignored) {
      return -1;
    }
  }

  private static String pageKey(String page) {
    try {
      URI u = URI.create(page);
      return u.getHost() != null && Set.of("http", "https").contains(u.getScheme())
          ? CookieSites.site(u)
          : "";
    } catch (Exception e) {
      return "";
    }
  }

  public MediaProbeResult forTransfer(
      HttpSource source,
      String url,
      MediaCandidate.MediaType type,
      Map<String, String> headers,
      TransferControl control)
      throws Exception {
    TreeMap<String, String> canonical = new TreeMap<>();
    headers.forEach((k, v) -> canonical.put(k.toLowerCase(Locale.ROOT), v));
    Entry found;
    synchronized (this) {
      found =
          cache.get(
              MediaIdentity.hash(
                  type
                      + "|"
                      + url
                      + "|"
                      + canonical
                      + "|"
                      + source.pageScopeKey()
                      + "|"
                      + source.browserScopeKey()
                      + "|"));
    }
    if (found != null) {
      while (!found.future.isDone()) {
        control.check();
        try {
          found.future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException wait) {
        } catch (ExecutionException failure) {
          break;
        }
      }
      MediaProbeResult ready = found.result;
      if (ready != null
          && ready.expiresAt > System.currentTimeMillis()
          && !ready.requiresCookieSession) return ready;
    }
    return fetchWithSource(source, url, type, null);
  }

  private MediaProbeResult get(
      String url,
      MediaCandidate.MediaType type,
      Map<String, String> headers,
      TransferControl control,
      boolean detached,
      String page,
      Map<String, Map<String, String>> contexts,
      int tab,
      int frame,
      String evidence)
      throws Exception {
    TreeMap<String, String> canonical = new TreeMap<>();
    headers.forEach((k, v) -> canonical.put(k.toLowerCase(Locale.ROOT), v));
    String key =
        MediaIdentity.hash(
            type
                + "|"
                + url
                + "|"
                + canonical
                + "|"
                + (page == null ? "" : page)
                + "|"
                + tab
                + ":"
                + frame
                + "|"
                + evidence);
    Entry entry;
    boolean created = false;
    MediaProbeResult previous = null;
    synchronized (this) {
      entry = cache.get(key);
      if (entry != null
          && entry.result != null
          && entry.result.expiresAt <= System.currentTimeMillis()) {
        previous = entry.result;
        cache.remove(key);
        entry = null;
      }
      if (entry == null) {
        if (cache.size() >= 32) {
          String completed = null;
          for (Map.Entry<String, Entry> e : cache.entrySet())
            if (e.getValue().future.isDone()) {
              completed = e.getKey();
              break;
            }
          if (completed == null) throw new DownloadError(DownloadError.Category.NETWORK);
          cache.remove(completed);
        }
        entry = new Entry();
        cache.put(key, entry);
        created = true;
      }
    }
    if (created) {
      FailureDiagnostics.Session diagnostic = FailureDiagnostics.current();
      boolean ownsDiagnostic = diagnostic == null;
      if (diagnostic == null)
        diagnostic = new FailureDiagnostics.Session(0, type.name(), url, page, Map.of());
      FailureDiagnostics.Session diagnosticOwner = diagnostic;
      Entry owner = entry;
      MediaProbeResult old = previous;
      Runnable fetch =
          () -> {
            try (var diagnosticScope = FailureDiagnostics.activate(diagnosticOwner)) {
              MediaProbeResult result =
                  fetch(
                      url,
                      type,
                      new LinkedHashMap<>(headers),
                      old,
                      detached ? new TransferControl() : control,
                      page,
                      contexts,
                      tab,
                      frame);
              if (result.weight() > 16L * 1024 * 1024)
                throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
              owner.result = result;
              owner.future.complete(result);
              synchronized (this) {
                long weight = 0;
                for (Entry e : cache.values()) if (e.result != null) weight += e.result.weight();
                Iterator<Map.Entry<String, Entry>> iterator = cache.entrySet().iterator();
                while (weight > 16L * 1024 * 1024 && iterator.hasNext()) {
                  Entry e = iterator.next().getValue();
                  if (e.result != null && e != owner) {
                    weight -= e.result.weight();
                    iterator.remove();
                  }
                }
              }
            } catch (Exception error) {
              if (ownsDiagnostic && !(error instanceof TransferControl.Stopped))
                diagnosticOwner.fail(error, FailureDiagnostics.category(error), "PROBE_FAILURE");
              owner.future.completeExceptionally(error);
              synchronized (this) {
                cache.remove(key, owner);
              }
            } finally {
              if (ownsDiagnostic) diagnosticOwner.complete();
            }
          };
      if (detached) io.execute(fetch);
      else fetch.run();
    }
    while (true) {
      control.check();
      try {
        return entry.future.get(100, TimeUnit.MILLISECONDS);
      } catch (TimeoutException wait) {
      } catch (ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        throw new IllegalStateException("PROBE_FAILED");
      }
    }
  }

  private MediaProbeResult fetch(
      String url,
      MediaCandidate.MediaType type,
      Map<String, String> headers,
      MediaProbeResult previous,
      TransferControl control,
      String page,
      Map<String, Map<String, String>> contexts,
      int tab,
      int frame)
      throws Exception {
    try (HttpSource source =
        new HttpSource(url, headers, control)
            .pageContext(page)
            .resourceContexts(contexts)
            .browserScope(tab, frame)) {
      return fetchWithSource(
          source, url, type, previous != null && previous.requiresCookieSession ? null : previous);
    }
  }

  private MediaProbeResult fetchWithSource(
      HttpSource source, String url, MediaCandidate.MediaType type, MediaProbeResult previous)
      throws Exception {
    Map<String, HttpSource.Document> documents = new LinkedHashMap<>();
    HttpSource.Document root = document(source, url, previous, documents);
    List<ManifestParser.Track> tracks;
    if (type == MediaCandidate.MediaType.HLS) {
      String rootText = HlsDiagnostic.body(root, "root");
      ManifestParser.HlsSelection selection;
      try {
        selection = ManifestParser.hlsSelection(root.url, rootText);
      } catch (RuntimeException error) {
        throw HlsDiagnostic.parse(root, rootText, "master", error);
      }
      HttpSource.Document video =
          selection.video.equals(root.url)
              ? root
              : document(source, selection.video, previous, documents);
      String videoText = HlsDiagnostic.body(video, video == root ? "root" : "variant");
      ManifestParser.Track track;
      try {
        track = ManifestParser.hlsTrack(video.url, videoText);
      } catch (RuntimeException error) {
        throw HlsDiagnostic.parse(video, videoText, "media", error);
      }
      tracks = new ArrayList<>();
      tracks.add(track);
      Map<String, String> attrs = selection.selectedAttributes;
      if (!attrs.isEmpty()) {
        try {
          String[] size = attrs.getOrDefault("RESOLUTION", "0x0").split("x");
          if (size.length == 2) {
            track.width = Integer.parseInt(size[0]);
            track.height = Integer.parseInt(size[1]);
          }
          track.bitRate = Long.parseLong(attrs.getOrDefault("BANDWIDTH", "0"));
          track.frameRate = Double.parseDouble(attrs.getOrDefault("FRAME-RATE", "0"));
          track.codecs = attrs.getOrDefault("CODECS", "");
          track.representation = selection.video;
        } catch (RuntimeException error) {
          throw HlsDiagnostic.parse(root, rootText, "master", error);
        }
      }
      if (selection.audio != null) {
        HttpSource.Document audio =
            source.document(
                selection.audio,
                previous == null ? null : previous.documents.get(selection.audio),
                "audio");
        documents.put(selection.audio, audio);
        ManifestParser.Track a;
        try {
          a = ManifestParser.hlsTrack(audio.url, HlsDiagnostic.body(audio, "audio"));
        } catch (RuntimeException error) {
          throw HlsDiagnostic.parse(audio, audio.text, "media", error);
        }
        a.audio = true;
        a.representation = selection.audio;
        tracks.add(a);
      }
    } else if (type == MediaCandidate.MediaType.DASH)
      tracks = ManifestParser.dash(root.url, root.text);
    else throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
    Map<String, Object> manifestInfo = new LinkedHashMap<>();
    manifestInfo.put("segmentCount", tracks.stream().mapToInt(t -> t.parts.size()).sum());
    manifestInfo.put("audioGroupPresent", tracks.stream().anyMatch(t -> t.audio));
    manifestInfo.put(
        "selectedHeight",
        tracks.stream().filter(t -> !t.audio).mapToInt(t -> t.height).max().orElse(0));
    manifestInfo.put(
        "selectedWidth",
        tracks.stream().filter(t -> !t.audio).mapToInt(t -> t.width).max().orElse(0));
    manifestInfo.put("variantCount", variantCount(root.text));
    FailureDiagnostics.event(Map.of("action", "MANIFEST_PARSED", "manifest", manifestInfo));
    return new MediaProbeResult(
        root,
        documents,
        tracks,
        Math.min(
            source.manifestEvidenceExpiresAt(),
            Math.min(System.currentTimeMillis() + ttl, expiry(url))),
        source.hasResponseCookies());
  }

  private HttpSource.Document document(
      HttpSource source,
      String url,
      MediaProbeResult previous,
      Map<String, HttpSource.Document> documents)
      throws Exception {
    HttpSource.Document hit = documents.get(url);
    if (hit != null) return hit;
    HttpSource.Document old = previous == null ? null : previous.documents.get(url);
    HttpSource.Document result =
        source.document(url, old, documents.isEmpty() ? "root" : "variant");
    documents.put(url, result);
    if (!url.equals(result.url)) documents.put(result.url, result);
    long size = 0;
    for (HttpSource.Document d : new HashSet<>(documents.values())) size += d.text.length() * 2L;
    if (size > 16L * 1024 * 1024) throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
    return result;
  }

  private static int variantCount(String text) {
    int count = 0, at = 0;
    while ((at = text.indexOf("#EXT-X-STREAM-INF:", at)) >= 0) {
      if (at == 0 || text.charAt(at - 1) == '\n') count++;
      at += 18;
    }
    return count;
  }

  private static long expiry(String url) {
    return com.adownloader.app.media.CdnQueryPolicy.expiresAt(url);
  }

  @Override
  public synchronized void close() {
    io.shutdownNow();
    cache.clear();
  }
}
