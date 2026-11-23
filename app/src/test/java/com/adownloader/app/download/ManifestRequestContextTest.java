package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;

/**
 * Reproduction of on-device failure F-0760eea6: a plain HLS manifest detected from the DOM
 * (detectionSource=dom-media) carries no captured request context, so the direct request reaches a
 * hotlink/anti-bot CDN with no Referer and only the HTTP stack's default (non-browser) User-Agent,
 * and is answered with a 403 HTML challenge. The fix supplies the page as Referer and the engine's
 * own User-Agent when the captured context does not provide them, matching the in-page player.
 */
public class ManifestRequestContextTest {
  private ServerSocket server;
  private Thread thread;
  private volatile String seenReferer, seenUserAgent;
  private static final String MANIFEST = "#EXTM3U\n#EXT-X-ENDLIST\n";

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
                  String referer = null, userAgent = null, line;
                  while ((line = in.readLine()) != null && !line.isEmpty()) {
                    String l = line.toLowerCase(Locale.ROOT);
                    if (l.startsWith("referer:")) referer = line.substring(8).trim();
                    if (l.startsWith("user-agent:")) userAgent = line.substring(11).trim();
                  }
                  seenReferer = referer;
                  seenUserAgent = userAgent;
                  // Hotlink protection: the manifest is served only to a request that carries a
                  // Referer (what an in-page player sends); anything else gets a 403 HTML challenge.
                  byte[] out;
                  String status, type;
                  if (referer != null && !referer.isEmpty()) {
                    out = MANIFEST.getBytes(StandardCharsets.UTF_8);
                    status = "200 OK";
                    type = "application/vnd.apple.mpegurl";
                  } else {
                    out = "<html><body>Forbidden</body></html>".getBytes(StandardCharsets.UTF_8);
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
  }

  @After
  public void stop() throws Exception {
    setUserAgentField(""); // isolate the process-wide User-Agent fallback between tests
    server.close();
    thread.join(2000);
  }

  private static void setUserAgentField(String value) throws Exception {
    var f = HttpConnections.class.getDeclaredField("userAgent");
    f.setAccessible(true);
    f.set(null, value);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getLocalPort() + "/ACBD/items1.m3u8";
  }

  @Test
  public void domDetectedManifestWithoutRefererIsBlockedWith403() throws Exception {
    // No page context: the request reaches the CDN with no Referer and is blocked — the failure.
    setUserAgentField("");
    try (HttpSource source = new HttpSource(url(), Collections.emptyMap(), new TransferControl())) {
      DownloadError blocked = assertThrows(DownloadError.class, () -> source.document(url()));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, blocked.category);
    }
    assertNull("no page context means no Referer was available to send", seenReferer);
  }

  @Test
  public void pageRefererAndEngineUserAgentUnblockTheManifest() throws Exception {
    setUserAgentField("Mozilla/5.0 (Android) TestEngine/160");
    String page = "http://127.0.0.1:" + server.getLocalPort() + "/watch?items_id=713";
    try (HttpSource source =
        new HttpSource(url(), Collections.emptyMap(), new TransferControl()).pageContext(page)) {
      HttpSource.Document doc = source.document(url());
      assertTrue(doc.text.contains("#EXTM3U"));
    }
    // The request now presents the page (origin) as Referer and the engine's own User-Agent —
    // exactly what the in-page player sends — so the hotlink gate serves the manifest.
    // Page and manifest share an origin here, so the browser default (strict-origin-when-cross-
    // origin) sends the full page URL; a cross-origin CDN would receive only the page origin.
    assertEquals(page, seenReferer);
    assertEquals("Mozilla/5.0 (Android) TestEngine/160", seenUserAgent);
  }

  @Test
  public void aCapturedContextIsNeverOverriddenByTheFallback() throws Exception {
    setUserAgentField("Mozilla/5.0 (Android) TestEngine/160");
    String page = "http://127.0.0.1:" + server.getLocalPort() + "/watch";
    Map<String, String> captured = new LinkedHashMap<>();
    captured.put("User-Agent", "CapturedPlayer/9");
    captured.put("Referer", page + "/exact/path");
    try (HttpSource source =
        new HttpSource(url(), captured, new TransferControl()).pageContext(page)) {
      source.document(url());
    }
    assertEquals("CapturedPlayer/9", seenUserAgent);
    assertEquals(page + "/exact/path", seenReferer);
  }
}
