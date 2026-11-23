package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/**
 * Regression for the on-device yasadong reports (dom-media candidate, GECKO_CACHE_MISS,
 * geckoMediaSuccessObserved:false, HTTP 302 to a CDN that answers 403, the direct request getting
 * HTTP 200 HTML, cookiePresent:false, MANIFEST_NATIVE_BLOCKED), reproduced with a mock server:
 *
 * <ul>
 *   <li>a first-party relay ({@code 127.0.0.1}) that redirects to the CDN only for a request that
 *       carries the site session cookie, and otherwise answers 200 with an HTML page;
 *   <li>a CDN on another site ({@code localhost}) that serves the playlist and segments only with
 *       its own cookie and a Referer from the page's origin, and otherwise answers 403.
 * </ul>
 *
 * A WebView-based downloader passes because it asks the browser cookie store for every URL and
 * redirect hop (CookieManager.getCookie). The same now happens through {@link BrowserCookies}. The
 * real site itself is not exercised here.
 */
public class BrowserSessionParityTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  private ServerSocket server;
  private Thread thread;
  private final Map<String, List<Map<String, String>>> requests = new ConcurrentHashMap<>();
  private static final byte[] TS = ts(188 * 400);

  private static byte[] ts(int size) {
    byte[] out = new byte[size];
    for (int i = 0; i < size; i++) out[i] = (byte) (i % 188 == 0 ? 0x47 : (i * 31) & 0xff);
    return out;
  }

  private int port() {
    return server.getLocalPort();
  }

  private String site() {
    return "http://127.0.0.1:" + port();
  }

  private String cdn() {
    return "http://localhost:" + port();
  }

  private String page() {
    return site() + "/watch/50451?tab=1#player";
  }

  private String relay() {
    return site() + "/hls/50451C0C.m3u8";
  }

  private int count(String path) {
    return requests.getOrDefault(path, List.of()).size();
  }

  @Before
  public void start() throws Exception {
    HttpConnections.installBrowser(null);
    server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    thread = new Thread(this::serve);
    thread.start();
  }

  @After
  public void stop() throws Exception {
    BrowserCookies.install(null);
    server.close();
    thread.join(2000);
  }

  private void serve() {
    while (!server.isClosed())
      try (Socket socket = server.accept()) {
        BufferedReader in =
            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String first = in.readLine();
        if (first == null) continue;
        String path = first.split(" ")[1];
        Map<String, String> h = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
          int c = line.indexOf(':');
          if (c > 0) h.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        requests.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(h);
        respond(socket.getOutputStream(), path, h, count(path));
      } catch (Exception stopped) {
        if (!server.isClosed()) stopped.printStackTrace();
      }
  }

  private static void write(OutputStream out, String status, String type, byte[] body, String extra, int send)
      throws IOException {
    out.write(
        ("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\n" + extra + "Connection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.write(body, 0, Math.min(send, body.length));
    out.flush();
  }

  private static void ok(OutputStream out, String type, byte[] body) throws IOException {
    write(out, "200 OK", type, body, "", body.length);
  }

  private static byte[] text(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private void respond(OutputStream out, String path, Map<String, String> h, int nth) throws IOException {
    String cookie = h.getOrDefault("cookie", ""), referer = h.getOrDefault("referer", "");
    boolean cdnSession = cookie.contains("cdn=ok") && referer.startsWith(site() + "/");
    switch (path) {
      case "/hls/50451C0C.m3u8" -> {
        if (cookie.contains("sid=ok"))
          write(out, "302 Found", "text/html", new byte[0], "Location: " + cdn() + "/cdn/master.m3u8\r\n", 0);
        else ok(out, "text/html", text("<!doctype html><html><head><title>site</title></head><body>page</body></html>"));
      }
      case "/cdn/master.m3u8" -> {
        if (cdnSession)
          ok(out, "application/vnd.apple.mpegurl", text("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:1,\nseg0.ts\n#EXT-X-ENDLIST\n"));
        else write(out, "403 Forbidden", "text/plain", text("Forbidden"), "", 9);
      }
      case "/cdn/seg0.ts" -> {
        if (cdnSession) ok(out, "video/mp2t", TS);
        else write(out, "403 Forbidden", "text/plain", text("Forbidden"), "", 9);
      }
      case "/cdn/html.ts" -> ok(out, "text/html", text("<!DOCTYPE html>\n<html><body>Access denied</body></html>"));
      case "/cdn/flaky.ts", "/cdn/norange.ts" -> {
        String range = h.get("range");
        if (range == null && nth == 1) write(out, "200 OK", "video/mp2t", TS, "", 30_000);
        else if (range != null && path.equals("/cdn/flaky.ts")) {
          String[] ab = range.substring("bytes=".length()).split("-");
          int a = Integer.parseInt(ab[0]), b = ab.length > 1 && !ab[1].isEmpty() ? Integer.parseInt(ab[1]) : TS.length - 1;
          byte[] slice = Arrays.copyOfRange(TS, a, b + 1);
          write(out, "206 Partial Content", "video/mp2t", slice,
              "Content-Range: bytes " + a + "-" + b + "/" + TS.length + "\r\n", slice.length);
        } else ok(out, "video/mp2t", TS);
      }
      case "/cdn/busy.ts" -> {
        if (nth == 1) write(out, "429 Too Many Requests", "text/plain", text("slow down"), "Retry-After: 1\r\n", 9);
        else ok(out, "video/mp2t", TS);
      }
      default -> write(out, "404 Not Found", "text/plain", text("no"), "", 2);
    }
  }

  /** Fake browser jar: the site cookie belongs to the site host, the CDN cookie to the CDN host. */
  private void browserJar() {
    BrowserCookies.install(
        (url, page) -> {
          String host = URI.create(url).getHost();
          return host.equals("127.0.0.1") ? "sid=ok" : host.equals("localhost") ? "cdn=ok" : "";
        });
  }

  private HttpSource domCandidate() {
    return new HttpSource(relay(), Map.of(), new TransferControl()).pageContext(page());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> history(FailureDiagnostics.Session s, DownloadError e) {
    return (List<Map<String, Object>>) s.report(e, e.category, "ACCESS_BLOCKED").get("eventHistory");
  }

  @Test
  public void withoutTheBrowserJarTheReportedFailureIsReproduced() throws Exception {
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(1, "HLS", relay(), page(), Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source = domCandidate()) {
      DownloadError e = assertThrows(DownloadError.class, () -> source.document(relay(), null, "root"));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      assertEquals("HTTP 200 that is an HTML page is not a playlist", 200, e.diagnostic.status);
      assertEquals(HlsDiagnostic.Stage.ROOT_BODY, e.diagnostic.stage);
      assertEquals("page", e.diagnostic.body);
      List<Map<String, Object>> events = history(session, e);
      assertTrue(events.stream().anyMatch(ev -> "MANIFEST_NATIVE_BLOCKED".equals(ev.get("action"))));
      assertTrue(
          "the session-less request carried no cookie",
          events.stream().anyMatch(ev -> Boolean.FALSE.equals(ev.get("cookiePresent"))));
    }
    assertEquals("the CDN was never reached", 0, count("/cdn/master.m3u8"));
  }

  @Test
  public void theBrowserJarIsAskedPerHopAndEachHostGetsOnlyItsOwnCookies() throws Exception {
    browserJar();
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(2, "HLS", relay(), page(), Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source = domCandidate()) {
      HttpSource.Document d = source.document(relay(), null, "root");
      assertTrue(d.text.startsWith("#EXTM3U"));
      assertEquals("final URL after the redirect is the CDN playlist", cdn() + "/cdn/master.m3u8", d.url);
      assertEquals(1, d.redirects);
    }
    Map<String, String> atRelay = requests.get("/hls/50451C0C.m3u8").get(0);
    Map<String, String> atCdn = requests.get("/cdn/master.m3u8").get(0);
    assertEquals("sid=ok", atRelay.get("cookie"));
    assertEquals("the site cookie never crosses to the CDN host", "cdn=ok", atCdn.get("cookie"));
    assertEquals(
        "same-origin subresource: the full page URL without its fragment",
        site() + "/watch/50451?tab=1",
        atRelay.get("referer"));
    assertEquals("cross-origin: only the page origin", site() + "/", atCdn.get("referer"));
  }

  @Test
  public void anHlsSegmentFromTheCdnDownloadsCompleteThroughTheSameSession() throws Exception {
    browserJar();
    File out = folder.newFile("seg0.ts");
    try (HttpSource source = domCandidate()) {
      source.stream(cdn() + "/cdn/seg0.ts", 0, -1, out, null);
    }
    assertArrayEquals(TS, java.nio.file.Files.readAllBytes(out.toPath()));
  }

  @Test
  public void aCdn403IsClassifiedAsAccessBlockedOnTheCdnHostNotRetried() throws Exception {
    File out = folder.newFile("denied.ts");
    try (HttpSource source = domCandidate()) {
      DownloadError e =
          assertThrows(DownloadError.class, () -> source.stream(cdn() + "/cdn/seg0.ts", 0, -1, out, null));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      assertEquals(403, e.diagnostic.status);
      assertEquals("localhost", e.diagnostic.host);
    }
    assertEquals("a 403 is not a transient: one request", 1, count("/cdn/seg0.ts"));
  }

  @Test
  public void anHttp200HtmlSegmentIsRejectedNotSavedAndNotRetried() throws Exception {
    File out = folder.newFile("html.ts");
    try (HttpSource source = domCandidate()) {
      DownloadError e =
          assertThrows(DownloadError.class, () -> source.stream(cdn() + "/cdn/html.ts", 0, -1, out, null));
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      assertEquals(HlsDiagnostic.Stage.SEGMENT_BODY, e.diagnostic.stage);
      assertEquals(200, e.diagnostic.status);
      assertEquals("challenge", e.diagnostic.body);
    }
    assertEquals(1, count("/cdn/html.ts"));
  }

  @Test
  public void anInterruptedSegmentResumesWithAValidatedRange() throws Exception {
    File out = folder.newFile("flaky.ts");
    try (HttpSource source = domCandidate()) {
      source.stream(cdn() + "/cdn/flaky.ts", 0, -1, out, null);
    }
    assertArrayEquals(TS, java.nio.file.Files.readAllBytes(out.toPath()));
    List<Map<String, String>> sent = requests.get("/cdn/flaky.ts");
    assertEquals(2, sent.size());
    assertEquals("bytes=30000-" + (TS.length - 1), sent.get(1).get("range"));
  }

  @Test
  public void aServerThatIgnoresTheResumeRangeGetsAFullRestartNotASplicedFile() throws Exception {
    File out = folder.newFile("norange.ts");
    try (HttpSource source = domCandidate()) {
      source.stream(cdn() + "/cdn/norange.ts", 0, -1, out, null);
    }
    assertArrayEquals(TS, java.nio.file.Files.readAllBytes(out.toPath()));
    assertNull("the final attempt is a whole-segment request", requests.get("/cdn/norange.ts").get(2).get("range"));
  }

  @Test
  public void http429HonoursRetryAfterThenSucceeds() throws Exception {
    File out = folder.newFile("busy.ts");
    long began = System.currentTimeMillis();
    try (HttpSource source = domCandidate()) {
      source.stream(cdn() + "/cdn/busy.ts", 0, -1, out, null);
    }
    assertTrue("waited for Retry-After", System.currentTimeMillis() - began >= 900);
    assertArrayEquals(TS, java.nio.file.Files.readAllBytes(out.toPath()));
    assertEquals(2, count("/cdn/busy.ts"));
  }

  @Test
  public void aMissing404SegmentIsNotRetried() throws Exception {
    File out = folder.newFile("missing.ts");
    try (HttpSource source = domCandidate()) {
      assertThrows(DownloadError.class, () -> source.stream(cdn() + "/cdn/missing.ts", 0, -1, out, null));
    }
    assertEquals(1, count("/cdn/missing.ts"));
  }
}
