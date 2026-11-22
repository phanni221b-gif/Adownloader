package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;

/**
 * Reproduction of on-device report F-f98b173b (yasyadong04.tv): the candidate is a first-party
 * redirector. With the browsing session (browser stack) it answers 302 to a CDN that then refuses
 * with 403; without the session (direct stack) it answers 200 with an HTML page. The direct stack's
 * HTTP 200 used to be accepted as the transport winner, so the report blamed "HTTP 200 HTML" and the
 * real block — the CDN's 403 on the session path — vanished, and the transfer stayed pinned to the
 * session-less stack.
 */
public class DirectHtmlFallbackTest {
  private ServerSocket server;
  private Thread thread;
  private volatile boolean cdnServes;

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
                  String path = in.readLine().split(" ")[1];
                  boolean session = false;
                  String line;
                  while ((line = in.readLine()) != null && !line.isEmpty())
                    if (line.toLowerCase(Locale.ROOT).startsWith("x-cleared:")) session = true;
                  String status, type, extra = "";
                  byte[] body;
                  if (path.startsWith("/hls/")) {
                    if (session) {
                      status = "302 Found";
                      extra = "Location: /cdn/signed.m3u8\r\n";
                      type = "text/html";
                      body = new byte[0];
                    } else {
                      status = "200 OK";
                      type = "text/html";
                      body = "<!doctype html><html><body>site page</body></html>".getBytes(StandardCharsets.UTF_8);
                    }
                  } else if (cdnServes) {
                    status = "200 OK";
                    type = "application/vnd.apple.mpegurl";
                    body = "#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
                  } else {
                    status = "403 Forbidden";
                    type = "text/plain";
                    body = "Forbidden".getBytes(StandardCharsets.UTF_8);
                  }
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                                  + body.length + "\r\n" + extra + "Connection: close\r\n\r\n")
                              .getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().write(body);
                } catch (Exception stopped) {
                  if (!server.isClosed()) throw new RuntimeException(stopped);
                }
            });
    thread.start();
    // The browser stack carries the live session (modelled by the opener's marker header).
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

  private String candidate() {
    return "http://127.0.0.1:" + server.getLocalPort() + "/hls/50451C0C.m3u8";
  }

  private static boolean preferDirect(HttpSource source) throws Exception {
    var f = HttpSource.class.getDeclaredField("preferDirect");
    f.setAccessible(true);
    return f.getBoolean(source);
  }

  @Test
  public void theSessionPathRefusalIsReportedNotTheSessionlessHtmlPage() throws Exception {
    try (HttpSource source = new HttpSource(candidate(), Map.of(), new TransferControl())) {
      DownloadError e =
          assertThrows(DownloadError.class, () -> source.document(candidate(), null, "root"));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      assertNotNull(e.diagnostic);
      assertEquals("the CDN's refusal on the session path is the reported block", 403, e.diagnostic.status);
      assertEquals("text/plain", e.diagnostic.mime);
      assertEquals("the block happened after the redirect hop", 1, e.diagnostic.redirects);
      assertFalse(
          "a session-less HTML page must not pin the transfer to the direct stack", preferDirect(source));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void theReportNamesTheBrowserStackAndInventsNoDirectStackBlock() throws Exception {
    // On-device report F-6a32de2c (v0.8.9): the failure block said transport "native", and the
    // history ended with a "native 403" on the first-party host that never happened.
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(185, "HLS", candidate(), "http://127.0.0.1/", Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source = new HttpSource(candidate(), Map.of(), new TransferControl())) {
      DownloadError e =
          assertThrows(DownloadError.class, () -> source.document(candidate(), null, "root"));
      Map<String, Object> report = session.report(e, e.category, "ACCESS_BLOCKED");
      Map<String, Object> failure = (Map<String, Object>) report.get("failure");
      assertEquals("the stack that was refused", "gecko", failure.get("transport"));
      assertEquals(403L, ((Number) failure.get("httpStatus")).longValue());
      assertEquals(1L, ((Number) failure.get("redirects")).longValue());
      List<Map<String, Object>> history = (List<Map<String, Object>>) report.get("eventHistory");
      assertTrue(history.stream().anyMatch(ev -> "DIRECT_HTML_DISCARDED".equals(ev.get("action"))));
      assertFalse(
          "no direct-stack 403 is invented for a request the direct stack answered with 200",
          history.stream()
              .anyMatch(
                  ev ->
                      "native".equals(ev.get("transport"))
                          && ev.get("status") instanceof Number n
                          && n.intValue() == 403));
    }
  }

  @Test
  public void withoutABrowserStackTheHtmlPageIsStillClassifiedAsBefore() throws Exception {
    HttpConnections.installBrowser(null);
    try (HttpSource source = new HttpSource(candidate(), Map.of(), new TransferControl())) {
      DownloadError e =
          assertThrows(DownloadError.class, () -> source.document(candidate(), null, "root"));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      assertEquals("unchanged: the HTML page itself is reported", 200, e.diagnostic.status);
    }
  }

  @Test
  public void whenTheSessionPathSucceedsTheManifestIsReturned() throws Exception {
    cdnServes = true;
    try (HttpSource source = new HttpSource(candidate(), Map.of(), new TransferControl())) {
      assertTrue(source.document(candidate(), null, "root").text.startsWith("#EXTM3U"));
    }
  }
}
