package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.adownloader.app.browser.*;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import com.adownloader.app.media.*;
import com.adownloader.app.ui.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HlsHttpIntegrationTest {
  private final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
  private final Context context = ins.getTargetContext();
  private ServerSocket server;
  private ExecutorService network;
  private String base;
  private Activity activity;
  private final AtomicInteger denied = new AtomicInteger(), redirects = new AtomicInteger();
  private final AtomicBoolean intact = new AtomicBoolean();
  private final List<String> deniedReasons = new CopyOnWriteArrayList<>();
  private final List<String> captureLookups = new CopyOnWriteArrayList<>();
  private MediaNetworkSession.Provider tracedProvider;

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort();
    network = Executors.newCachedThreadPool();
    network.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket s = server.accept();
              network.execute(() -> serve(s));
            } catch (IOException ignored) {
            }
        });
  }

  @After
  public void stop() throws Exception {
    if (activity != null) ins.runOnMainSync(activity::finish);
    server.close();
    network.shutdownNow();
  }

  private byte[] asset(String name) throws Exception {
    try (InputStream in = ins.getContext().getAssets().open("fixtures/" + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toByteArray();
    }
  }

  private byte[] manifest(String kind) throws Exception {
    String raw =
        new String(
            asset(kind.equals("fmp4") ? "fmp4/playlist.m3u8" : "playlist.m3u8"),
            StandardCharsets.UTF_8);
    String map = "";
    for (String l : raw.split("\n"))
      if (l.startsWith("#EXT-X-MAP:"))
        map = l.replace("init.mp4", "../parts/init.mp4?kind=" + kind);
    StringBuilder out = new StringBuilder("\ufeff");
    for (String l : raw.split("\n")) {
      if (l.startsWith("#EXT-X-MAP:")) l = map;
      else if (!l.isBlank() && !l.startsWith("#")) {
        if (!map.isEmpty()) out.append(map).append("\r\n");
        l = "../parts/" + l + "?kind=" + kind;
      }
      out.append(l).append("\r\n");
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void serve(Socket client) {
    try (Socket c = client) {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
      String request = in.readLine();
      if (request == null) return;
      String target = request.split(" ")[1],
          path = target.split("\\?", 2)[0],
          kind =
              target.contains("kind=fmp4") ? "fmp4" : target.contains("kind=dash") ? "dash" : "ts",
          line;
      Map<String, String> h = new HashMap<>();
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        int at = line.indexOf(':');
        if (at > 0)
          h.put(line.substring(0, at).toLowerCase(Locale.ROOT), line.substring(at + 1).trim());
      }
      String cookie = h.getOrDefault("cookie", ""), mime = "text/plain", extra = "";
      byte[] body;
      int status = 200;
      if (path.equals("/watch")) {
        mime = "text/html; charset=utf-8";
        extra = "Set-Cookie: initial=PRIVATE_HLS_CONTEXT; Path=/; HttpOnly; SameSite=Lax\r\n";
        body =
            ("<!doctype html><meta charset='utf-8'><title>Loading post</title><iframe"
                    + " src='/frame?kind="
                    + kind
                    + "'></iframe><script>setTimeout(()=>{let"
                    + " m=document.createElement('meta');m.setAttribute('property','og:title');m.content='Actual"
                    + " post title';document.head.append(m);},5000);</script>")
                .getBytes(StandardCharsets.UTF_8);
      } else if (path.equals("/frame")) {
        mime = "text/html";
        body =
            ("<!doctype html><title>Advertisement iframe title must never win</title><video"
                 + " muted></video><script>let v=document.querySelector('video');try{let m=new"
                 + " MediaSource();v.src=URL.createObjectURL(m);m.addEventListener('sourceopen',()=>{try{m.addSourceBuffer('video/mp4;"
                 + " codecs=\\\"avc1.42C00A,mp4a.40.2\\\"');}catch(e){}});}catch(e){}fetch('/media/start?kind="
                    + kind
                    + "').then(r=>r.arrayBuffer()).then(b=>{let t=new"
                    + " TextDecoder().decode(b);if(!t.startsWith('#EXTM3U'))throw Error('stream"
                    + " changed');return fetch('/media/child/variant?kind="
                    + kind
                    + "')}).then(r=>r.text()).then(t=>{let p=t.replace(/^\\uFEFF/,'').split(/\\r"
                    + "?\\n"
                    + "/).find(l=>l&&!l.startsWith('#'));return fetch(new"
                    + " URL(p,location.origin+'/media/child/variant'),{referrer:location.origin+'/segment-player'})}).then(r=>r.arrayBuffer()).then(()=>fetch('/intact'));</script>")
                .getBytes(StandardCharsets.UTF_8);
        if (kind.equals("dash"))
          body =
              ("<!doctype html><title>Frame title</title><video muted></video><script>let"
                   + " v=document.querySelector('video');try{v.src=URL.createObjectURL(new"
                   + " MediaSource());}catch(e){}fetch('/api/playback').then(r=>r.text()).then(t=>{if(!t.includes('<MPD'))throw"
                   + " Error('changed');return fetch('/intact');});</script>")
                  .getBytes(StandardCharsets.UTF_8);
      } else if (path.equals("/api/playback")) {
        mime = "application/octet-stream";
        extra = "Set-Cookie: dash=PRIVATE_DASH_CONTEXT; Path=/api/; HttpOnly; SameSite=Lax\r\n";
        body = asset("manifest.mpd");
      } else if (path.startsWith("/api/")) {
        if (!cookie.contains("dash=PRIVATE_DASH_CONTEXT")) {
          status = 403;
          body = new byte[0];
          deniedReasons.add("DASH_COOKIE");
          denied.incrementAndGet();
        } else {
          body = asset(path.substring(path.lastIndexOf('/') + 1));
          mime = "video/mp4";
        }
      } else if (path.equals("/media/start")) {
        redirects.incrementAndGet();
        status = 302;
        extra =
            "Location: /media/master?kind="
                + kind
                + "\r\n"
                + "Set-Cookie: edge=PRIVATE_EDGE_CONTEXT; Path=/media/; HttpOnly; SameSite=Lax\r\n";
        body = new byte[0];
      } else if (path.equals("/media/master")) {
        if (!cookie.contains("initial=PRIVATE_HLS_CONTEXT")
            || !cookie.contains("edge=PRIVATE_EDGE_CONTEXT")) {
          mime = "text/html";
          body = "<html>challenge access denied</html>".getBytes();
          deniedReasons.add(
              "ROOT_COOKIE initial="
                  + cookie.contains("initial=PRIVATE_HLS_CONTEXT")
                  + " edge="
                  + cookie.contains("edge=PRIVATE_EDGE_CONTEXT"));
          denied.incrementAndGet();
        } else {
          extra =
              "Set-Cookie: child=PRIVATE_CHILD_CONTEXT; Path=/media/child/; HttpOnly;"
                  + " SameSite=Lax\r\n";
          body =
              ("#EXTM3U\n"
                      + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=160x90,FRAME-RATE=24\n"
                      + "# generated comment\n"
                      + "child/variant?kind="
                      + kind
                      + "\n")
                  .getBytes();
        }
      } else if (path.equals("/media/child/variant")) {
        if (!cookie.contains("child=PRIVATE_CHILD_CONTEXT")) {
          status = 403;
          body = new byte[0];
          deniedReasons.add("VARIANT_COOKIE");
          denied.incrementAndGet();
        } else {
          mime = "application/octet-stream";
          extra =
              "Set-Cookie: seg=PRIVATE_SEGMENT_CONTEXT; Path=/media/parts/; HttpOnly;"
                  + " SameSite=Lax\r\n";
          body = manifest(kind);
        }
      } else if (path.startsWith("/media/parts/")) {
        if (!cookie.contains("seg=PRIVATE_SEGMENT_CONTEXT")
            || !h.getOrDefault("referer", "").equals(base + "/segment-player")) {
          status = 403;
          body = new byte[0];
          deniedReasons.add(
              "SEGMENT_CONTEXT cookie="
                  + cookie.contains("seg=PRIVATE_SEGMENT_CONTEXT")
                  + " referrer="
                  + h.getOrDefault("referer", "").equals(base + "/segment-player"));
          denied.incrementAndGet();
        } else {
          String name = path.substring(path.lastIndexOf('/') + 1);
          body = asset((kind.equals("fmp4") ? "fmp4/" : "") + name);
          mime = name.endsWith(".ts") ? "video/mp2t" : "video/mp4";
        }
      } else if (path.equals("/intact")) {
        intact.set(true);
        body = "OK".getBytes();
      } else if (path.equals("/challenge")) {
        mime = "text/html";
        body = "<!DOCTYPE html><html>challenge access denied</html>".getBytes();
      } else {
        status = 404;
        body = new byte[0];
      }
      c.getOutputStream()
          .write(
              ("HTTP/1.1 "
                      + status
                      + " OK\r\nContent-Type: "
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

  private BrowserSessionManager browser() throws Exception {
    var field = MainActivity.class.getDeclaredField("browser");
    field.setAccessible(true);
    return (BrowserSessionManager) field.get(activity);
  }

  private MediaCandidate discover(String kind, boolean openEarly) throws Exception {
    ins.getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    activity =
        ins.startActivitySync(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(base + "/watch?kind=" + kind))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    long end = System.currentTimeMillis() + 25000;
    MediaCandidate found = null;
    boolean shown = false;
    while (System.currentTimeMillis() < end) {
      MediaDetector detector = browser().getDetector();
      found =
          detector.snapshot().stream()
              .filter(
                  x ->
                      x.getType()
                              == (kind.equals("dash")
                                  ? MediaCandidate.MediaType.DASH
                                  : MediaCandidate.MediaType.HLS)
                          && x.getUrl()
                              .contains(kind.equals("dash") ? "/api/playback" : "/media/master"))
              .findFirst()
              .orElse(null);
      if (found != null && openEarly && !shown) {
        MediaNetworkSession.Provider actual = MediaNetworkSession.provider();
        assertNotNull(actual);
        tracedProvider =
            new MediaNetworkSession.Provider() {
              public MediaNetworkSession.Observation latest(String u, String p) {
                return actual.latest(u, p);
              }

              public MediaNetworkSession.Observation latest(String u, String p, int t, long f) {
                return actual.latest(u, p, t, f);
              }

              public Map<String, Object> captureDiagnostics() {
                return actual.captureDiagnostics();
              }

              public MediaNetworkSession.Bootstrap captured(
                  String u, String p, int t, long f, TransferControl c) throws Exception {
                var hit = actual.captured(u, p, t, f, c);
                captureLookups.add(
                    URI.create(u).getPath() + " frame=" + f + " hit=" + (hit != null));
                return hit;
              }

              public MediaNetworkSession.Bootstrap manifest(
                  String u, String p, Map<String, String> h, TransferControl c) throws Exception {
                return actual.manifest(u, p, h, c);
              }

              public MediaNetworkSession.Bootstrap manifest(
                  String u, String p, int t, long f, Map<String, String> h, TransferControl c)
                  throws Exception {
                return actual.manifest(u, p, t, f, h, c);
              }
            };
        MediaNetworkSession.install(tracedProvider);
        ins.runOnMainSync(() -> activity.findViewById(R.id.media_action).performClick());
        shown = true;
      }
      if (found != null
          && intact.get()
          && "Actual post title".equals(detector.forDownload(found).getMetadata().get("title")))
        break;
      Thread.sleep(100);
    }
    assertNotNull("MSE + extensionless text/plain manifest must become HLS", found);
    assertTrue("Filtered response must reach the page unchanged", intact.get());
    MediaCandidate current = browser().getDetector().forDownload(found);
    assertEquals("Actual post title", current.getMetadata().get("title"));
    if (!kind.equals("dash")) {
      JSONObject contexts = new JSONObject(current.getMetadata().get("requestContexts"));
      boolean part = false;
      for (Iterator<String> keys = contexts.keys(); keys.hasNext(); )
        part |= URI.create(keys.next()).getPath().startsWith("/media/parts/");
      assertTrue("Actual segment context must be retained after JSON decoding", part);
    }
    assertTrue(
        browser().getDetector().snapshot().stream()
            .noneMatch(x -> x.getUrl().matches(".*[.](ts|m4s|cmfv|cmfa)(?:[?].*)?$")));
    return current;
  }

  private void download(String kind, boolean openEarly) throws Exception {
    MediaCandidate candidate = discover(kind, openEarly);
    if (openEarly) {
      assertTrue(
          UiDevice.getInstance(ins).wait(Until.hasObject(By.text("Actual post title")), 5000));
      ins.runOnMainSync(() -> UiSheets.close(activity));
    }
    DownloadJobs jobs = DownloadJobs.get(context);
    CountDownLatch ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    AtomicLong id = new AtomicLong();
    CountDownLatch saved = new CountDownLatch(1);
    jobs.enqueue(
        candidate,
        value -> {
          id.set(value);
          saved.countDown();
        });
    assertTrue(saved.await(10, TimeUnit.SECONDS));
    assertTrue(id.get() > 0);
    long end = System.currentTimeMillis() + 30000;
    DownloadJobStore.Job result = null;
    while (System.currentTimeMillis() < end) {
      result = jobs.store.get(id.get());
      if (result.state == DownloadJobStore.State.COMPLETED
          || result.state == DownloadJobStore.State.FAILED
          || result.state == DownloadJobStore.State.NEEDS_REFRESH) break;
      Thread.sleep(100);
    }
    assertEquals(
        "Job must complete; category=" + result.error,
        DownloadJobStore.State.COMPLETED,
        result.state);
    assertTrue(result.name.startsWith("Actual post title"));
    try (ParcelFileDescriptor fd =
        context.getContentResolver().openFileDescriptor(Uri.parse(result.uri), "r")) {
      PostProcessor.Inspection inspection =
          PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
      assertTrue(inspection.video && inspection.audio);
    }
    assertEquals(
        "No unauthenticated preview or transfer requests: "
            + deniedReasons
            + "; evidence="
            + captureLookups,
        0,
        denied.get());
    for (File db : context.getDatabasePath("download-jobs.db").getParentFile().listFiles())
      if (db.isFile()) {
        String raw = new String(Files.readAllBytes(db.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("PRIVATE_HLS_CONTEXT"));
        assertFalse(raw.contains("PRIVATE_SEGMENT_CONTEXT"));
      }
    context.getContentResolver().delete(Uri.parse(result.uri), null, null);
  }

  @Test
  public void mseExtensionlessTsUsesJobCookiesScopedSegmentRefererAndLiveSheetTitle()
      throws Exception {
    download("ts", true);
  }

  @Test
  public void mseExtensionlessFmp4RepeatedMapAndByteExactStreamComplete() throws Exception {
    download("fmp4", false);
  }

  @Test
  public void htmlChallengeHasSanitizedSpecificDiagnostic() throws Exception {
    try (HttpSource source = new HttpSource(base + "/challenge", Map.of())) {
      try {
        MediaProbeService.shared()
            .forTransfer(
                source,
                base + "/challenge",
                MediaCandidate.MediaType.HLS,
                Map.of(),
                new TransferControl());
        fail();
      } catch (DownloadError e) {
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
        assertEquals(HlsDiagnostic.Stage.ROOT_BODY, e.diagnostic.stage);
        String safe = e.diagnostic.safeFields().toString();
        assertFalse(safe.contains("/challenge"));
        assertFalse(safe.contains("Cookie"));
      }
    }
  }

  @Test
  public void mseExtensionlessDashSignaturePlansBothTracksAndCompletes() throws Exception {
    download("dash", false);
  }
}
