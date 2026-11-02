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
  Map<String, String> bootstrapHeaders = Map.of();
  Map<String, List<String>> bootstrapCookies = Map.of();
  java.util.concurrent.atomic.AtomicReference<String> sentCookie =
      new java.util.concurrent.atomic.AtomicReference<>("");
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
      while ((line = r.readLine()) != null && !line.isEmpty()) {
        if (line.toLowerCase(Locale.ROOT).startsWith("cookie:"))
          sentCookie.set(line.substring(7).trim());
      }
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
                bootstrapCookies,
                bootstrapHeaders);
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

  @Test
  public void bootstrapSeedThenResponseCookieReachNativeSegmentWithoutStaleOverwrite()
      throws Exception {
    install(0);
    bootstrapHeaders = Map.of("Cookie", "sid=PRIVATE_FRESH");
    bootstrapCookies = Map.of(root, List.of("session=PRIVATE_NEXT; Path=/v/; SameSite=Lax"));
    try (HttpSource source =
        new HttpSource(root, Map.of("Cookie", "sid=PRIVATE_OLD")).pageContext(page)) {
      source.document(root, null, "root");
      try {
        source.open(root.replace("master.m3u8", "part.ts"), 0, -1);
        fail();
      } catch (DownloadError expected) {
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, expected.category);
      }
      assertTrue(sentCookie.get().contains("sid=PRIVATE_FRESH"));
      assertTrue(sentCookie.get().contains("session=PRIVATE_NEXT"));
      assertFalse(sentCookie.get().contains("PRIVATE_OLD"));
    }
  }

  @Test
  public void successfulPlaybackEvidenceIsUsedBeforeAnyNativeOrFetchReplay() throws Exception {
    install(0);
    var fallback = provider;
    provider =
        new MediaNetworkSession.Provider() {
          public MediaNetworkSession.Observation latest(String u, String p) {
            return fallback.latest(u, p);
          }

          public MediaNetworkSession.Bootstrap captured(
              String u, String p, int tab, int frame, TransferControl c) {
            if (!u.equals(root) || !p.equals(page) || tab != 9 || frame != 2) return null;
            return new MediaNetworkSession.Bootstrap(
                root,
                200,
                0,
                Map.of(
                    "content-type",
                    "application/vnd.apple.mpegurl",
                    "evidence-expires-at",
                    Long.toString(System.currentTimeMillis() + 60000)),
                "#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                Map.of("Authorization", "Bearer PRIVATE"));
          }

          public MediaNetworkSession.Bootstrap manifest(
              String u, String p, Map<String, String> h, TransferControl c) {
            bridges.incrementAndGet();
            throw new AssertionError("Cache hit must never fetch");
          }
        };
    MediaNetworkSession.install(provider);
    try (HttpSource source = new HttpSource(root, Map.of()).pageContext(page).browserScope(9, 2)) {
      assertTrue(source.document(root, null, "root").text.startsWith("#EXTM3U"));
      assertEquals(0, nativeRoots.get());
      assertEquals(0, bridges.get());
      assertTrue(source.hasResponseCookies());
      assertTrue(
          trace.stream()
              .anyMatch(
                  e ->
                      Boolean.TRUE.equals(e.get("manifestCacheUsed"))
                          && Boolean.TRUE.equals(e.get("nativeRequestSkipped"))));
      assertFalse(trace.toString().contains("Bearer PRIVATE"));
      assertFalse(trace.toString().contains("/v/master"));
    }
    var candidate =
        new com.adownloader.app.MediaCandidate(
            root,
            com.adownloader.app.MediaCandidate.MediaType.HLS,
            "network-response",
            page,
            Map.of(),
            System.currentTimeMillis(),
            Map.of("browserTabId", "9", "frameId", "2"));
    var handoff = MediaProbeService.shared().prepareCaptured(candidate);
    assertNotNull(handoff);
    assertEquals(1, handoff.tracks().size());
    assertTrue(handoff.tracks().get(0).parts.get(0).url.endsWith("part.ts"));
    assertEquals("Bearer PRIVATE", handoff.headers().get("Authorization"));
    assertFalse(handoff.cookieState().isBlank());
    assertEquals(0, nativeRoots.get());
    assertEquals(0, bridges.get());
  }

  @Test
  public void redirectedCaptureUsesFinalBaseAndFinalContextWithoutReplay() throws Exception {
    install(0);
    String finalUrl = root.replace("127.0.0.1", "localhost").replace("/v/", "/cdn/");
    var original = provider;
    provider =
        new MediaNetworkSession.Provider() {
          public MediaNetworkSession.Observation latest(String u, String p) {
            return original.latest(u, p);
          }

          public MediaNetworkSession.Bootstrap captured(
              String u, String p, int tab, int frame, TransferControl control) {
            return new MediaNetworkSession.Bootstrap(
                finalUrl,
                200,
                1,
                Map.of(
                    "content-type",
                    "application/vnd.apple.mpegurl",
                    "captured-request-url",
                    root,
                    "evidence-expires-at",
                    Long.toString(System.currentTimeMillis() + 60000)),
                "#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                Map.of("Cookie", "clearance=CDN_ONLY"));
          }

          public MediaNetworkSession.Bootstrap manifest(
              String u, String p, Map<String, String> h, TransferControl c) {
            throw new AssertionError("No replay");
          }
        };
    MediaNetworkSession.install(provider);
    var candidate =
        new com.adownloader.app.MediaCandidate(
            root,
            com.adownloader.app.MediaCandidate.MediaType.HLS,
            "dom-media",
            page,
            Map.of("Cookie", "login=PRIVATE_ORIGIN", "Authorization", "Bearer ORIGIN"),
            System.currentTimeMillis(),
            Map.of("browserTabId", "9", "frameId", "2"));
    var plan = MediaProbeService.shared().prepareCaptured(candidate);
    assertNotNull(plan);
    assertEquals(finalUrl, plan.probe().finalManifestUrl);
    assertEquals(finalUrl.replace("master.m3u8", "part.ts"), plan.tracks().get(0).parts.get(0).url);
    assertEquals("clearance=CDN_ONLY", plan.headers().get("Cookie"));
    assertFalse(plan.headers().containsKey("Authorization"));
    assertEquals(0, nativeRoots.get());
    var jar = new JobCookieJar(finalUrl, Map.of());
    jar.restore(plan.cookieState());
    String cookie = jar.header(java.net.URI.create(plan.tracks().get(0).parts.get(0).url));
    assertTrue(cookie.contains("CDN_ONLY"));
    assertFalse(cookie.contains("PRIVATE_ORIGIN"));
  }
}
