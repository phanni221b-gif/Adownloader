package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class SessionPolicyTest {
  @Test
  public void authAndPlaybackContextRemainOriginScoped() {
    var h =
        Map.of(
            "Authorization",
            "Bearer PRIVATE",
            "X-Playback-Token",
            "PRIVATE",
            "Cookie",
            "sid=PRIVATE",
            "Accept",
            "application/vnd.apple.mpegurl",
            "Accept-Language",
            "ko-KR",
            "Referer",
            "https://page.test/watch?id=PRIVATE",
            "Origin",
            "https://page.test");
    var c = new RequestContext("https://cdn.test/v/master.m3u8", h);
    assertEquals("PRIVATE", c.forUrl("https://cdn.test/v/part.ts").get("X-Playback-Token"));
    var foreign = c.forUrl("https://other.test/v/part.ts");
    assertFalse(foreign.containsKey("Cookie"));
    assertFalse(foreign.containsKey("Authorization"));
    assertFalse(foreign.containsKey("X-Playback-Token"));
    assertFalse(foreign.containsKey("Origin"));
    assertEquals("https://page.test/", foreign.get("Referer"));
    assertEquals("ko-KR", foreign.get("Accept-Language"));
    assertFalse(c.forUrl("http://other.test/v/part.ts").containsKey("Referer"));
  }

  @Test
  public void exactCredentialsNeverGeneralizeToSiblingVideos() {
    var h =
        Map.of(
            "Authorization",
            "PRIVATE",
            "X-Playback-Token",
            "PRIVATE",
            "Cookie",
            "sid=PRIVATE",
            "Referer",
            "https://player.test/frame");
    var c = new CapturedRequestContexts(Map.of("https://cdn.test/v/part.ts?sig=1", h), true);
    assertEquals("PRIVATE", c.forUrl("https://cdn.test/v/part.ts?sig=1").get("Authorization"));
    assertEquals(
        "PRIVATE", c.forUrl("https://CDN.test:443/v/part.ts?sig=1#fragment").get("Authorization"));
    assertFalse(c.forUrl("https://cdn.test/v/part.ts?sig=2").containsKey("Authorization"));
    assertFalse(c.forUrl("https://cdn.test/v/other.ts").containsKey("Cookie"));
    assertTrue(c.forUrl("https://other.test/v/part.ts?sig=1").isEmpty());
    assertFalse(
        new CapturedRequestContexts(Map.of("https://cdn.test/v/part.ts", h))
            .forUrl("https://cdn.test/v/part.ts")
            .containsKey("Authorization"));
  }

  @Test
  public void diagnosticNeverContainsQueryOrValuesAndComparesNames() {
    List<Map<String, Object>> events = new ArrayList<>();
    MediaNetworkSession.diagnostics(events::add);
    try {
      MediaNetworkSession.trace(
          "ROOT_MANIFEST",
          "https://cdn.test/private/secret?token=PRIVATE",
          "native",
          403,
          2,
          "text/html; token=PRIVATE",
          "HTML_CHALLENGE",
          List.of("Accept", "Cookie"),
          List.of("Accept", "Cookie", "X-Playback-Token"),
          true,
          "MANIFEST_NATIVE_BLOCKED");
      String raw = events.toString();
      assertFalse(raw.contains("PRIVATE"));
      assertFalse(raw.contains("/private"));
      assertTrue(raw.contains("missingHeaderNames=[x-playback-token]"));
    } finally {
      MediaNetworkSession.diagnostics(null);
    }
  }

  @Test
  public void recoveryLabelsDistinguishAccessFromExpiry() {
    assertEquals("주소 만료", AccessPresentation.label("EXPIRED_URL"));
    assertEquals("로그인 또는 세션 확인 필요", AccessPresentation.label("AUTH"));
    assertEquals("사이트 요청 차단", AccessPresentation.label("ACCESS_BLOCKED"));
    assertEquals("페이지 열기", AccessPresentation.action("ACCESS_BLOCKED"));
  }

  @Test
  public void browserContextCannotCrossTabsOrFrames() {
    MediaNetworkSession.Provider p =
        new MediaNetworkSession.Provider() {
          public MediaNetworkSession.Observation latest(String url, String page) {
            return new MediaNetworkSession.Observation(
                url,
                page,
                2,
                200,
                System.currentTimeMillis(),
                Map.of("Authorization", "PRIVATE"),
                List.of("Authorization"),
                7);
          }

          public MediaNetworkSession.Bootstrap manifest(
              String url, String page, Map<String, String> h, TransferControl c) {
            throw new AssertionError("No fetch expected");
          }
        };
    MediaNetworkSession.install(p);
    try {
      assertNotNull(MediaNetworkSession.latest("https://cdn.test/a", "https://page.test", 7, 2));
      assertNull(MediaNetworkSession.latest("https://cdn.test/a", "https://page.test", 8, 2));
      assertNull(MediaNetworkSession.latest("https://cdn.test/a", "https://page.test", 7, 3));
    } finally {
      MediaNetworkSession.remove(p);
    }
  }
}
