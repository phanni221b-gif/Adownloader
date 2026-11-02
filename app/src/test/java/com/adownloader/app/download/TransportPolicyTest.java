package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

public class TransportPolicyTest {
  ServerSocket server;
  ExecutorService io;
  String root, page;
  AtomicInteger bridges = new AtomicInteger(), nativeRoots = new AtomicInteger();
  MediaNetworkSession.Provider provider;
  List<Map<String, Object>> trace = new CopyOnWriteArrayList<>();

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0);
    root = "http://127.0.0.1:" + server.getLocalPort() + "/v/master.m3u8";
    page = "http://127.0.0.1:" + server.getLocalPort() + "/watch";
    io = Executors.newCachedThreadPool();
    io.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket c = server.accept();
              io.execute(() -> serve(c));
            } catch (IOException ignored) {
            }
        });
    MediaNetworkSession.diagnostics(trace::add);
  }

  void serve(Socket c) {
    try (c) {
      BufferedReader r =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
      String line = r.readLine();
      if (line == null) return;
      if (line.contains("master.m3u8")) nativeRoots.incrementAndGet();
      while ((line = r.readLine()) != null && !line.isEmpty()) {}
      byte[] b = "<html>access denied challenge</html>".getBytes(StandardCharsets.UTF_8);
      c.getOutputStream()
          .write(
              ("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html\r\nContent-Length: "
                      + b.length
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      c.getOutputStream().write(b);
    } catch (IOException ignored) {
    }
  }

  @After
  public void stop() throws Exception {
    if (provider != null) MediaNetworkSession.remove(provider);
    MediaNetworkSession.diagnostics(null);
    server.close();
    io.shutdownNow();
  }

  void install(long age) {
    provider =
        new MediaNetworkSession.Provider() {
          public MediaNetworkSession.Observation latest(String url, String top) {
            return url.equals(root) && top.equals(page)
                ? new MediaNetworkSession.Observation(
                    root,
                    page,
                    0,
                    200,
                    System.currentTimeMillis() - age,
                    Map.of(),
                    List.of("sec-fetch-mode"))
                : null;
          }

          public MediaNetworkSession.Bootstrap manifest(
              String url, String top, Map<String, String> headers, TransferControl control) {
            bridges.incrementAndGet();
            return new MediaNetworkSession.Bootstrap(
                root,
                200,
                0,
                Map.of("content-type", "application/vnd.apple.mpegurl"),
                "#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                Map.of());
          }
        };
    MediaNetworkSession.install(provider);
  }

  @Test
  public void recentBrowserSuccessAllowsOneManifestBootstrapButNoSegmentBridge() throws Exception {
    install(0);
    try (HttpSource source = new HttpSource(root, Map.of()).pageContext(page)) {
      var d = source.document(root, null, "root");
      assertTrue(d.text.startsWith("#EXTM3U"));
      assertTrue(source.hasResponseCookies());
      var tracks = List.of(ManifestParser.hlsTrack(root, d.text));
      source.registerTracks(tracks);
      try {
        source.open(root.replace("master.m3u8", "part.ts"), 0, -1);
        fail();
      } catch (DownloadError e) {
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      }
      assertEquals(1, bridges.get());
      assertTrue(trace.toString().contains("SEGMENT_NATIVE_BLOCKED"));
      try {
        source.document(root, null, "root");
        fail();
      } catch (DownloadError e) {
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      }
      assertEquals(1, bridges.get());
    }
  }

  @Test
  public void staleBrowserSuccessCannotTriggerBootstrap() throws Exception {
    install(121000);
    try (HttpSource source = new HttpSource(root, Map.of()).pageContext(page)) {
      try {
        source.document(root, null, "root");
        fail();
      } catch (DownloadError e) {
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      }
      assertEquals(0, bridges.get());
      assertEquals(1, nativeRoots.get());
    }
  }
}
