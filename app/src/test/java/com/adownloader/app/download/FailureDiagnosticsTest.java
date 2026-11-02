package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

public class FailureDiagnosticsTest {
  @After
  public void reset() {
    FailureDiagnostics.install(null);
  }

  FailureDiagnostics.Session session() {
    return new FailureDiagnostics.Session(
        318,
        "HLS",
        "https://cdn.test/video.m3u8?token=VERY_SECRET",
        "https://page.test/post?login=PRIVATE",
        Map.of("title", "PRIVATE TITLE", "detectionSource", "response-signature"));
  }

  @Test
  public void rootFailureHasStageStatusOrderedHistoryAndNoRequestValues() {
    var s = session();
    s.event(
        Map.of(
            "stage",
            "ROOT_MANIFEST",
            "action",
            "GECKO_CACHE_MISS",
            "transport",
            "gecko-response-cache",
            "status",
            0));
    var event = new LinkedHashMap<String, Object>();
    event.putAll(
        Map.of(
            "stage",
            "ROOT_MANIFEST",
            "action",
            "NATIVE_INITIAL",
            "transport",
            "native",
            "status",
            403,
            "headerNames",
            List.of("cookie", "authorization", "x-playback-token"),
            "geckoHeaderNames",
            List.of("cookie", "authorization", "x-playback-token")));
    event.put("Cookie", "SUPER_SECRET_COOKIE_123");
    event.put("Authorization", "Bearer VERY_SECRET_TOKEN");
    event.put("url", "https://cdn.test/a?SIGNED_QUERY_SECRET");
    s.event(event);
    var r =
        s.report(
            new java.io.IOException("https://user@example.com/private?token=VERY_SECRET"),
            DownloadError.Category.ACCESS_BLOCKED,
            "DOWNLOAD_FAILURE");
    Map<?, ?> failure = (Map<?, ?>) r.get("failure");
    assertEquals("ROOT_MANIFEST", failure.get("stage"));
    assertEquals("ACCESS_BLOCKED", failure.get("category"));
    assertEquals(403L, failure.get("httpStatus"));
    assertTrue(r.get("failureId").toString().startsWith("F-"));
    String text = r.toString();
    for (String secret :
        List.of(
            "VERY_SECRET",
            "SUPER_SECRET_COOKIE_123",
            "SIGNED_QUERY_SECRET",
            "PRIVATE TITLE",
            "user@example.com",
            "/private",
            "?token")) assertFalse(secret, text.contains(secret));
    assertTrue(text.contains("x-playback-token"));
    List<?> history = (List<?>) r.get("eventHistory");
    assertEquals(3, history.size());
    assertEquals("GECKO_CACHE_MISS", ((Map<?, ?>) history.get(1)).get("action"));
    assertEquals("NATIVE_INITIAL", ((Map<?, ?>) history.get(2)).get("action"));
  }

