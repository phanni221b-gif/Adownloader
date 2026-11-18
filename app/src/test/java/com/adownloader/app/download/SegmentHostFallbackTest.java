package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/**
 * Reproduction of on-device failure F-4fc03bc7: after the fix in v0.8.0 the manifest host is
 * reachable, but the segments live on a different host (an anti-bot CDN) that the direct stack
 * cannot pass. Once any request pins the transfer to the direct stack (preferDirect), the segment
 * host is never retried through the browser stack — which carries the cleared browsing session —
 * so every segment is blocked with a 403 challenge. A blocked direct request must be retried
 * through the browser stack, and the host remembered so its remaining segments skip the dead
 * direct attempt.
 */
public class SegmentHostFallbackTest {
  private ServerSocket server;
  private Thread thread;
  private final AtomicInteger directHits = new AtomicInteger(), browserHits = new AtomicInteger();
  private volatile boolean alwaysBlock;
  private static final String BODY = "#EXTM3U\n#EXT-X-ENDLIST\n";

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    thread =
        new Thread(
            () -> {
              while (!server.isClosed())
                try (Socket socket = server.accept()) {
                  BufferedReader in =
                      new BufferedReader(new InputStreamReader(socket.getInputStream()));
                  in.readLine();
                  boolean cleared = false;
                  String line;
                  while ((line = in.readLine()) != null && !line.isEmpty())
                    if (line.toLowerCase(Locale.ROOT).startsWith("x-cleared:")) cleared = true;
                  // Anti-bot CDN: only a request carrying the cleared browsing session (modelled by
                  // the browser opener's header) is served; a plain direct request gets a 403.
                  byte[] out;
                  String status, type;
                  if (cleared) browserHits.incrementAndGet();
                  else directHits.incrementAndGet();
                  if (cleared && !alwaysBlock) {
                    out = BODY.getBytes(StandardCharsets.UTF_8);
                    status = "200 OK";
                    type = "application/vnd.apple.mpegurl";
                  } else {
                    out = "<html>captcha challenge</html>".getBytes(StandardCharsets.UTF_8);
                    status = "403 Forbidden";
                    type = "text/html";
                  }
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 "
                                  + status
                                  + "\r\nContent-Type: "
                                  + type
                                  + "\r\nContent-Length: "
                                  + out.length
                                  + "\r\nConnection: close\r\n\r\n")
                              .getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().write(out);
                } catch (Exception stopped) {
                  if (!server.isClosed()) throw new RuntimeException(stopped);
                }
            });
    thread.start();
    // The browser stack is modelled by an opener that presents the cleared-session marker the real
    // GeckoWebExecutor carries from the browsing session.
    HttpConnections.installBrowser(
        url -> {
          HttpURLConnection c = (HttpURLConnection) url.openConnection();
          c.setRequestProperty("X-Cleared", "1");
          return c;
        });
  }

  @After
  public void stop() throws Exception {
    HttpConnections.installBrowser(null);
    server.close();
    thread.join(2000);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getLocalPort() + "/segment/items1.m3u8";
  }

  private static void setPreferDirect(HttpSource source) throws Exception {
    var f = HttpSource.class.getDeclaredField("preferDirect");
    f.setAccessible(true);
    f.setBoolean(source, true);
  }

  @Test
  public void aDirectBlockedHostIsServedThroughTheBrowserStackAndThenRemembered() throws Exception {
    try (HttpSource source = new HttpSource(url(), Collections.emptyMap(), new TransferControl())) {
      // Simulate an earlier request having pinned the transfer to the direct stack.
      setPreferDirect(source);

      // First request: the direct stack is blocked (403), then the browser stack serves it.
      assertTrue(source.document(url()).text.contains("#EXTM3U"));
      assertEquals("one blocked direct attempt", 1, directHits.get());
      assertEquals("served through the browser stack", 1, browserHits.get());

      // Second request to the same host skips the dead direct attempt entirely.
      assertTrue(source.document(url()).text.contains("#EXTM3U"));
      assertEquals("no further blocked direct attempts", 1, directHits.get());
      assertEquals(2, browserHits.get());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void aBrowserStackAttemptIsRecordedAsGeckoNotHiddenAsNative() throws Exception {
    // Model the real on-device case where the anti-bot blocks even the browser stack (both the
    // direct and the browser retry are 403). The browser attempt must be visible in diagnostics as
    // transport=gecko, so a report can show the browser stack was tried and also blocked rather
    // than leaving every attempt indistinguishable as "native".
    alwaysBlock = true;
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(0, "HLS", url(), "http://127.0.0.1/", Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source =
            new HttpSource(url(), Collections.emptyMap(), new TransferControl())) {
      setPreferDirect(source);
      assertThrows(DownloadError.class, () -> source.document(url()));
    }
    Map<String, Object> report =
        session.report(
            new DownloadError(DownloadError.Category.ACCESS_BLOCKED),
            DownloadError.Category.ACCESS_BLOCKED,
            "TEST");
    List<Map<String, Object>> events = (List<Map<String, Object>>) report.get("eventHistory");
    assertTrue(
        "a direct attempt is recorded",
        events.stream().anyMatch(e -> "native".equals(e.get("transport"))));
    assertTrue(
        "the browser-stack attempt is recorded as gecko, not hidden as native",
        events.stream().anyMatch(e -> "gecko".equals(e.get("transport"))));
    assertTrue("the browser stack was actually tried", browserHits.get() >= 1);
  }

  @Test
  public void withoutABrowserStackADirectBlockRemainsAFailure() throws Exception {
    HttpConnections.installBrowser(null); // no browser stack available
    try (HttpSource source = new HttpSource(url(), Collections.emptyMap(), new TransferControl())) {
      setPreferDirect(source);
      DownloadError blocked = assertThrows(DownloadError.class, () -> source.document(url()));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, blocked.category);
    }
  }
}
