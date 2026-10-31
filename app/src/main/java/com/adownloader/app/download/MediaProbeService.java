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
    return get(url, type, headers, new TransferControl(), true);
  }

  public MediaProbeResult probe(
      String url,
      MediaCandidate.MediaType type,
      Map<String, String> headers,
      TransferControl control)
      throws Exception {
    return get(url, type, headers, control, false);
  }

  private MediaProbeResult get(
      String url,
      MediaCandidate.MediaType type,
      Map<String, String> headers,
      TransferControl control,
      boolean detached)
      throws Exception {
    TreeMap<String, String> canonical = new TreeMap<>();
    headers.forEach((k, v) -> canonical.put(k.toLowerCase(Locale.ROOT), v));
    String key = MediaIdentity.hash(type + "|" + url + "|" + canonical);
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
      Entry owner = entry;
      MediaProbeResult old = previous;
      Runnable fetch =
          () -> {
            try {
              MediaProbeResult result =
                  fetch(
                      url,
                      type,
                      new LinkedHashMap<>(headers),
                      old,
                      detached ? new TransferControl() : control);
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
              owner.future.completeExceptionally(error);
              synchronized (this) {
                cache.remove(key, owner);
              }
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
      TransferControl control)
      throws Exception {
    HttpSource source = new HttpSource(url, headers, control);
    Map<String, HttpSource.Document> documents = new LinkedHashMap<>();
    HttpSource.Document root = document(source, url, previous, documents);
    List<ManifestParser.Track> tracks;
    if (type == MediaCandidate.MediaType.HLS) {
      ManifestParser.HlsSelection selection = ManifestParser.hlsSelection(root.url, root.text);
      HttpSource.Document video =
          selection.video.equals(root.url)
              ? root
              : document(source, selection.video, previous, documents);
      ManifestParser.Track track = ManifestParser.hlsTrack(video.url, video.text);
      tracks = new ArrayList<>();
      tracks.add(track);
      String[] lines = root.text.split("\\r?\\n");
      for (int i = 0; i < lines.length; i++)
        if (lines[i].trim().startsWith("#EXT-X-STREAM-INF:")) {
          int next = i + 1;
          while (next < lines.length && lines[next].trim().isEmpty()) next++;
          if (next < lines.length
              && ManifestParser.resolve(root.url, lines[next].trim()).equals(selection.video)) {
            Map<String, String> attrs = ManifestParser.attributes(lines[i].trim());
            String[] size = attrs.getOrDefault("RESOLUTION", "0x0").split("x");
            if (size.length == 2) {
              track.width = Integer.parseInt(size[0]);
              track.height = Integer.parseInt(size[1]);
            }
            track.frameRate = Double.parseDouble(attrs.getOrDefault("FRAME-RATE", "0"));
            track.codecs = attrs.getOrDefault("CODECS", "");
            track.representation = selection.video;
            break;
          }
        }
      if (selection.audio != null) {
        HttpSource.Document audio = document(source, selection.audio, previous, documents);
        ManifestParser.Track a = ManifestParser.hlsTrack(audio.url, audio.text);
        a.audio = true;
        a.representation = selection.audio;
        tracks.add(a);
      }
    } else if (type == MediaCandidate.MediaType.DASH)
      tracks = ManifestParser.dash(root.url, root.text);
    else throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
    return new MediaProbeResult(
        root, documents, tracks, Math.min(System.currentTimeMillis() + ttl, expiry(url)));
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
    HttpSource.Document result = source.document(url, old);
    documents.put(url, result);
    if (!url.equals(result.url)) documents.put(result.url, result);
    long size = 0;
    for (HttpSource.Document d : new HashSet<>(documents.values())) size += d.text.length() * 2L;
    if (size > 16L * 1024 * 1024) throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
    return result;
  }

  private static long expiry(String url) {
    try {
      String query = URI.create(url).getRawQuery();
      if (query != null)
        for (String part : query.split("&")) {
          String[] kv = part.split("=", 2);
          if (kv.length == 2 && kv[0].equalsIgnoreCase("expires")) {
            long seconds = Long.parseLong(kv[1]);
            if (seconds > 1500000000L && seconds < Long.MAX_VALUE / 1000) return seconds * 1000;
          }
        }
    } catch (Exception ignored) {
    }
    return Long.MAX_VALUE;
  }

  @Override
  public synchronized void close() {
    io.shutdownNow();
    cache.clear();
  }
}