  @Test
  public void fingerprintGroupsFailuresWithoutHostsButSeparatesStages() {
    var a = session();
    a.phase(FailureDiagnostics.Stage.ROOT_MANIFEST);
    var first = a.report(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE");
    var b =
        new FailureDiagnostics.Session(
            999,
            "HLS",
            "https://different.test/root?OTHER_SECRET",
            "https://other.test/",
            Map.of());
    b.phase(FailureDiagnostics.Stage.ROOT_MANIFEST);
    assertEquals(
        FailureDiagnostics.fingerprint(first),
        FailureDiagnostics.fingerprint(
            b.report(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE")));
    b.phase(FailureDiagnostics.Stage.VIDEO_SEGMENT);
    assertNotEquals(
        FailureDiagnostics.fingerprint(first),
        FailureDiagnostics.fingerprint(
            b.report(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE")));
  }

  @Test
  public void eventsAreBoundedAndCompletionDoesNotCreateReports() {
    AtomicInteger reports = new AtomicInteger();
    FailureDiagnostics.install(r -> reports.incrementAndGet());
    var s = session();
    for (int i = 0; i < 120; i++)
      s.event(Map.of("action", "PHASE", "stage", "VIDEO_SEGMENT", "segmentIndex", i));
    List<?> events =
        (List<?>)
            s.report(null, DownloadError.Category.NETWORK, "DOWNLOAD_FAILURE").get("eventHistory");
    assertEquals(50, events.size());
    long last = 0;
    for (Object o : events) {
      long n = ((Number) ((Map<?, ?>) o).get("sequence")).longValue();
      assertTrue(n > last);
      last = n;
    }
    s.complete();
    assertEquals(0, reports.get());
  }

  @Test
  public void exportPassRejectsPoisonedFieldsAndCredentialShapedValues() {
    var dirty = new LinkedHashMap<String, Object>();
    dirty.put("url", "https://host.test/a?SIGNED_QUERY_SECRET");
    dirty.put("Cookie", "SUPER_SECRET_COOKIE_123");
    dirty.put("exceptionMessage", "Bearer VERY_SECRET_TOKEN");
    dirty.put("device", Map.of("model", "SUPER_SECRET_COOKIE_123", "IMEI", "PRIVATE"));
    dirty.put(
        "media", Map.of("pageHost", "https://host.test/a?secret=x", "title", "PRIVATE TITLE"));
    dirty.put("headerNames", List.of("cookie", "authorization", "x-playback-token"));
    String clean = FailureDiagnostics.sanitize(dirty).toString();
    for (String secret :
        List.of(
            "SUPER_SECRET",
            "VERY_SECRET",
            "SIGNED_QUERY_SECRET",
            "PRIVATE TITLE",
            "IMEI",
            "?secret")) assertFalse(clean.contains(secret));
    assertTrue(clean.contains("x-playback-token"));
  }

  @Test
  public void laterMuxFailureDoesNotInheritAnEarlierRecoveredHttp403() {
    var s = session();
    s.event(
        Map.of(
            "stage",
            "VIDEO_SEGMENT",
            "action",
            "NATIVE_INITIAL",
            "transport",
            "native",
            "status",
            403));
    s.event(
        Map.of(
            "stage",
            "VIDEO_SEGMENT",
            "action",
            "NATIVE_CONTEXT_RETRY",
            "transport",
            "native",
            "status",
            200));
    s.phase(FailureDiagnostics.Stage.MUX);
    var failure =
        (Map<?, ?>)
            s.report(
                    new IllegalStateException("SECRET"),
                    DownloadError.Category.UNSUPPORTED_FORMAT,
                    "DOWNLOAD_FAILURE")
                .get("failure");
    assertEquals("MUX", failure.get("stage"));
    assertEquals(0L, failure.get("httpStatus"));
  }

  @Test
  public void scopesRestoreTheirOwnerAndExplicitWorkerEventsRemainIsolated() throws Exception {
    var a = session();
    var b = session();
    try (var one = FailureDiagnostics.activate(a)) {
      assertSame(a, FailureDiagnostics.current());
      try (var two = FailureDiagnostics.activate(b)) {
        assertSame(b, FailureDiagnostics.current());
      }
      assertSame(a, FailureDiagnostics.current());
    }
    assertNull(FailureDiagnostics.current());
    Thread worker =
        new Thread(
            () ->
                FailureDiagnostics.event(
                    a,
                    Map.of("stage", "VIDEO_SEGMENT", "action", "NATIVE_INITIAL", "status", 403)));
    worker.start();
    worker.join();
    assertEquals(
        "VIDEO_SEGMENT",
        ((Map<?, ?>)
                a.report(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE")
                    .get("failure"))
            .get("stage"));
    assertEquals(
        "UNKNOWN",
        ((Map<?, ?>)
                b.report(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE")
                    .get("failure"))
            .get("stage"));
  }
}
