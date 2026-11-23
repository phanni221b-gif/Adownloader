package com.adownloader.app.download;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transient-failure retry timing for segment/key/manifest body transfers.
 *
 * <p>Only NETWORK (no response, timeout, 408/429) and SERVER (5xx) failures are retried; 401/403,
 * an HTML page instead of media and every other category are properties of the request and fail
 * at once with their own classification. A server's Retry-After is honoured up to a cap, otherwise
 * the wait grows exponentially with jitter so parallel segment workers do not retry in lockstep.
 */
public final class RetryPolicy {
  private RetryPolicy() {}

  public static final int ATTEMPTS = 4;
  static final long MAX_SERVER_WAIT_MS = 30_000;
  private static final long BASE_MS = 400;
  private static final long MAX_BACKOFF_MS = 8_000;

  public static boolean retryable(Exception error) {
    if (error instanceof TransferControl.Stopped) return false;
    if (error instanceof DownloadError d) {
      // A definitive client error (404, 410, 416 ...) is a property of the URL, not a transient.
      if (d.diagnostic != null
          && d.diagnostic.status >= 400
          && d.diagnostic.status < 500
          && d.diagnostic.status != 408
          && d.diagnostic.status != 429) return false;
      return d.category == DownloadError.Category.NETWORK
          || d.category == DownloadError.Category.SERVER;
    }
    return error instanceof java.io.IOException;
  }

  /** Wait before attempt {@code nextAttempt} (1-based count of failures so far). */
  public static long delayMs(Exception error, int failures) {
    long backoff =
        Math.min(MAX_BACKOFF_MS, BASE_MS << Math.max(0, Math.min(5, failures - 1)))
            + ThreadLocalRandom.current().nextLong(250);
    long server = error instanceof DownloadError d ? d.retryAfterMs : 0;
    return Math.max(backoff, server);
  }

  /** Retry-After as delta-seconds or HTTP-date; 0 when absent or invalid. */
  public static long parseRetryAfter(String value, long now) {
    if (value == null || value.isBlank() || value.length() > 64) return 0;
    String v = value.trim();
    try {
      if (v.matches("\\d{1,6}")) return Math.min(MAX_SERVER_WAIT_MS, Long.parseLong(v) * 1000);
      SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
      date.setTimeZone(TimeZone.getTimeZone("GMT"));
      return Math.max(0, Math.min(MAX_SERVER_WAIT_MS, date.parse(v).getTime() - now));
    } catch (Exception invalid) {
      return 0;
    }
  }
}
