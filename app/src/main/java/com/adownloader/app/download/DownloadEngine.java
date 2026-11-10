package com.adownloader.app.download;

import com.adownloader.app.MediaCandidate;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Browser-independent VOD engine. Output is always validated before publication. */
public final class DownloadEngine {
  public interface Progress {
    void update(String text, int percent);
  }

  public static final class Result {
    public final File file;
    public final String mime, extension;

    public Result(File file, String mime, String extension) {
      this.file = file;
      this.mime = mime;
      this.extension = extension;
    }
  }

  private final File directory;
  private final Progress progress;
  private final EngineCheckpoint checkpoint;
  private final TransferControl control;
  private final java.io.FileDescriptor destination;
  private int temporaryIndex;
  private final List<File> temporary = new ArrayList<>();

  public DownloadEngine(File directory, Progress progress) {
    this(directory, progress, null, new TransferControl());
  }

  public DownloadEngine(
      File directory, Progress progress, EngineCheckpoint checkpoint, TransferControl control) {
    this(directory, progress, checkpoint, control, null);
  }

  public DownloadEngine(
      File directory,
      Progress progress,
      EngineCheckpoint checkpoint,
      TransferControl control,
      java.io.FileDescriptor destination) {
    this.directory = directory;
    this.progress = progress;
    this.checkpoint = checkpoint;
    this.control = control;
    this.destination = destination;
  }

  private File temp(String suffix) throws IOException {
    File f =
        checkpoint == null
            ? File.createTempFile("adownloader_", suffix, directory)
            : new File(directory, "output-" + (temporaryIndex++) + suffix);
    temporary.add(f);
    return f;
  }

  public void cleanup() {
    for (File file : temporary) file.delete();
    temporary.clear();
  }

  private void copy(HttpSource source, String url, File file) throws Exception {
    if (checkpoint == null) source.copy(url, file, progress);
    else checkpoint.copy(source, url, file, control);
  }

  private static byte[] segmentIv(ManifestParser.Part part) throws DownloadError {
    byte[] iv = new byte[16];
    if (part.iv != null) {
      String hex = part.iv.replaceFirst("(?i)^0x", "");
      if (hex.length() > 32 || !hex.matches("[0-9a-fA-F]+"))
        throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
      hex = "0".repeat(32 - hex.length()) + hex;
      for (int i = 0; i < 16; i++)
        iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    } else {
      long sequence = part.sequence;
      for (int i = 15; i >= 0; i--) {
        iv[i] = (byte) sequence;
        sequence >>>= 8;
      }
    }
    return iv;
  }

  private static boolean refreshable(DownloadError.Category category) {
    return category == DownloadError.Category.AUTH
        || category == DownloadError.Category.EXPIRED_URL
        || category == DownloadError.Category.ACCESS_BLOCKED;
  }

  /** Concurrent segment requests per track; the plan order is kept for the mux. */
  static final int SEGMENT_PARALLELISM = 4;

  private static void closeAll(List<List<android.media.MediaDataSource>> inputs) {
    for (var groups : inputs)
      for (var input : groups)
        try {
          input.close();
        } catch (IOException ignored) {
        }
  }

