package com.adownloader.app.browser;

import android.content.Context;
import android.os.Handler;
import com.adownloader.app.download.*;
import com.adownloader.app.media.MediaDetector;
import java.util.*;
import java.util.function.IntFunction;
import org.json.JSONObject;

/** A quiet page or a video element alone is never treated as a detection failure. */
public final class DetectionFailureWatchdog implements AutoCloseable {
  private final Handler handler;
  private final IntFunction<MediaDetector> detectors;
  private final long delay;
  private final Map<Integer, String> pages = new HashMap<>();
  private final Map<Integer, Runnable> pending = new HashMap<>();
  private final Set<Integer> reported = new HashSet<>();
  private final Set<Integer> playbackSeen = new HashSet<>();

  public DetectionFailureWatchdog(
      Context context, Handler handler, IntFunction<MediaDetector> detectors, long delay) {
    this.handler = handler;
    this.detectors = detectors;
    this.delay = delay;
    com.adownloader.app.diagnostics.FailureJournal.get(context).install();
  }

  public void page(int tab, String page) {
    if (!Objects.equals(pages.get(tab), page)) {
      discard(tab);
      if (!FailureDiagnostics.host(page).isEmpty()) pages.put(tab, page);
    }
  }

  public void candidate(int tab) {
    Runnable r = pending.remove(tab);
    if (r != null) handler.removeCallbacks(r);
  }

  public void unsupportedTransport(int tab, String url, String page) {
    if (!Objects.equals(pages.get(tab), page) || !reported.add(tab)) return;
    candidate(tab);
    var diagnostic =
        new FailureDiagnostics.Session(
            0, "ENCRYPTED_TRANSPORT", url, page, Map.of("detectionSource", "protected-response"));
    diagnostic.event(
        Map.of(
            "stage",
            "DETECTION",
            "transport",
            "gecko",
            "status",
            200,
            "host",
            FailureDiagnostics.host(url),
            "body",
            "ENCRYPTED",
            "action",
            "PROTECTED_RESPONSE"));
    diagnostic.fail(
        new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT),
        DownloadError.Category.UNSUPPORTED_FORMAT,
        "ENCRYPTED_TRANSPORT_UNSUPPORTED");
    diagnostic.complete();
  }

  public void metrics(int tab, JSONObject metrics) {
    if (metrics != null && metrics.optInt("playbackObserved") > 0) playbackSeen.add(tab);
    if (metrics == null
        || !pages.containsKey(tab)
        || reported.contains(tab)
        || pending.containsKey(tab)) return;
    boolean playback = metrics.optInt("playbackObserved") > 0,
        mse = metrics.optInt("blobPlayers") > 0;
    MediaDetector detector = detectors.apply(tab);
    if ((!playback && !mse) || detector == null || !detector.snapshot().isEmpty()) return;
    String page = pages.get(tab);
    Runnable action =
        () -> {
          pending.remove(tab);
          MediaDetector current = detectors.apply(tab);
          if (current == null
              || !Objects.equals(pages.get(tab), page)
              || !current.snapshot().isEmpty()
              || !reported.add(tab)) return;
          boolean observed = playbackSeen.contains(tab);
          var diagnostic =
              new FailureDiagnostics.Session(
                  0,
                  mse ? "MSE" : "UNKNOWN",
                  "",
                  page,
                  Map.of("geckoPlaybackObserved", Boolean.toString(observed)));
          Map<String, Object> evidence = new LinkedHashMap<>();
          if (current.detectionDiagnostics != null)
            for (var keys = current.detectionDiagnostics.keys(); keys.hasNext(); ) {
              String key = keys.next();
              evidence.put(key, current.detectionDiagnostics.opt(key));
            }
          diagnostic.detection(evidence);
          diagnostic.phase(FailureDiagnostics.Stage.DETECTION);
          diagnostic.fail(
              new DownloadError(DownloadError.Category.MANIFEST_INVALID),
              DownloadError.Category.MANIFEST_INVALID,
              observed ? "PLAYBACK_WITHOUT_CANDIDATE" : "MSE_MANIFEST_NOT_FOUND");
          diagnostic.complete();
        };
    pending.put(tab, action);
    handler.postDelayed(action, delay);
  }

  public void discard(int tab) {
    candidate(tab);
    pages.remove(tab);
    reported.remove(tab);
    playbackSeen.remove(tab);
  }

  @Override
  public void close() {
    for (Runnable r : pending.values()) handler.removeCallbacks(r);
    pending.clear();
    pages.clear();
    reported.clear();
    playbackSeen.clear();
  }
}
