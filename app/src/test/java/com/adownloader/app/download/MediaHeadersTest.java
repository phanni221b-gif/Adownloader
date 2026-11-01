package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class MediaHeadersTest {
  @Test
  public void onlyMediaAuthenticationAndNecessaryNegotiationAreSelectable() {
    var h =
        MediaHeaders.select(
            Map.of(
                "Authorization",
                "Bearer SYNTHETIC_SECRET",
                "X-Playback-Token",
                "SYNTHETIC_TOKEN",
                "Accept-Language",
                "ko-KR",
                "Sec-Fetch-Site",
                "same-site",
                "Accept-Encoding",
                "gzip",
                "Range",
                "bytes=1-2",
                "X-Analytics-Id",
                "TRACKER"));
    assertEquals(3, h.size());
    assertTrue(h.containsKey("Authorization"));
    assertFalse(h.containsKey("X-Analytics-Id"));
  }

  @Test
  public void controlsAndForeignOriginsCannotMasqueradeAsScope() {
    assertTrue(
        MediaHeaders.select(Map.of("Authorization", "a\r\nb", "X-Playback-Token", "a\u0000b"))
            .isEmpty());
    assertEquals("https://cdn.test:443", MediaHeaders.origin("https://CDN.test/a?q=private"));
    assertEquals("", MediaHeaders.origin("https://user:pass@cdn.test/a"));
  }
}