  /**
   * Fetches every part of one track with bounded concurrency. Each part is still staged, synced,
   * renamed and checkpointed individually, so recovery semantics are unchanged; only the network
   * wait is overlapped. The first failure stops the remaining workers and is rethrown.
   */
  private List<File> fetchSegments(
      HttpSource source, ManifestParser.Track track, int trackIndex, int parallelism)
      throws Exception {
    int count = track.parts.size();
    File[] files = new File[count];
    for (int i = 0; i < count; i++) {
      files[i] = new File(directory, "part-" + trackIndex + "-" + i);
      if (checkpoint == null) {
        temporary.add(files[i]);
        temporary.add(new File(directory, files[i].getName() + ".partial"));
      }
    }
    Map<String, byte[]> keys = new HashMap<>();
    AtomicInteger next = new AtomicInteger(), finished = new AtomicInteger();
    AtomicReference<Exception> failure = new AtomicReference<>();
    FailureDiagnostics.Session diagnostics = FailureDiagnostics.current();
    int workers = Math.max(1, Math.min(parallelism, count));
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      List<Future<?>> running = new ArrayList<>();
      for (int w = 0; w < workers; w++)
        running.add(
            pool.submit(
                () -> {
                  try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(diagnostics)) {
                    int i;
                    while (failure.get() == null && (i = next.getAndIncrement()) < count) {
                      control.check();
                      fetchSegment(source, track, trackIndex, i, files[i], keys);
                      int done = finished.incrementAndGet();
                      progress.update(
                          (track.audio ? "오디오" : "영상") + " 조각 " + done + "/" + count,
                          done * 100 / count);
                    }
                  } catch (Exception error) {
                    failure.compareAndSet(null, error);
                  }
                  return null;
                }));
      for (Future<?> worker : running) worker.get();
    } finally {
      pool.shutdownNow();
      synchronized (keys) {
        for (byte[] key : keys.values()) Arrays.fill(key, (byte) 0);
        keys.clear();
      }
    }
    control.check();
    if (failure.get() != null) throw failure.get();
    return new ArrayList<>(Arrays.asList(files));
  }

  private byte[] key(HttpSource source, String url, Map<String, byte[]> keys) throws Exception {
    synchronized (keys) {
      byte[] key = keys.get(url);
      if (key == null) {
        FailureDiagnostics.phase(FailureDiagnostics.Stage.AES_KEY);
        key = source.bytes(url, 0, -1, 1024);
        if (key.length != 16) throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
        keys.put(url, key);
      }
      return key.clone();
    }
  }

  private void fetchSegment(
      HttpSource source,
      ManifestParser.Track track,
      int trackIndex,
      int i,
      File segment,
      Map<String, byte[]> keys)
      throws Exception {
    ManifestParser.Part part = track.parts.get(i);
    FailureDiagnostics.event(
        Map.of(
            "action",
            "PHASE",
            "stage",
            part.initialization ? "INIT_SEGMENT" : track.audio ? "AUDIO_SEGMENT" : "VIDEO_SEGMENT",
            "segmentIndex",
            i));
    if (checkpoint != null && checkpoint.verified(segment, trackIndex, i)) return;
    HttpSource.Decoder decoder = null;
    byte[] secret = null;
    if (part.keyUrl != null) {
      secret = key(source, part.keyUrl, keys);
      final byte[] key = secret, iv = segmentIv(part);
      decoder =
          input -> {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new javax.crypto.CipherInputStream(input, cipher);
          };
    }
    File staging = new File(directory, segment.getName() + ".partial");
    try {
      source.stream(part.url, part.offset, part.length, staging, decoder);
    } finally {
      if (secret != null) Arrays.fill(secret, (byte) 0);
    }
    control.check();
    if (!staging.renameTo(segment)) throw new DownloadError(DownloadError.Category.STORAGE);
    if (checkpoint != null) checkpoint.saved(segment, trackIndex, i);
  }

  public Result download(String url, MediaCandidate.MediaType type, Map<String, String> headers)
      throws Exception {
    if (type == MediaCandidate.MediaType.PROTECTED)
      throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
    FailureDiagnostics.phase(
        type == MediaCandidate.MediaType.HLS || type == MediaCandidate.MediaType.RELAY_HLS
            ? FailureDiagnostics.Stage.ROOT_MANIFEST
            : type == MediaCandidate.MediaType.DASH
                ? FailureDiagnostics.Stage.DASH_MPD
                : FailureDiagnostics.Stage.DIRECT_MEDIA);
    control.check();
    if (checkpoint != null) {
      Result finalized = checkpoint.loadFinal();
      if (finalized != null) return finalized;
    }
    if (checkpoint != null && destination != null) {
      Result finalized = checkpoint.loadDestinationFinal(destination, control);
      if (finalized != null) return finalized;
    }
    try (HttpSource source =
        new HttpSource(url, headers, control)
            .previouslyAccepted(checkpoint != null && checkpoint.previouslyAccepted())) {
      if (checkpoint != null) {
        source.pageContext(checkpoint.topLevelPage());
        source.resourceContexts(checkpoint.requestContexts());
        source.restoreCookies(checkpoint.cookieState());
        source.onCookies(checkpoint::cookieState);
        source.onContext(checkpoint::requestContext);
        source.browserScope(checkpoint.browserTabId(), checkpoint.browserFrameId());
      }
      source.relay(type == MediaCandidate.MediaType.RELAY_HLS);
      if (type == MediaCandidate.MediaType.HLS
          || type == MediaCandidate.MediaType.RELAY_HLS
          || type == MediaCandidate.MediaType.DASH) {
        List<ManifestParser.Track> tracks = checkpoint == null ? null : checkpoint.loadPlan();
        if (tracks != null
            && checkpoint != null
            && (checkpoint.cookieBootstrap() || type == MediaCandidate.MediaType.RELAY_HLS)) {
          var fresh =
              MediaProbeService.shared().forTransfer(source, url, type, headers, control).tracks();
          if (!RefreshPlanMatcher.compatible(tracks, fresh))
            throw new DownloadError(DownloadError.Category.EXPIRED_URL);
          tracks = fresh;
          checkpoint.savePlan(tracks);
        }
        if (tracks == null) {
          MediaProbeResult probe =
              MediaProbeService.shared().forTransfer(source, url, type, headers, control);
          tracks = probe.tracks();
          if (checkpoint != null) checkpoint.sourceMetadata(probe.disposition);
          if (checkpoint != null) checkpoint.savePlan(tracks);
        }
        tracks = new ArrayList<>(tracks);
        source.registerTracks(tracks);
        boolean planRefreshed = false;
        StorageBudget.Estimate budget =
            StorageBudget.estimate(
                tracks, checkpoint == null ? 0 : checkpoint.committedSegmentBytes());
        progress.update(budget.exact ? "저장 공간 확인 중" : "저장 공간 확인 중 · 실제 용량은 서버 응답에 따라 달라집니다", 0);
        FailureDiagnostics.phase(FailureDiagnostics.Stage.STORAGE);
        StorageBudget.require(budget, directory.getUsableSpace());
        if (destination != null)
          try (DescriptorChannel target = new DescriptorChannel(destination)) {
            target.reserve(Math.max(0, budget.additional - StorageBudget.SAFETY));
          }
        List<List<android.media.MediaDataSource>> inputs = new ArrayList<>();
        double duration = 0;
        boolean video = false, audio = false;
        try {
          for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            ManifestParser.Track track = tracks.get(trackIndex);
            File direct =
                checkpoint == null
                    ? null
                    : temp(".media"); // Reserve existing durable names without creating an unused
            // file.
            duration = Math.max(duration, track.duration);
            audio |= track.audio;
            video |= !track.audio;
            if (track.parts.size() == 1
                && track.parts.get(0).length < 0
                && track.parts.get(0).keyUrl == null) {
              if (direct == null) direct = temp(".media");
              copy(source, track.parts.get(0).url, direct);
              inputs.add(
                  new ArrayList<>(
                      List.of(new JoinedMediaDataSource(Collections.singletonList(direct)))));
              continue;
            }
            int parallelism =
                type == MediaCandidate.MediaType.RELAY_HLS ? 1 : SEGMENT_PARALLELISM;
            List<File> parts;
            try {
              parts = fetchSegments(source, track, trackIndex, parallelism);
            } catch (DownloadError expired) {
              // Signed segment addresses often expire during long background transfers. Fetch the
              // playlist again (through the browser's network stack) and continue only if it
              // describes exactly the same media and quality; otherwise report as before.
              if (planRefreshed
                  || type == MediaCandidate.MediaType.RELAY_HLS
                  || !refreshable(expired.category)) throw expired;
              planRefreshed = true;
              List<ManifestParser.Track> fresh;
              try {
                fresh = MediaProbeService.shared().fresh(source, url, type).tracks();
              } catch (TransferControl.Stopped stopped) {
                throw stopped;
              } catch (Exception unavailable) {
                throw expired;
              }
              if (!RefreshPlanMatcher.compatible(tracks, fresh)) throw expired;
              FailureDiagnostics.event(Map.of("action", "PLAN_REFRESHED", "trackIndex", trackIndex));
              tracks = new ArrayList<>(fresh);
              if (checkpoint != null) checkpoint.savePlan(tracks);
              source.registerTracks(tracks);
              track = tracks.get(trackIndex);
              parts = fetchSegments(source, track, trackIndex, parallelism);
            }
            List<android.media.MediaDataSource> groups = new ArrayList<>();
            inputs.add(groups);
            for (List<File> group : SegmentGroups.split(track.parts, parts))
              groups.add(new JoinedMediaDataSource(group));
          }
          File output = destination == null ? temp(".mp4") : null;
          if (destination != null) {
            long estimate = 0;
            for (var groups : inputs)
              for (var input : groups) estimate = Math.addExact(estimate, input.getSize());
            try (DescriptorChannel target = new DescriptorChannel(destination)) {
              target.reserve(Math.addExact(estimate, estimate / 10));
            }
          }
          progress.update("원본 트랙 무손실 병합 및 검증 중", 100);
          control.check();
          FailureDiagnostics.phase(FailureDiagnostics.Stage.MUX);
          if (destination == null) PostProcessor.muxSources(inputs, output, control);
          else PostProcessor.muxSources(inputs, destination, control);
          control.check();
          FailureDiagnostics.phase(FailureDiagnostics.Stage.FINAL_INSPECTION);
          if (destination == null) PostProcessor.inspect(output, duration, video, audio);
          else PostProcessor.inspect(destination, duration, video, audio);
          if (checkpoint != null && destination != null) {
            closeAll(inputs);
            checkpoint.saveDestinationFinal(destination, "video/mp4", ".mp4", control);
          }
          return new Result(output, "video/mp4", ".mp4");
        } finally {
          closeAll(inputs);
        }
      }
      if (destination != null) {
        if (checkpoint == null) throw new DownloadError(DownloadError.Category.STORAGE);
        checkpoint.copy(source, url, destination, control);
        FailureDiagnostics.phase(FailureDiagnostics.Stage.FINAL_INSPECTION);
        PostProcessor.Inspection inspection =
            PostProcessor.inspect(
                destination,
                0,
                type == MediaCandidate.MediaType.VIDEO,
                type == MediaCandidate.MediaType.AUDIO);
        byte[] signature = new byte[16];
        try (DescriptorChannel input = new DescriptorChannel(destination)) {
          input.read(java.nio.ByteBuffer.wrap(signature));
        }
        boolean webm =
            (signature[0] & 255) == 0x1a
                && (signature[1] & 255) == 0x45
                && (signature[2] & 255) == 0xdf
                && (signature[3] & 255) == 0xa3;
        boolean mp4 =
            signature[4] == 'f'
                && signature[5] == 't'
                && signature[6] == 'y'
                && signature[7] == 'p';
        if (webm) return new Result(null, inspection.video ? "video/webm" : "audio/webm", ".webm");
        if (mp4)
          return new Result(
              null,
              inspection.video ? "video/mp4" : "audio/mp4",
              inspection.video ? ".mp4" : ".m4a");
        // A rare container conversion retains a durable finalized file before overwriting its input
        // URI.
        File output = temp(".mp4");
        FailureDiagnostics.phase(FailureDiagnostics.Stage.MUX);
        PostProcessor.muxDescriptor(destination, output, control);
        PostProcessor.inspect(
            output, inspection.durationUs / 1000000.0, inspection.video, inspection.audio);
        try (RandomAccessFile synced = new RandomAccessFile(output, "rw")) {
          synced.getFD().sync();
        }
        String mime = inspection.video ? "video/mp4" : "audio/mp4",
            extension = inspection.video ? ".mp4" : ".m4a";
        checkpoint.saveFinal(output, mime, extension);
        return new Result(output, mime, extension);
      }
      File file = temp(".media");
      copy(source, url, file);
      FailureDiagnostics.phase(FailureDiagnostics.Stage.FINAL_INSPECTION);
      PostProcessor.Inspection inspection =
          PostProcessor.inspect(
              file,
              0,
              type == MediaCandidate.MediaType.VIDEO,
              type == MediaCandidate.MediaType.AUDIO);
      byte[] signature = new byte[16];
      try (InputStream in = new FileInputStream(file)) {
        in.read(signature);
      }
      boolean webm =
          (signature[0] & 255) == 0x1a
              && (signature[1] & 255) == 0x45
              && (signature[2] & 255) == 0xdf
              && (signature[3] & 255) == 0xa3;
      boolean mp4 =
          signature[4] == 'f' && signature[5] == 't' && signature[6] == 'y' && signature[7] == 'p';
      if (webm) return new Result(file, inspection.video ? "video/webm" : "audio/webm", ".webm");
      if (mp4)
        return new Result(
            file, inspection.video ? "video/mp4" : "audio/mp4", inspection.video ? ".mp4" : ".m4a");
      // Repack other supported containers rather than assigning them a false .mp4 extension.
      File output = temp(".mp4");
      FailureDiagnostics.phase(FailureDiagnostics.Stage.MUX);
      PostProcessor.mux(Collections.singletonList(file), output, control);
      PostProcessor.inspect(
          output, inspection.durationUs / 1_000_000.0, inspection.video, inspection.audio);
      return new Result(
          output, inspection.video ? "video/mp4" : "audio/mp4", inspection.video ? ".mp4" : ".m4a");
    }
  }
}
