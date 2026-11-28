package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/**
 * Reproduction of on-device failure F-3d5b0023: four concurrent segment workers start while the
 * first request decides the transport. The segment host answers every transport with a 403 HTML
 * challenge. The first worker proves the host blocked (HOST_ACCESS_BLOCKED), but the three workers
 * that waited for the decision each repeated the browser and direct attempts. A server refusal must
 * be respected: once the host is proven blocked, no further request may reach it.
 */
public class BlockedHostRepeatTest {
  private ServerSocket server;
  private Thread thread;
  private final AtomicInteger hits = new AtomicInteger();

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
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
                  hits.incrementAndGet();
                  Thread.sleep(60); // a real CDN round trip: lets the other workers queue up
                  byte[] out =
                      "<html><title>Just a moment...</title>challenge</html>"
                          .getBytes(StandardCharsets.UTF_8);
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
    HttpConnections.installBrowser(url -> (HttpURLConnection) url.openConnection());
  }

  @After
  public void stop() throws Exception {
    HttpConnections.installBrowser(null);
    server.close();
    thread.join(2000);
  }

  @Test
  public void concurrentSegmentsStopOnceTheHostIsProvenBlocked() throws Exception {
    String base = "http://127.0.0.1:" + server.getLocalPort() + "/v/";
    StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n");
    for (int i = 0; i < 4; i++) playlist.append("#EXTINF:4,\nseg").append(i).append(".ts\n");
    playlist.append("#EXT-X-ENDLIST\n");
    ManifestParser.Track track = ManifestParser.hlsTrack(base + "index.m3u8", playlist.toString());
    try (HttpSource source =
        new HttpSource(base + "index.m3u8", Collections.emptyMap(), new TransferControl())) {
      source.registerTracks(List.of(track));
      ExecutorService pool = Executors.newFixedThreadPool(4);
      List<Future<DownloadError>> results = new ArrayList<>();
      CountDownLatch go = new CountDownLatch(1);
      for (ManifestParser.Part part : track.parts)
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    source.release(source.open(part.url, 0, -1));
                    return null;
                  } catch (DownloadError e) {
                    return e;
                  }
                }));
      go.countDown();
      for (Future<DownloadError> r : results) {
        DownloadError e = r.get(20, TimeUnit.SECONDS);
        assertNotNull("every segment fails", e);
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      }
      pool.shutdownNow();
      // Only the first segment's browser and direct attempts reach the server.
      assertEquals("no request is repeated after the host is proven blocked", 2, hits.get());
      assertEquals(3, source.blockedRequestsSkipped());
    }
  }
}
