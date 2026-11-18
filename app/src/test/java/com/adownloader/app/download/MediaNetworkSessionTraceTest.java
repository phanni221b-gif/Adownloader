package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.*;

/**
 * trace() clamps transport/action/requestType with its own allowlist before the event reaches the
 * report. Those lists must know the current vocabulary, or a browser-stack attempt is relabelled as
 * a plain native one and the page-context (content-script) transport is hidden as "native" — which
 * is what made the on-device report show gecko fallbacks as NATIVE_INITIAL / requestType UNKNOWN.
 */
public class MediaNetworkSessionTraceTest {
  private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

  @Before
  public void start() {
    MediaNetworkSession.diagnostics(events::add);
  }

  @After
  public void stop() {
    MediaNetworkSession.diagnostics(null);
  }

  @Test
  public void browserStackTerminologySurvivesInsteadOfBeingRelabelledNative() {
    MediaNetworkSession.trace(
        "VIDEO_SEGMENT",
        "https://cdn.test/seg1.ts",
        "gecko",
        403,
        0,
        "text/html",
        "HTML_CHALLENGE",
        List.of("referer"),
        List.of(),
        false,
        "BROWSER_INITIAL",
        "browser-fetch");
    Map<String, Object> e = events.get(0);
    assertEquals("gecko", e.get("transport"));
    assertEquals("the browser-stack action is not relabelled NATIVE_INITIAL", "BROWSER_INITIAL", e.get("action"));
    assertEquals("the browser-stack request type is not blanked to UNKNOWN", "browser-fetch", e.get("requestType"));
  }

  @Test
  public void contentScriptTransportIsNotDowngradedToNative() {
    MediaNetworkSession.trace(
        "VIDEO_SEGMENT",
        "https://cdn.test/seg1.ts",
        "content-script",
        200,
        0,
        "video/mp2t",
        "MEDIA",
        List.of("referer"),
        List.of(),
        true,
        "SEGMENT_CONTENT_SCRIPT",
        "content-script-fetch");
    Map<String, Object> e = events.get(0);
    assertEquals("content-script", e.get("transport"));
    assertEquals("SEGMENT_CONTENT_SCRIPT", e.get("action"));
    assertEquals("content-script-fetch", e.get("requestType"));
  }

  @Test
  public void legacyRequestTypeDefaultForGeckoIsBrowserFetchNotContentFetch() {
    MediaNetworkSession.trace(
        "VIDEO_SEGMENT",
        "https://cdn.test/seg1.ts",
        "gecko",
        403,
        0,
        "text/html",
        "HTML_CHALLENGE",
        List.of(),
        List.of(),
        false,
        "BROWSER_INITIAL");
    assertEquals("browser-fetch", events.get(0).get("requestType"));
  }
}
