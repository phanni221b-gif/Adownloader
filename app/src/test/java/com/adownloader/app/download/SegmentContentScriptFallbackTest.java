package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/**
 * Route #1 last resort: a segment host that blocks every network transport (native and the browser
 * stack) can still be served through the live playing tab's content-script context — the same
 * page-context bridge the manifest bootstrap uses — but only for segments the tab itself fetched,
 * and only for whole-segment requests. This is not a protection bypass: it replays the page's own
 * fetch for a resource the tab already loaded.
 */
public class SegmentContentScriptFallbackTest {
  private ServerSocket server;
  private Thread thread;
  private final AtomicInteger networkHits = new AtomicInteger();
  private final AtomicInteger bridgeCalls = new AtomicInteger();
  private MediaNetworkSession.Provider provider;
  private volatile boolean bridgeServes = true;
  private volatile boolean observeSegments = true;
  private static final byte[] SEGMENT = "MEDIA-SEGMENT-BYTES".getBytes(StandardCharsets.UTF_8);

  private String page;

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    page = "http://127.0.0.1:" + server.getLocalPort() + "/watch";
    thread =
        new Thread(
            () -> {
              while (!server.isClosed())
                try (Socket socket = server.accept()) {
                  BufferedReader in =
                      new BufferedReader(new InputStreamReader(socket.getInputStream()));
                  in.readLine();
                  String line;
                  while ((line = in.readLine()) != null && !line.isEmpty()) {}
                  // Anti-bot segment CDN: every network request (direct or browser stack) is
                  // refused with a 403 challenge. Only the page-context bridge can reach it.
                  networkHits.incrementAndGet();
                  byte[] out = "<html>access denied challenge</html>".getBytes(StandardCharsets.UTF_8);
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html\r\nContent-Length: "
                                  + out.length
                                  + "\r\nConnection: close\r\n\r\n")
                              .getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().write(out);
                } catch (Exception stopped) {
                  if (!server.isClosed()) throw new RuntimeException(stopped);
                }
            });
    thread.start();
    HttpConnections.installBrowser(
        url -> {
          HttpURLConnection c = (HttpURLConnection) url.openConnection();
          c.setRequestProperty("X-Cleared", "1");
          return c;
        });
    provider =
        new MediaNetworkSession.Provider() {
          public MediaNetworkSession.Observation latest(String url, String top) {
            return observeSegments && url.contains("/cdn/") && top.equals(page)
                ? new MediaNetworkSession.Observation(
                    url,
                    page,
                    3,
                    200,
                    System.currentTimeMillis(),
                    Map.of(),
                    List.of("referer"),
                    7)
                : null;
          }

          public MediaNetworkSession.Bootstrap manifest(
              String url, String top, Map<String, String> headers, TransferControl control) {
            bridgeCalls.incrementAndGet();
            if (!bridgeServes)
              return new MediaNetworkSession.Bootstrap(
                  url, 403, 0, Map.of(), new byte[0], Map.of(), Map.of());
            return new MediaNetworkSession.Bootstrap(
                url,
                200,
                0,
                Map.of("content-type", "video/mp2t"),
                SEGMENT,
                Map.of(),
                Map.of());
          }
        };
    MediaNetworkSession.install(provider);
  }

  @After
  public void stop() throws Exception {
    MediaNetworkSession.remove(provider);
    HttpConnections.installBrowser(null);
    server.close();
    thread.join(2000);
  }

  private String seg(int n) {
    return "http://127.0.0.1:" + server.getLocalPort() + "/cdn/seg" + n + ".ts";
  }

  private HttpSource source() {
    HttpSource source = new HttpSource(seg(1), Collections.emptyMap(), new TransferControl());
    source.pageContext(page).browserScope(7, 3);
    var track = new ManifestParser.Track();
    track.parts.add(new ManifestParser.Part(seg(1)));
    track.parts.add(new ManifestParser.Part(seg(2)));
    source.registerTracks(List.of(track));
    return source;
  }

  @Test
  public void aSegmentBlockedOnEveryNetworkTransportIsServedThroughTheContentScriptBridge()
      throws Exception {
    try (HttpSource source = source()) {
      byte[] data = source.bytes(seg(1), 0, 0, 1 << 20);
      assertArrayEquals("the bridge served the real segment bytes", SEGMENT, data);
      assertTrue("native and the browser stack were both tried first", networkHits.get() >= 2);
      assertEquals("the page-context bridge fetched the segment", 1, bridgeCalls.get());
    }
  }

  @Test
  public void aHostProvenOnTheBridgeSkipsTheDeadNetworkAttemptsForLaterSegments() throws Exception {
    try (HttpSource source = source()) {
      assertArrayEquals(SEGMENT, source.bytes(seg(1), 0, 0, 1 << 20));
      int afterFirst = networkHits.get();
      // The second segment on the same host must go straight to the bridge, with no further blocked
      // network attempt.
      assertArrayEquals(SEGMENT, source.bytes(seg(2), 0, 0, 1 << 20));
      assertEquals("no further network attempts for a bridge-served host", afterFirst, networkHits.get());
      assertEquals(2, bridgeCalls.get());
    }
  }

  @Test
  public void whenTheBridgeAlsoFailsTheHostIsMarkedBlockedAndRemainingSegmentsFailFast()
      throws Exception {
    bridgeServes = false;
    try (HttpSource source = source()) {
      DownloadError first = assertThrows(DownloadError.class, () -> source.bytes(seg(1), 0, 0, 1 << 20));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, first.category);
      assertEquals("the bridge was tried once for the first segment", 1, bridgeCalls.get());
      int afterFirst = networkHits.get();
      // Every transport including the bridge was blocked: the host is marked blocked and the next
      // segment fails fast without another network round-trip or another bridge call.
      DownloadError second =
          assertThrows(DownloadError.class, () -> source.bytes(seg(2), 0, 0, 1 << 20));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, second.category);
      assertEquals("remaining segments do not re-hit the network", afterFirst, networkHits.get());
      assertEquals("remaining segments do not re-try the bridge", 1, bridgeCalls.get());
    }
  }

  @Test
  public void aByteRangeSegmentIsNotEligibleForTheBridge() throws Exception {
    try (HttpSource source = source()) {
      // A byte-range request cannot be served from the bridge's in-memory body, so the bridge must
      // not be attempted; the request fails as a normal access block.
      DownloadError blocked =
          assertThrows(DownloadError.class, () -> source.bytes(seg(1), 0, 64, 1 << 20));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, blocked.category);
      assertEquals("the bridge is never called for a ranged segment", 0, bridgeCalls.get());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void theBridgeSuccessIsRecordedAsContentScriptNotDowngradedToNative() throws Exception {
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(0, "HLS", seg(1), page, Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source = source()) {
      assertArrayEquals(SEGMENT, source.bytes(seg(1), 0, 0, 1 << 20));
    }
    Map<String, Object> report =
        session.report(
            new DownloadError(DownloadError.Category.NETWORK),
            DownloadError.Category.NETWORK,
            "TEST");
    List<Map<String, Object>> history = (List<Map<String, Object>>) report.get("eventHistory");
    assertTrue(
        "the bridge fetch is recorded as transport=content-script, not hidden as native",
        history.stream()
            .anyMatch(
                e ->
                    "content-script".equals(e.get("transport"))
                        && "SEGMENT_CONTENT_SCRIPT".equals(e.get("action"))));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void whenTheSegmentWasNeverObservedTheReportRecordsItAsUnobserved() throws Exception {
    observeSegments = false;
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(0, "HLS", seg(1), page, Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source = source()) {
      DownloadError blocked =
          assertThrows(DownloadError.class, () -> source.bytes(seg(1), 0, 0, 1 << 20));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, blocked.category);
    }
    assertEquals("the bridge is never fetched when nothing was observed", 0, bridgeCalls.get());
    Map<String, Object> report =
        session.report(
            new DownloadError(DownloadError.Category.ACCESS_BLOCKED),
            DownloadError.Category.ACCESS_BLOCKED,
            "TEST");
    List<Map<String, Object>> history = (List<Map<String, Object>>) report.get("eventHistory");
    assertTrue(
        "the report shows the player's own fetch of the segment host was never captured",
        history.stream()
            .anyMatch(e -> "SEGMENT_CONTENT_SCRIPT_UNOBSERVED".equals(e.get("action"))));
  }
}
