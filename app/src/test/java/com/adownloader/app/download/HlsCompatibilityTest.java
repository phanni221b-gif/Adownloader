package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.util.*;
import org.junit.Test;

public class HlsCompatibilityTest {
  @Test
  public void repeatedMapAndDiscontinuitySequenceAreNotAnActualDiscontinuity() {
    String text =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:7\n"
            + "#EXT-X-TARGETDURATION:2\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:0\n"
            + "#EXT-X-MAP:URI=\"init.mp4\"\n"
            + "#EXTINF:2,\n"
            + "a.m4s\n"
            + "#EXT-X-MAP:URI=\"init.mp4\"\n"
            + "#EXTINF:2,\n"
            + "b.m4s\n"
            + "#EXT-X-ENDLIST\n";
    var t = ManifestParser.hlsTrack("https://cdn.test/v/list.m3u8?token=secret", text);
    assertEquals(3, t.parts.size());
    assertEquals(4, t.duration, 0.001);
    assertEquals("https://cdn.test/v/a.m4s", t.parts.get(1).url);
  }

  @Test
  public void bomAndCommentsBetweenVariantAndUriAreTolerated() {
    var s =
        ManifestParser.hlsSelection(
            "https://cdn.test/master.m3u8?sig=private",
            "\ufeff#EXTM3U\r\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=1280x720\r\n"
                + "# CDN generated comment\r\n\r\n"
                + "v/list.m3u8\r\n");
    assertEquals("https://cdn.test/v/list.m3u8", s.video);
  }

  @Test
  public void rootAndRedirectSetCookieReachChildWithoutGlobalCookieHandler() throws Exception {
    ServerSocket server = new ServerSocket(0);
    Thread worker =
        new Thread(
            () -> {
              while (!server.isClosed())
                try (Socket c = server.accept()) {
                  BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                  String path = in.readLine().split(" ")[1], line, cookie = "";
                  while ((line = in.readLine()) != null && !line.isEmpty())
                    if (line.toLowerCase(Locale.ROOT).startsWith("cookie:")) cookie = line;
                  boolean start = path.equals("/start"),
                      root = path.equals("/root"),
                      ok =
                          cookie.contains("edge=private")
                              && (root || cookie.contains("child=private"));
                  byte[] body = (ok ? "#EXTM3U\n" : "<html>access denied</html>").getBytes();
                  String extra =
                      start
                          ? "Location: /root\r\nSet-Cookie: edge=private; Path=/\r\n"
                          : root ? "Set-Cookie: child=private; Path=/\r\n" : "";
                  c.getOutputStream()
                      .write(
                          ("HTTP/1.1 "
                                  + (start ? "302 Found" : ok || root ? "200 OK" : "403 Forbidden")
                                  + "\r\n"
                                  + extra
                                  + "Content-Length: "
                                  + body.length
                                  + "\r\nConnection: close\r\n\r\n")
                              .getBytes());
                  c.getOutputStream().write(body);
                } catch (Exception e) {
                  if (!server.isClosed()) throw new RuntimeException(e);
                }
            });
    worker.setDaemon(true);
    worker.start();
    try {
      String base = "http://127.0.0.1:" + server.getLocalPort();
      HttpSource source = new HttpSource(base + "/start", Map.of());
      assertTrue(source.document(base + "/start", null).text.startsWith("#EXTM3U"));
      assertTrue(source.document(base + "/child", null).text.startsWith("#EXTM3U"));
    } finally {
      server.close();
      worker.join(2000);
    }
  }
}
