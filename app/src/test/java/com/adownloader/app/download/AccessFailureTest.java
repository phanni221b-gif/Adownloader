package com.adownloader.app.download;

import static org.junit.Assert.*;

import org.junit.Test;

public class AccessFailureTest {
  @Test
  public void unknownForbiddenAndUnauthorizedAreAuth() {
    assertEquals(DownloadError.Category.AUTH, DownloadError.http(401).category);
    assertEquals(DownloadError.Category.AUTH, DownloadError.http(403).category);
    assertEquals(
        DownloadError.Category.AUTH,
        error("https://cdn.test/movie.mp4?token=a", false, false).category);
  }

  @Test
  public void expiryRequiresSpecificSignedEvidence() {
    assertEquals(
        DownloadError.Category.AUTH,
        DownloadError.http(
                401, "https://cdn.test/movie.mp4?sig=a&exp=1600000000", true, false, 1800000000000L)
            .category);
    assertEquals(
        DownloadError.Category.EXPIRED_URL,
        error("https://cdn.test/movie.mp4?sig=a&exp=1600000000", false, false).category);
    assertEquals(
        DownloadError.Category.EXPIRED_URL,
        error("https://cdn.test/movie.mp4?token=a", true, false).category);
    assertEquals(
        DownloadError.Category.AUTH, error("https://cdn.test/movie.mp4", true, false).category);
    assertEquals(
        DownloadError.Category.AUTH,
        error("https://cdn.test/movie.mp4?token=a", true, true).category);
  }

  private DownloadError error(String url, boolean accepted, boolean challenge) {
    return DownloadError.http(403, url, accepted, challenge, 1800000000000L);
  }
}
