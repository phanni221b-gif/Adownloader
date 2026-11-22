package com.adownloader.app.download;

import org.mozilla.geckoview.WebRequestError;

/**
 * Classifies a connection-level failure (no HTTP response at all) into a small, privacy-safe kind,
 * so a report can say whether the request failed at address lookup, was refused, was reset, timed
 * out or failed TLS — instead of a bare "network error". Never carries host, URL or message text.
 */
public final class NetworkFailure {
  private NetworkFailure() {}

  public static final java.util.Set<String> KINDS =
      java.util.Set.of("dns", "timeout", "refused", "reset", "tls", "offline", "other");

  /**
   * A Gecko network failure that keeps its kind. Still a SocketException, so every existing catch
   * and retry path treats it exactly as before.
   */
  public static final class Typed extends java.net.SocketException {
    public final String kind;

    public Typed(String message, String kind) {
      super(message);
      this.kind = KINDS.contains(kind) ? kind : "other";
    }
  }

  /** GeckoView WebRequestError code to kind (constants are inlined at compile time). */
  public static String geckoKind(int code) {
    return switch (code) {
      case WebRequestError.ERROR_UNKNOWN_HOST -> "dns";
      case WebRequestError.ERROR_NET_TIMEOUT -> "timeout";
      case WebRequestError.ERROR_CONNECTION_REFUSED -> "refused";
      case WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "reset";
      case WebRequestError.ERROR_SECURITY_SSL,
          WebRequestError.ERROR_SECURITY_BAD_CERT,
          WebRequestError.ERROR_BAD_HSTS_CERT -> "tls";
      case WebRequestError.ERROR_OFFLINE -> "offline";
      default -> "other";
    };
  }

  public static String kind(Throwable error) {
    Throwable t = error;
    for (int depth = 0; t != null && depth < 6; depth++, t = t.getCause()) {
      if (t instanceof Typed typed) return typed.kind;
      if (t instanceof java.net.UnknownHostException) return "dns";
      if (t instanceof java.net.SocketTimeoutException) return "timeout";
      if (t instanceof java.net.ConnectException) return "refused";
      if (t instanceof javax.net.ssl.SSLException) return "tls";
      if (t instanceof java.net.SocketException) {
        String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("reset") || m.contains("broken pipe") || m.contains("econnreset")) return "reset";
      }
    }
    return "other";
  }
}
