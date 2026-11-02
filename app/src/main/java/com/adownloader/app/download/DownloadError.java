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
    HLS_UNSUPPORTED,
    ACCESS_BLOCKED,
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

  public HlsDiagnostic diagnostic;

  public DownloadError diagnostic(HlsDiagnostic detail) {
    diagnostic = detail;
    return this;
  }

  public final Category category;

  public DownloadError(Category category) {
    super(category.name());
    this.category = category;
  }

  public static DownloadError storage(Throwable error) {
    for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
      if (error instanceof DownloadError
          && ((DownloadError) error).category == Category.STORAGE_FULL)
        return new DownloadError(Category.STORAGE_FULL);
      if (error instanceof android.system.ErrnoException
          && ((android.system.ErrnoException) error).errno == android.system.OsConstants.ENOSPC)
        return new DownloadError(Category.STORAGE_FULL);
    }
    return new DownloadError(Category.STORAGE);
  }

  public static DownloadError http(int code) {
    return new DownloadError(
        code == 401 || code == 403
            ? Category.AUTH
            : code >= 500 ? Category.SERVER : Category.NETWORK);
  }

  public static DownloadError http(
      int code, String url, boolean previouslyAccepted, boolean authenticationChallenge, long now) {
    if (code == 403
        && !authenticationChallenge
        && com.adownloader.app.media.CdnQueryPolicy.signed(url)
        && com.adownloader.app.media.CdnQueryPolicy.expiresAt(url) <= now)
      return new DownloadError(Category.EXPIRED_URL);
    if (code == 403 && !authenticationChallenge) return new DownloadError(Category.ACCESS_BLOCKED);
    return http(code);
  }
}
