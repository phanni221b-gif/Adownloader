package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.TransferControl;
import com.adownloader.app.playback.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Real GeckoView verification of the live playback diagnostic in a download-permitted HLS test
 * environment (a local server with synthetic fixtures): the in-page video is sampled, the page's
 * media requests are recorded with their real outcome (a 200 segment and a 403 challenge segment),
 * and the download-side probe verifies a normal HLS stream end to end and stops at the first
 * refusal of an access-restricted one.
 */
@RunWith(AndroidJUnit4.class)
public class PlaybackDiagnosticsIntegrationTest {
  private final Instrumentation app = InstrumentationRegistry.getInstrumentation();
  private final Context context = app.getTargetContext();
  private ServerSocket server;
  private ExecutorService network;
  private MainActivity activity;
  private int port;
  private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

  @Before
  public void start() throws Exception {
    app.getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    server = new ServerSocket(0, 32, InetAddress.getByName("0.0.0.0"));
    port = server.getLocalPort();
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
  }

  private byte[] fixture(String name) throws IOException {
    try (InputStream in = app.getContext().getAssets().open("fixtures/" + name)) {
      return in.readAllBytes();
    }
  }

  private void serve(Socket socket) {
    try (Socket client = socket) {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
      String first = in.readLine();
      if (first == null) return;
      String path = first.split(" ")[1];
      while ((first = in.readLine()) != null && !first.isEmpty()) {}
      hits.computeIfAbsent(path.replaceAll("\\d+\\.ts$", "N.ts"), k -> new AtomicInteger()).incrementAndGet();
      String status = "200 OK", type = "text/html; charset=utf-8";
      byte[] bytes;
      if (path.equals("/direct.mp4")) {
        type = "video/mp4";
        bytes = fixture("direct.mp4");
      } else if (path.startsWith("/open/") || path.startsWith("/locked/")) {
        String name = path.substring(path.indexOf('/', 1) + 1);
        if (name.endsWith(".m3u8")) {
          type = "application/vnd.apple.mpegurl";
          bytes = fixture(name);
        } else if (path.startsWith("/locked/")) {
          status = "403 Forbidden";
          bytes = "<!DOCTYPE html><html><title>Just a moment...</title><body>challenge</body></html>".getBytes(StandardCharsets.UTF_8);
        } else {
          type = "video/mp2t";
          bytes = fixture(name);
        }
      } else if (path.startsWith("/seg/ok")) {
        type = "video/mp2t";
        bytes = fixture("playlist0.ts");
      } else if (path.startsWith("/seg/blocked")) {
        status = "403 Forbidden";
        bytes = "<html><title>Just a moment...</title>challenge</html>".getBytes(StandardCharsets.UTF_8);
      } else {
        // The diagnostic activates on the extension's next poll, so the page's own media
        // requests start a few seconds after load and repeat; the video loops.
        String blocked = "http://127.0.0.2:" + port + "/seg/blocked0.ts";
        bytes =
            ("<title>diag</title><video id=v src='/direct.mp4' muted autoplay loop playsinline></video><script>"
                    + "const tick=()=>{fetch('/seg/ok0.ts?token=SECRET').then(r=>r.arrayBuffer()).catch(()=>{});"
                    + "fetch('" + blocked + "').catch(()=>{});"
                    + "const v=document.getElementById('v');if(v.paused)v.play().catch(()=>{});};"
                    + "setInterval(tick,2000);</script>")
                .getBytes(StandardCharsets.UTF_8);
      }
      client.getOutputStream().write(
          ("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                  + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().write(bytes);
    } catch (Exception ignored) {
    }
  }

  @After
  public void stop() throws Exception {
    PlaybackDiagnostics.discard();
    if (activity != null) app.runOnMainSync(() -> activity.finish());
    server.close();
    network.shutdownNow();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void livePlaybackAndPlayerRequestsAreRecordedOnRealGecko() throws Exception {
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    activity =
        (MainActivity)
            app.startActivitySync(
                new Intent(context, MainActivity.class)
                    .setData(Uri.parse("http://127.0.0.1:" + port + "/watch"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Map<String, Object> report = Map.of();
    boolean ok = false, blocked = false, sampled = false;
    long end = System.currentTimeMillis() + 90000;
    while (System.currentTimeMillis() < end && !(ok && blocked && sampled)) {
      Thread.sleep(1000);
      report = session.report(null);
      Map<String, Object> net = (Map<String, Object>) report.get("playerNetwork");
      for (Object h : (List<Object>) net.get("hosts")) {
        Map<String, Object> host = (Map<String, Object>) h;
        Map<String, Object> statuses = (Map<String, Object>) host.get("statuses");
        if ("127.0.0.1".equals(host.get("host")) && statuses != null && statuses.containsKey("200")) ok = true;
        if ("127.0.0.2".equals(host.get("host")) && statuses != null && statuses.containsKey("403")) blocked = true;
      }
      Map<String, Object> playback = (Map<String, Object>) report.get("playback");
      for (Object e : (List<Object>) playback.get("elements")) {
        Map<String, Object> el = (Map<String, Object>) e;
        if (((Number) el.get("samples")).intValue() >= 3 && ((Number) el.get("maxReadyState")).intValue() >= 1)
          sampled = true;
      }
    }
    String json = Json.write(report);
    assertTrue("a successful player media request is recorded (status 200): " + json, ok);
    assertTrue("a refused player media request is recorded with its status (403): " + json, blocked);
    assertTrue("the in-page video element is sampled on real GeckoView: " + json, sampled);
    Map<String, Object> el =
        (Map<String, Object>) ((List<Object>) ((Map<String, Object>) report.get("playback")).get("elements")).get(0);
    assertEquals("progressive", el.get("path"));
    assertTrue("the looping muted video plays on real GeckoView: " + json,
        ((Number) el.get("progressSeconds")).doubleValue() > 0);
    assertFalse("no query or credential reaches the report", json.contains("SECRET") || json.contains("://"));
  }

  private MediaCandidate candidate(String root) {
    return new MediaCandidate(
        "http://127.0.0.1:" + port + "/" + root + "/playlist.m3u8", MediaCandidate.MediaType.HLS,
        "dom-media", "http://127.0.0.1:" + port + "/watch", Map.of(), 0, Map.of());
  }

  @Test
  public void downloadProbeVerifiesANormalStreamAndStopsAtARefusal() throws Exception {
    // The app installs the real browser transport (GeckoWebExecutor) when it starts.
    activity =
        (MainActivity)
            app.startActivitySync(
                new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    PlaybackProbe normal =
        PlaybackProbe.run(candidate("open"), session.salt, new TransferControl(), PlaybackProbe::defaultSource);
    assertEquals("completed", normal.stoppedReason);
    assertEquals(HlsInspection.Protection.CLEAR, normal.root.protection);
    long segmentsOk = normal.steps.stream().filter(s -> s.stage.equals("VIDEO_SEGMENT") && s.kind.equals("MEDIA_OK")).count();
    assertEquals(2, segmentsOk);
    assertEquals("mpeg-ts", normal.steps.get(1).signature);

    PlaybackProbe locked =
        PlaybackProbe.run(candidate("locked"), session.salt, new TransferControl(), PlaybackProbe::defaultSource);
    assertEquals("segment-challenge", locked.stoppedReason);
    PlaybackProbe.Step last = locked.steps.get(locked.steps.size() - 1);
    assertEquals("HTML_CHALLENGE", last.kind);
    AtomicInteger lockedSegments = hits.get("/locked/playlistN.ts");
    // Only the first segment is attempted (browser stack, then direct stack): the refusal is not
    // repeated for the second segment.
    assertNotNull(lockedSegments);
    assertTrue("segment requests after the refusal: " + lockedSegments.get(), lockedSegments.get() <= 3);
  }
}
