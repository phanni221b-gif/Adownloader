package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.browser.*;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import com.adownloader.app.media.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SessionTransportIntegrationTest {
  final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
  final Context context = ins.getTargetContext();
  ServerSocket server;
  ExecutorService io;
  String base, mode;
  Activity activity;
  MediaNetworkSession.Provider realProvider, cacheMissProvider;
  AtomicInteger nativeDenied = new AtomicInteger(), browserRoots = new AtomicInteger();
  final String auth = "Bearer SYNTHETIC_PRIVATE_AUTH",
      token = "SYNTHETIC_PRIVATE_TOKEN",
      cookie = "sid=SYNTHETIC_PRIVATE_COOKIE";

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort();
    io = Executors.newCachedThreadPool();
    io.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket s = server.accept();
              io.execute(() -> serve(s));
            } catch (IOException ignored) {
            }
        });
  }

  @After
  public void stop() throws Exception {
    if (realProvider != null) MediaNetworkSession.install(realProvider);
    if (activity != null) ins.runOnMainSync(activity::finish);
    server.close();
    io.shutdownNow();
  }

  byte[] asset(String name) throws Exception {
    try (InputStream in = ins.getContext().getAssets().open("fixtures/" + name)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toByteArray();
    }
  }

  void serve(Socket s) {
    try (Socket c = s) {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
      String request = in.readLine();
      if (request == null) return;
      String path = request.split(" ")[1], line;
      Map<String, String> h = new HashMap<>();
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        int at = line.indexOf(':');
        if (at > 0)
          h.put(line.substring(0, at).toLowerCase(Locale.ROOT), line.substring(at + 1).trim());
      }
      if (path.startsWith("/cookie/")) {
        boolean root = path.endsWith("master.m3u8"), variant = path.endsWith("variant.m3u8");
        String jar = h.getOrDefault("cookie", "");
        boolean ok =
            root
                || jar.contains("boot=PRIVATE_BOOT")
                    && (variant || jar.contains("stage=PRIVATE_STAGE"));
        String text =
            root
                ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvariant.m3u8\n"
                : variant ? "#EXTM3U\n#EXTINF:1,\npiece.ts\n#EXT-X-ENDLIST\n" : "DATA";
        String set =
            root
                ? "Set-Cookie: boot=PRIVATE_BOOT; Path=/cookie/\r\n"
                : variant ? "Set-Cookie: stage=PRIVATE_STAGE; Path=/cookie/\r\n" : "";
        respond(
            c,
            ok ? 200 : 403,
            "application/vnd.apple.mpegurl",
            set,
            text.getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.equals("/redirect.m3u8")) {
        respond(
            c,
            302,
            "text/plain",
            "Location: http://localhost:" + server.getLocalPort() + "/foreign.m3u8\r\n",
            new byte[0]);
        return;
      }
      if (path.equals("/foreign.m3u8")) {
        boolean clean =
            !h.containsKey("authorization")
                && !h.containsKey("x-playback-token")
                && !h.containsKey("cookie")
                && !h.containsKey("origin");
        respond(
            c,
            clean ? 200 : 403,
            "application/vnd.apple.mpegurl",
            "",
            "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8));
        return;
      }
      boolean page = path.startsWith("/watch"),
          root = path.endsWith(".m3u8") || path.equals("/media/api/playback"),
          browser = "cors".equals(h.get("sec-fetch-mode"));
      boolean allowed =
          page
              || auth.equals(h.get("authorization"))
                  && token.equals(h.get("x-playback-token"))
                  && h.getOrDefault("cookie", "").contains(cookie)
                  && h.getOrDefault("referer", "").startsWith(base + "/watch");
      if (!page && ((root && mode.equals("bootstrap")) || mode.equals("segmentBlocked")))
        allowed &= browser;
      if (root && browser) browserRoots.incrementAndGet();
      if (!allowed) nativeDenied.incrementAndGet();
      String target = mode.equals("extensionless") ? "/media/api/playback" : "/media/playlist.m3u8";
      String html =
          "<!doctype html><title>Session fixture</title><video id='v'></video><script>"
              + "v.src=URL.createObjectURL(new MediaSource());setTimeout(()=>fetch('"
              + target
              + "',{headers:{Authorization:'"
              + auth
              + "','X-Playback-Token':'"
              + token
              + "'}}).then(r=>r.text()).then(t=>window.ready=t.startsWith('#EXTM3U')),250);</script>";
      byte[] body =
          page
              ? html.getBytes(StandardCharsets.UTF_8)
              : allowed
                  ? asset(root ? "playlist.m3u8" : path.substring(path.lastIndexOf('/') + 1))
                  : "<html>access denied challenge</html>".getBytes(StandardCharsets.UTF_8);
      String mime =
          page
              ? "text/html"
              : !allowed
                  ? "text/html"
                  : root
                      ? (mode.equals("extensionless")
                          ? "text/plain"
                          : "application/vnd.apple.mpegurl")
                      : "video/mp2t";
      String extra = page ? "Set-Cookie: " + cookie + "; Path=/; HttpOnly; SameSite=Lax\r\n" : "";
      c.getOutputStream()
          .write(
              ("HTTP/1.1 "
                      + (allowed ? "200 OK" : "403 Forbidden")
                      + "\r\nContent-Type: "
                      + mime
                      + "\r\n"
                      + extra
                      + "Cache-Control: no-store\r\nContent-Length: "
                      + body.length
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      c.getOutputStream().write(body);
    } catch (Exception ignored) {
    }
  }

  void respond(Socket c, int status, String mime, String extra, byte[] body) throws IOException {
    c.getOutputStream()
        .write(
            ("HTTP/1.1 "
                    + status
                    + " OK\r\nContent-Type: "
                    + mime
                    + "\r\n"
                    + extra
                    + "Content-Length: "
                    + body.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    c.getOutputStream().write(body);
  }

  @Test
  public void rootSetCookieReachesVariantThenSegmentAndSurvivesSnapshot() throws Exception {
    mode = "native";
    String url = base + "/cookie/master.m3u8";
    String snapshot;
    try (HttpSource source = new HttpSource(url, Map.of())) {
      assertTrue(source.document(url, null, "root").text.contains("STREAM-INF"));
      assertTrue(
          source
              .document(base + "/cookie/variant.m3u8", null, "variant")
              .text
              .contains("piece.ts"));
      snapshot = source.hasResponseCookies() ? "ready" : "";
      var c = source.open(base + "/cookie/piece.ts", 0, -1);
      try {
        assertEquals(200, c.getResponseCode());
      } finally {
        source.release(c);
      }
    }
    assertEquals("ready", snapshot);
  }

  @Test
  public void foreignRedirectStripsRootAuthenticationAndCookies() throws Exception {
    mode = "native";
    try (HttpSource source =
        new HttpSource(
            base + "/redirect.m3u8",
            Map.of(
                "Authorization",
                auth,
                "X-Playback-Token",
                token,
                "Cookie",
                cookie,
                "Origin",
                base))) {
      assertEquals(1, source.document(base + "/redirect.m3u8", null, "root").redirects);
    }
  }

  MediaCandidate discover() throws Exception {
    ins.getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    activity =
        ins.startActivitySync(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(base + "/watch"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    var field = MainActivity.class.getDeclaredField("browser");
    field.setAccessible(true);
    var browser = (BrowserSessionManager) field.get(activity);
    long end = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < end) {
      for (var item : browser.getDetector().snapshot())
        if (item.getUrl()
                .equals(
                    base
                        + (mode.equals("extensionless")
                            ? "/media/api/playback"
                            : "/media/playlist.m3u8"))
            && item.getHeaders().containsKey("Authorization")
            && MediaNetworkSession.latest(item.getUrl(), item.getPageUrl()) != null)
          return browser.getDetector().forDownload(item);
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Successful Gecko HLS context was not captured; candidates="
            + browser.getDetector().snapshot().size()
            + "; metrics="
            + String.valueOf(browser.getDetector().detectionDiagnostics)
            + "; capturedNames="
            + browser.getDetector().snapshot().stream()
                .map(
                    x ->
                        MediaHeaders.names(x.getHeaders().keySet()).toString()
                            + "/recent="
                            + (MediaNetworkSession.latest(x.getUrl(), x.getPageUrl()) != null))
                .toList());
  }

  void run(String kind, boolean blocked) throws Exception {
    mode = kind;
    int deniedBefore = nativeDenied.get();
    MediaCandidate item = discover();
    if (kind.equals("bootstrap") || kind.equals("segmentBlocked")) {
      // Keep the original real content.fetch fallback coverage when playback evidence is absent.
      realProvider = MediaNetworkSession.provider();
      cacheMissProvider =
          new MediaNetworkSession.Provider() {
            public MediaNetworkSession.Observation latest(String u, String p) {
              return realProvider.latest(u, p);
            }

            public MediaNetworkSession.Observation latest(String u, String p, int t, long f) {
              return realProvider.latest(u, p, t, f);
            }

            public MediaNetworkSession.Bootstrap manifest(
                String u, String p, Map<String, String> h, TransferControl c) throws Exception {
              return realProvider.manifest(u, p, h, c);
            }

            public MediaNetworkSession.Bootstrap manifest(
                String u, String p, int t, long f, Map<String, String> h, TransferControl c)
                throws Exception {
              return realProvider.manifest(u, p, t, f, h, c);
            }
          };
      MediaNetworkSession.install(cacheMissProvider);
    }
    assertEquals(auth, item.getHeaders().get("Authorization"));
    assertEquals(token, item.getHeaders().get("X-Playback-Token"));
    DownloadJobs jobs = DownloadJobs.get(context);
    CountDownLatch ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    AtomicLong id = new AtomicLong();
    CountDownLatch saved = new CountDownLatch(1);
    jobs.enqueue(
        item,
        v -> {
          id.set(v);
          saved.countDown();
        });
    assertTrue(saved.await(10, TimeUnit.SECONDS));
    assertTrue(id.get() > 0);
    long end = System.currentTimeMillis() + 45000;
    DownloadJobStore.Job result = null;
    while (System.currentTimeMillis() < end) {
      result = jobs.store.get(id.get());
      if (Set.of(
              DownloadJobStore.State.COMPLETED,
              DownloadJobStore.State.FAILED,
              DownloadJobStore.State.NEEDS_REFRESH)
          .contains(result.state)) break;
      Thread.sleep(100);
    }
    if (blocked) {
      assertEquals("ACCESS_BLOCKED", result.error);
      String diag = com.adownloader.app.ui.DeviceDiagnostics.collect(activity);
      assertTrue(diag.contains("SEGMENT_NATIVE_BLOCKED"));
      assertTrue(browserRoots.get() <= 3);
    } else {
      assertEquals(
          "category="
              + result.error
              + " "
              + com.adownloader.app.ui.DeviceDiagnostics.collect(activity),
          DownloadJobStore.State.COMPLETED,
          result.state);
      try (var fd = context.getContentResolver().openFileDescriptor(Uri.parse(result.uri), "r")) {
        var tracks = PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
        assertTrue(tracks.video && tracks.audio);
      }
      context.getContentResolver().delete(Uri.parse(result.uri), null, null);
    }
    if (!kind.equals("native") && !kind.equals("extensionless")) {
      assertTrue("Native request must be blocked before browser bootstrap", nativeDenied.get() > 0);
      assertTrue("A bounded second Gecko manifest request must occur", browserRoots.get() >= 2);
    } else
      // The browser transport cannot carry this fixture's captured-only Authorization/Cookie
      // combination; its single rejected attempt switches the transfer to the captured context.
      assertTrue(
          "At most one rejected browser-transport attempt per transfer",
          nativeDenied.get() - deniedBefore <= 1);
    for (File db : context.getDatabasePath("download-jobs.db").getParentFile().listFiles())
      if (db.isFile()) {
        String raw = new String(Files.readAllBytes(db.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(auth));
        assertFalse(raw.contains(token));
        assertFalse(raw.contains(cookie));
      }
  }

  @Test
  public void extensionlessMseResponseKeepsOnlyConfirmedMediaAuthentication() throws Exception {
    for (int iteration = 0; iteration < 3; iteration++) {
      run("extensionless", false);
      ins.runOnMainSync(activity::finish);
      activity = null;
    }
  }

  @Test
  public void actualGeckoAuthCustomCookieContextDownloadsNatively() throws Exception {
    run("native", false);
  }

  @Test
  public void onlyManifestFallsBackToActualGeckoThenNativeSegmentsComplete() throws Exception {
    run("bootstrap", false);
  }

  @Test
  public void blockedNativeSegmentsAreReportedWithoutSegmentBridge() throws Exception {
    run("segmentBlocked", true);
  }
}
