package com.adownloader.app.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Smoothed per-download speed and time-remaining estimate for the download list. Samples come from
 * the committed byte counts the list already reads; nothing extra is stored.
 */
public final class TransferRate {
  private static final class Sample {
    long bytes, at, progressAt;
    double rate; // bytes per second, exponentially smoothed
  }

  private final Map<Long, Sample> samples = new HashMap<>();

  /** Records a reading and returns the smoothed rate in bytes/s, or 0 while unknown or stale. */
  public double update(long id, long bytes, long nowMillis) {
    Sample sample = samples.get(id);
    if (sample == null || bytes < sample.bytes) {
      sample = new Sample();
      sample.bytes = bytes;
      sample.at = nowMillis;
      sample.progressAt = nowMillis;
      samples.put(id, sample);
      return 0;
    }
    long elapsed = nowMillis - sample.at;
    if (elapsed >= 1000) {
      double instant = (bytes - sample.bytes) * 1000.0 / elapsed;
      sample.rate = sample.rate <= 0 ? instant : sample.rate * 0.6 + instant * 0.4;
      if (bytes > sample.bytes) sample.progressAt = nowMillis;
      sample.bytes = bytes;
      sample.at = nowMillis;
    } else if (elapsed < 0) {
      sample.at = nowMillis;
      sample.progressAt = nowMillis;
    }
    // No new bytes for a while: show nothing rather than an old optimistic speed.
    return nowMillis - sample.progressAt > 15_000 ? 0 : sample.rate;
  }

  public void forget(long id) {
    samples.remove(id);
  }

  /**
   * Remaining bytes from the known total, or extrapolated from committed segments when a stream has
   * no total size. Returns -1 when no honest estimate exists.
   */
  public static long remaining(long bytes, long total, int segment, int segments) {
    if (total > 0 && bytes <= total) return total - bytes;
    if (segments > 0 && segment > 0 && segment <= segments && bytes > 0)
      return Math.round(bytes * (double) (segments - segment) / segment);
    return -1;
  }

  /** Korean time-remaining label, or empty when there is no estimate. */
  public static String eta(long remainingBytes, double bytesPerSecond) {
    if (remainingBytes < 0 || bytesPerSecond <= 0) return "";
    long seconds = Math.round(remainingBytes / bytesPerSecond);
    if (seconds < 60) return "1분 이내 남음";
    long minutes = (seconds + 59) / 60;
    if (minutes < 60) return "약 " + minutes + "분 남음";
    long hours = minutes / 60, rest = minutes % 60;
    return "약 " + hours + "시간" + (rest > 0 ? " " + rest + "분" : "") + " 남음";
  }
}
