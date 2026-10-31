package com.adownloader.app.media;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import java.util.*;
import org.junit.Test;

public class MediaIdentityTest {
  @Test
  public void signedTokenRotationKeepsOneLatestLogicalCandidate() {
    MediaDetector detector = new MediaDetector(null);
    for (int i = 0; i < 100; i++)
      detector.capture(
          "https://cdn.example.test/files/movie-123.mp4?quality=2160&token=" + i,
          "video/mp4",
          "network-response",
          "https://example.test/watch",
          Map.of("Cookie", "session=" + i));
    assertEquals(1, detector.snapshot().size());
    MediaCandidate newest = detector.snapshot().get(0);
    assertTrue(newest.getUrl().endsWith("token=99"));
    assertEquals("session=99", newest.getHeaders().get("Cookie"));
  }

  @Test
  public void fileIdAndQualityAreNotThrownAway() {
    MediaDetector d = new MediaDetector(null);
    for (String url :
        List.of(
            "https://cdn.example.test/a.mp4?token=1&quality=1080",
            "https://cdn.example.test/b.mp4?token=1&quality=1080",
            "https://cdn.example.test/a.mp4?token=2&quality=2160",
            "https://cdn.example.test/api/play?id=1&token=a",
            "https://cdn.example.test/api/play?id=2&token=b"))
      d.capture(url, "video/mp4", "network-response", "https://example.test/watch", Map.of());
    assertEquals(5, d.snapshot().size());
  }

  @Test
  public void ambiguousApiTokensRemainSeparate() {
    MediaDetector d = new MediaDetector(null);
    d.capture(
        "https://cdn.example.test/play?token=one",
        "video/mp4",
        "network",
        "https://example.test",
        Map.of());
    d.capture(
        "https://cdn.example.test/play?token=two",
        "video/mp4",
        "network",
        "https://example.test",
        Map.of());
    assertEquals(2, d.snapshot().size());
  }

  @Test
  public void domRepeatDoesNotEraseAuthenticatedResponse() {
    MediaDetector d = new MediaDetector(null);
    String url = "https://cdn.example.test/movie.mp4";
    d.capture(
        url,
        "video/mp4",
        "network",
        "https://example.test",
        Map.of("Cookie", "request-only", "Authorization", "never"));
    d.capture(url, "", "dom", "https://example.test", Map.of());
    assertEquals(1, d.snapshot().size());
    assertEquals("request-only", d.snapshot().get(0).getHeaders().get("Cookie"));
    assertFalse(d.snapshot().get(0).getHeaders().containsKey("Authorization"));
  }

  @Test
  public void listStaysBoundedAndQueryTextDoesNotImpersonateMedia() {
    MediaDetector d = new MediaDetector(null);
    d.capture(
        "https://example.test/page?next=movie.mp4",
        "text/html",
        "network",
        "https://example.test",
        Map.of());
    assertTrue(d.snapshot().isEmpty());
    for (int i = 0; i < 250; i++)
      d.capture(
          "https://cdn.example.test/file-" + i + ".mp4",
          "video/mp4",
          "network",
          "https://example.test",
          Map.of());
    assertEquals(200, d.snapshot().size());
  }
}
