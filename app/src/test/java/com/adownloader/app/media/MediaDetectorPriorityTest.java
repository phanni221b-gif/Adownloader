package com.adownloader.app.media;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import java.util.*;
import org.junit.Test;

public class MediaDetectorPriorityTest {
  @Test
  public void encryptedEnvelopeIsRecognizedWithoutPretendingItIsDownloadableHls() {
    var d = new MediaDetector(null);
    String url = "https://cdn.test/media/opaque", page = "https://page.test/watch";
    d.capture(url, "application/octet-stream", "dom-media", page, Map.of());
    assertTrue(d.snapshot().isEmpty());
    d.capture(
        url,
        "application/x-adownloader-protected",
        "protected-response",
        page,
        Map.of(),
        Map.of("transportProtection", "encrypted-relay", "frameId", "4294967297"));
    assertEquals(MediaCandidate.MediaType.PROTECTED, d.snapshot().get(0).getType());
    d.capture(url, "video/mp4", "dom-media", page, Map.of());
    assertEquals(1, d.snapshot().size());
    assertEquals(MediaCandidate.MediaType.PROTECTED, d.snapshot().get(0).getType());
    d.capture(url, "application/vnd.apple.mpegurl", "response-signature", page, Map.of());
    assertEquals(1, d.snapshot().size());
    assertEquals(MediaCandidate.MediaType.HLS, d.snapshot().get(0).getType());
  }

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
