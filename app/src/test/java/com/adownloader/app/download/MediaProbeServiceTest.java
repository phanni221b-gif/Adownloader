package com.adownloader.app.download;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

public class MediaProbeServiceTest {
  private ServerSocket server;
  private ExecutorService network;
  private MediaProbeService service;
  private final AtomicInteger requests = new AtomicInteger();
  private volatile String etag = "\"v1\"", ifNone, cookie, part = "part.ts";
  private CountDownLatch started, release;

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0);
    network = Executors.newCachedThreadPool();
    network.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket socket = server.accept();
              network.execute(() -> serve(socket));
            } catch (IOException ignored) {
            }
        });
    service = new MediaProbeService(60000);
  }

  @After
  public void close() throws Exception {
    if (release != null) release.countDown();
    service.close();
    server.close();
    network.shutdownNow();
  }

  private String url() {
    return "http://127.0.0.1:" + server.getLocalPort() + "/once.m3u8";
  }

  private void serve(Socket socket) {
    try (Socket client = socket) {
      BufferedReader input =
          new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
      input.readLine();
      String line;
      Map<String, String> headers = new HashMap<>();
      while ((line = input.readLine()) != null && !line.isEmpty()) {
        int colon = line.indexOf(':');
        if (colon > 0)
          headers.put(
              line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
      }
      requests.incrementAndGet();
      ifNone = headers.get("if-none-match");
      cookie = headers.get("cookie");
      if (started != null) started.countDown();
      if (release != null) release.await(10, TimeUnit.SECONDS);
      boolean unchanged = etag.equals(ifNone);
      byte[] body =
          unchanged
              ? new byte[0]
              : ("#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\n" + part + "\n#EXT-X-ENDLIST\n")
                  .getBytes(StandardCharsets.UTF_8);
      String response =
          "HTTP/1.1 "
              + (unchanged ? "304 Not Modified" : "200 OK")
              + "\r\nETag: "
              + etag
              + "\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: "
              + body.length
              + "\r\nConnection: close\r\n\r\n";
      client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().write(body);
    } catch (Exception ignored) {
    }
  }

  @Test
  public void documentReadsExactlyOneResponse() throws Exception {
    HttpSource.Document doc = new HttpSource(url(), Map.of()).document(url());
    assertTrue(doc.text.startsWith("#EXTM3U"));
    assertEquals("\"v1\"", doc.etag);
    assertEquals(1, requests.get());
  }

  @Test
  public void previewAndTransferShareOneInflightOriginalPlan() throws Exception {
    started = new CountDownLatch(1);
    release = new CountDownLatch(1);
    ExecutorService consumer = Executors.newSingleThreadExecutor();
    try {
      Future<MediaProbeResult> preview =
          consumer.submit(() -> service.preview(url(), MediaCandidate.MediaType.HLS, Map.of()));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      consumer.shutdownNow();
      release.countDown();
      MediaProbeResult result =
          service.probe(url(), MediaCandidate.MediaType.HLS, Map.of(), new TransferControl());
      assertEquals(1, requests.get());
      assertEquals(1, result.tracks().size());
      assertTrue(result.tracks().get(0).parts.get(0).url.endsWith("part.ts"));
    } finally {
      consumer.shutdownNow();
    }
  }

  @Test
  public void expiredProbeRevalidatesAndChangedRepresentationIsFresh() throws Exception {
    service.close();
    service = new MediaProbeService(5);
    service.probe(url(), MediaCandidate.MediaType.HLS, Map.of(), new TransferControl());
    Thread.sleep(15);
    MediaProbeResult same =
        service.probe(url(), MediaCandidate.MediaType.HLS, Map.of(), new TransferControl());
    assertEquals(2, requests.get());
    assertEquals("\"v1\"", ifNone);
    assertTrue(same.tracks().get(0).parts.get(0).url.endsWith("part.ts"));
    etag = "\"v2\"";
    part = "new-part.ts";
    Thread.sleep(15);
    MediaProbeResult changed =
        service.probe(url(), MediaCandidate.MediaType.HLS, Map.of(), new TransferControl());
    assertEquals(3, requests.get());
    assertEquals("\"v2\"", changed.etag);
    assertTrue(changed.tracks().get(0).parts.get(0).url.endsWith("new-part.ts"));
  }

  @Test
  public void DifferentRequestCookiesNeverShareAProbe() throws Exception {
    service.probe(
        url(),
        MediaCandidate.MediaType.HLS,
        Map.of("Cookie", "request=one"),
        new TransferControl());
    service.probe(
        url(),
        MediaCandidate.MediaType.HLS,
        Map.of("Cookie", "request=two"),
        new TransferControl());
    assertEquals(2, requests.get());
    assertEquals("request=two", cookie);
  }
}
