package com.adownloader.app.media;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import java.util.*;
import org.junit.Test;

public class MediaDetectorPriorityTest {
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
}
