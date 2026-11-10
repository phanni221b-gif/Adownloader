package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.browser.*;
import com.adownloader.app.download.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.GeckoRuntime;

/**
 * Background downloads must not depend on a live tab: media requests go through Gecko's own network
 * stack and cookie store, with the captured-context HttpURLConnection path as fallback.
 */
@RunWith(AndroidJUnit4.class)
public class GeckoTransportIntegrationTest {
  final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
  final byte[] media = new byte[300_000];
  ServerSocket server;
  ExecutorService io;
  String base;
  GeckoRuntime runtime;

  @Before
  public void start() throws Exception {
    new Random(7).nextBytes(media);
    runtime = GeckoRuntimes.obtain(context, 60_000);
    HttpConnections.installBrowser(url -> new GeckoHttpConnection(url, runtime));
    server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
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
    server.close();
    io.shutdownNow();
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
      h.put(":path", path);
      seen.add(h);
      String cookie = h.getOrDefault("cookie", "");
      switch (path) {
        case "/login":
          respond(c, 200, "Set-Cookie: sid=LIVE_GECKO; Path=/\r\n", "ok".getBytes());
          return;
        case "/slow":
          Thread.sleep(30_000);
          respond(c, 200, "", "late".getBytes());
          return;
        case "/hop":
          respond(c, 302, "Location: " + base + "/media\r\n", new byte[0]);
          return;
        case "/captured-only":
          // Only the captured request context (not Gecko's jar) can open this resource.
          if (!cookie.contains("cap=CAPTURED")) {
            respond(c, 403, "", new byte[0]);
            return;
          }
          break;
        case "/media":
          if (!cookie.contains("sid=LIVE_GECKO")) {
            respond(c, 403, "", new byte[0]);
            return;
          }
          break;
        default:
          respond(c, 404, "", new byte[0]);
          return;
      }
      String range = h.get("range");
      if (range != null) {
        String[] r = range.replace("bytes=", "").split("-");
        int from = Integer.parseInt(r[0]),
            to = r.length > 1 && !r[1].isEmpty() ? Integer.parseInt(r[1]) : media.length - 1;
        respond(
            c,
            206,
            "Content-Range: bytes " + from + "-" + to + "/" + media.length + "\r\n",
            Arrays.copyOfRange(media, from, to + 1));
      } else respond(c, 200, "", media);
    } catch (Exception ignored) {
    }
  }

  static void respond(Socket c, int code, String headers, byte[] body) throws IOException {
    OutputStream out = c.getOutputStream();
    out.write(
        ("HTTP/1.1 "
                + code
                + " X\r\nContent-Type: video/mp2t\r\nContent-Length: "
                + body.length
                + "\r\nConnection: close\r\n"
                + headers
                + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  void login() throws Exception {
    HttpURLConnection login = HttpConnections.open(base + "/login", true);
    assertEquals(200, login.getResponseCode());
    login.disconnect();
  }

  @Test
  public void geckoCookieStoreIsUsedAndStaleReplayedCookiesAreNeverSent() throws Exception {
    login();
    HttpURLConnection c = HttpConnections.open(base + "/media", true);
    c.setRequestProperty("Cookie", "sid=STALE_SNAPSHOT");
    c.setRequestProperty("Range", "bytes=100-199");
    assertEquals(206, c.getResponseCode());
    assertEquals("bytes 100-199/" + media.length, c.getHeaderField("Content-Range"));
    byte[] body = c.getInputStream().readAllBytes();
    assertArrayEquals(Arrays.copyOfRange(media, 100, 200), body);
    c.disconnect();
    Map<String, String> request = seen.get(seen.size() - 1);
    assertTrue(request.get("cookie").contains("sid=LIVE_GECKO"));
    assertFalse(request.get("cookie").contains("STALE_SNAPSHOT"));
  }

  @Test
  public void redirectsAreReturnedToTheCallerForValidation() throws Exception {
    HttpURLConnection c = HttpConnections.open(base + "/hop", true);
    assertEquals(302, c.getResponseCode());
    assertEquals(base + "/media", c.getHeaderField("Location"));
    c.disconnect();
  }

  @Test
  public void disconnectUnblocksAPendingRequestQuickly() throws Exception {
    HttpURLConnection c = HttpConnections.open(base + "/slow", true);
    Executors.newSingleThreadScheduledExecutor().schedule(c::disconnect, 500, TimeUnit.MILLISECONDS);
    long started = System.currentTimeMillis();
    try {
      c.getResponseCode();
      fail("A disconnected request must not complete");
    } catch (IOException expected) {
    }
    assertTrue(System.currentTimeMillis() - started < 10_000);
  }

  @Test
  public void segmentStreamUsesBrowserTransportWithoutAnyCapturedContext() throws Exception {
    login();
    File out = new File(context.getCacheDir(), "gecko-segment");
    try (HttpSource source = new HttpSource(base + "/media", Map.of(), new TransferControl())) {
      source.stream(base + "/media", 0, -1, out, null);
    }
    assertArrayEquals(media, java.nio.file.Files.readAllBytes(out.toPath()));
    out.delete();
  }

  @Test
  public void directTransportWithCapturedContextRemainsTheFallback() throws Exception {
    File out = new File(context.getCacheDir(), "captured-segment");
    try (HttpSource source =
        new HttpSource(
            base + "/captured-only", Map.of("Cookie", "cap=CAPTURED"), new TransferControl())) {
      source.stream(base + "/captured-only", 0, -1, out, null);
    }
    assertArrayEquals(media, java.nio.file.Files.readAllBytes(out.toPath()));
    out.delete();
  }
}
