package com.adownloader.app.download;

import java.io.IOException;

/** Typed errors contain categories, never remote URLs or server response text. */
public final class DownloadError extends IOException {
  public enum Category {
    NETWORK,
    AUTH,
    EXPIRED_URL,
    STORAGE,
    DRM,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_CODEC,
    LIVE_UNSUPPORTED,
    MANIFEST_INVALID,
    CANCELED,
    SERVER,
    UNKNOWN
  }

  public final Category category;

  public DownloadError(Category category) {
    super(category.name());
    this.category = category;
  }

  public static DownloadError http(int code) {
    return new DownloadError(
        code == 401 || code == 403
            ? Category.EXPIRED_URL
            : code >= 500 ? Category.SERVER : Category.NETWORK);
  }
}
