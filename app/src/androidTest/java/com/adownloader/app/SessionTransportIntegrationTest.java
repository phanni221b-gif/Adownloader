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
      boolean page = path.startsWith("/watch"),
          root = path.endsWith(".m3u8"),
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
      String html =
          "<!doctype html><title>Session"
              + " fixture</title><script>fetch('/media/playlist.m3u8',{headers:{Authorization:'"
              + auth
              + "','X-Playback-Token':'"
              + token
              + "'}}).then(r=>r.text()).then(t=>window.ready=t.startsWith('#EXTM3U'));</script>";
      byte[] body =
          page
              ? html.getBytes(StandardCharsets.UTF_8)
              : allowed
                  ? asset(root ? "playlist.m3u8" : path.substring(path.lastIndexOf('/') + 1))
                  : "<html>access denied challenge</html>".getBytes(StandardCharsets.UTF_8);
      String mime =
          page
              ? "text/html"
              : !allowed ? "text/html" : root ? "application/vnd.apple.mpegurl" : "video/mp2t";
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
        if (item.getUrl().equals(base + "/media/playlist.m3u8")
            && item.getHeaders().containsKey("Authorization")
            && MediaNetworkSession.latest(item.getUrl(), item.getPageUrl()) != null)
          return browser.getDetector().forDownload(item);
      Thread.sleep(100);
    }
    throw new AssertionError("Successful Gecko HLS context was not captured");
  }

  void run(String kind, boolean blocked) throws Exception {
    mode = kind;
    MediaCandidate item = discover();
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
      assertEquals("category=" + result.error, DownloadJobStore.State.COMPLETED, result.state);
      try (var fd = context.getContentResolver().openFileDescriptor(Uri.parse(result.uri), "r")) {
        var tracks = PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
        assertTrue(tracks.video && tracks.audio);
      }
      context.getContentResolver().delete(Uri.parse(result.uri), null, null);
    }
    if (!kind.equals("native")) {
      assertTrue("Native request must be blocked before browser bootstrap", nativeDenied.get() > 0);
      assertTrue("A bounded second Gecko manifest request must occur", browserRoots.get() >= 2);
    } else assertEquals(0, nativeDenied.get());
    for (File db : context.getDatabasePath("download-jobs.db").getParentFile().listFiles())
      if (db.isFile()) {
        String raw = new String(Files.readAllBytes(db.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(auth));
        assertFalse(raw.contains(token));
        assertFalse(raw.contains(cookie));
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
