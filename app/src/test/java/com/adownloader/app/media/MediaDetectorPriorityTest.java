package com.adownloader.app.media;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import java.util.*;
import org.junit.Test;

public class MediaDetectorPriorityTest {
  @org.junit.Test
  public void latePageHintCannotReplaceSuccessfulResponseBinding() {
    MediaDetector detector = new MediaDetector(null);
    String url = "https://page.test/start.m3u8", page = "https://page.test/watch";
    detector.capture(
        url,
        "application/vnd.apple.mpegurl",
        "redirect-observation",
        page,
        java.util.Map.of(),
        java.util.Map.of("browserTabId", "3", "frameId", "7", "mediaObservedAt", "123"));
    detector.capture(
        url,
        "application/vnd.apple.mpegurl",
        "performance-resource",
        page,
        java.util.Map.of(),
        java.util.Map.of("browserTabId", "3", "frameId", "0", "title", "Page title"));
    var result = detector.snapshot().get(0);
    org.junit.Assert.assertEquals("7", result.getMetadata().get("frameId"));
    org.junit.Assert.assertEquals("123", result.getMetadata().get("mediaObservedAt"));
    org.junit.Assert.assertEquals("Page title", result.getMetadata().get("title"));
  }

  @Test
  public void confirmedBodySignatureOverridesMisleadingFileExtensionAndDomHint() {
    var detector = new MediaDetector(null);
    String url = "https://cdn.test/pretend.mp4?sig=synthetic";
    detector.capture(
        url,
        "application/vnd.apple.mpegurl",
        "response-signature",
        "https://page.test/post",
        Map.of("Cookie", "fixture=private"));
    detector.capture(url, "", "dom-video", "https://page.test/post", Map.of());
    assertEquals(1, detector.snapshot().size());
    assertEquals(MediaCandidate.MediaType.HLS, detector.snapshot().get(0).getType());
    assertEquals("fixture=private", detector.snapshot().get(0).getHeaders().get("Cookie"));
  }

  @Test
  public void aNewVerifiedResponseReplacesCredentialsThatWereRemovedFromTheRequest() {
    var d = new MediaDetector(null);
    String url = "https://cdn.test/m/master.m3u8", page = "https://page.test/post";
    d.capture(
        url,
        "application/vnd.apple.mpegurl",
        "network-response",
        page,
        Map.of("Authorization", "Bearer OLD", "Cookie", "sid=OLD"),
        Map.of("requestContextVerified", "true"));
    d.capture(
        url,
        "application/vnd.apple.mpegurl",
        "network-response",
        page,
        Map.of("Referer", page),
        Map.of("requestContextVerified", "true"));
    var h = d.snapshot().get(0).getHeaders();
    assertFalse(h.containsKey("Authorization"));
    assertFalse(h.containsKey("Cookie"));
    assertEquals(page, h.get("Referer"));
  }
}
