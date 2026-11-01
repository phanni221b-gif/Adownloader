package com.adownloader.app.download;

import java.io.IOException;

/** Typed errors contain categories, never remote URLs or server response text. */
public final class DownloadError extends IOException {
  public enum Category {
    NETWORK,
    AUTH,
    EXPIRED_URL,
    STORAGE,
    STORAGE_FULL,
    DRM,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_CODEC,
    LIVE_UNSUPPORTED,
    HLS_GAP,
    HLS_DISCONTINUITY,
    DASH_MULTIPERIOD,
    MANIFEST_INVALID,
    CANCELED,
    SERVER,
    UNKNOWN
  }

  public static final class ManifestFailure extends IllegalArgumentException {
    public final Category category;

    ManifestFailure(Category category, String message) {
      super(message);
      this.category = category;
    }
  }

  public final Category category;

  public DownloadError(Category category) {
    super(category.name());
    this.category = category;
  }

  public static DownloadError http(int code) {
    return new DownloadError(
        code == 401 || code == 403
            ? Category.AUTH
            : code >= 500 ? Category.SERVER : Category.NETWORK);
  }

  public static DownloadError http(
      int code, String url, boolean previouslyAccepted, boolean authenticationChallenge, long now) {
    if ((code == 401 || code == 403)
        && !authenticationChallenge
        && com.adownloader.app.media.CdnQueryPolicy.signed(url)
        && (previouslyAccepted || com.adownloader.app.media.CdnQueryPolicy.expiresAt(url) <= now))
      return new DownloadError(Category.EXPIRED_URL);
    return http(code);
  }
}
