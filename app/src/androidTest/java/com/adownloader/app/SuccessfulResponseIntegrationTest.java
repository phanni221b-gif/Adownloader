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
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real Gecko response filtering plus a tiny test-only fMP4 MSE player. No mocked provider. */
@RunWith(AndroidJUnit4.class)
public class SuccessfulResponseIntegrationTest {
  final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
  final Context context = ins.getTargetContext();
  ServerSocket server;
  ExecutorService io;
  String base, mode;
  Activity activity;
  final Map<String, AtomicInteger> first = new ConcurrentHashMap<>();
  final AtomicInteger nativeManifests = new AtomicInteger(),
      fetchDenied = new AtomicInteger(),
      nativeSegments = new AtomicInteger(),
      segmentDenied = new AtomicInteger();
  volatile boolean played;
  volatile String playerFailure = "NONE";
  final AtomicInteger browserSegments = new AtomicInteger();
  final String rootAuth = "Bearer SYNTHETIC_ROOT", segmentAuth = "Bearer SYNTHETIC_SEGMENT";
  final CountDownLatch transferStarted = new CountDownLatch(1);

  @Before
  public void start() throws Exception {
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
    context.getSharedPreferences("device_diagnostics", 0).edit().remove("network").apply();
  }

  @After
  public void stop() throws Exception {
    if (activity != null) ins.runOnMainSync(activity::finish);
    server.close();
    io.shutdownNow();
  }

  byte[] asset(String name) throws Exception {
    try (InputStream in = ins.getContext().getAssets().open("fixtures/" + name)) {
      return in.readAllBytes();
    }
  }

  String target() {
    return mode.equals("dash")
        ? "/m/manifest.mpd"
        : mode.equals("master") ? "/m/master.m3u8" : "/m/playlist.m3u8";
  }

  String html() {
    return """
<!doctype html><meta charset='utf-8'><title>Successful response fixture</title>
<video id='v' muted autoplay controls></video><button onclick='v.play();watch()'>Play fixture</button><script>
const root='@ROOT@',rootAuth='@AUTH@',segmentAuth='@SEGMENT@';
let phase='START';const ms=new MediaSource();v.src=URL.createObjectURL(ms);v.muted=true;function watch(){const timer=setInterval(()=>{if(v.currentTime>0.1){clearInterval(timer);fetch('/played');}},50);}
const load=async(url,auth)=>{const r=await fetch(url,{headers:{Authorization:auth}});if(!r.ok)throw Error('fixture response');return r;};
const buffer=async(mime,parts,u)=>{phase='SOURCE_BUFFER';const sb=ms.addSourceBuffer(mime);const append=bytes=>new Promise((resolve,reject)=>{sb.addEventListener('updateend',resolve,{once:true});sb.addEventListener('error',reject,{once:true});sb.appendBuffer(bytes);});for(const part of parts){phase='SEGMENT_APPEND';await append(await(await load(new URL(part,u),segmentAuth)).arrayBuffer());}};
ms.addEventListener('sourceopen',async()=>{try{
  phase='ROOT';let u=new URL(root,location),text=await(await load(u,rootAuth)).text();
  if(root.endsWith('.mpd')){
    const doc=new DOMParser().parseFromString(text,'application/xml');
    for(const rep of doc.querySelectorAll('Representation')){
      const parent=rep.parentElement,t=rep.querySelector('SegmentTemplate')||parent.querySelector('SegmentTemplate');
      const id=rep.getAttribute('id'),mime=rep.getAttribute('mimeType')||parent.getAttribute('mimeType'),codec=rep.getAttribute('codecs')||parent.getAttribute('codecs');
      let count=0;for(const span of t.querySelectorAll('S'))count+=1+Number(span.getAttribute('r')||0);
      const start=Number(t.getAttribute('startNumber')||1),expand=(value,n)=>value.split('$RepresentationID$').join(id).split('$Number%05d$').join(String(n).padStart(5,'0')).split('$Number$').join(String(n));
      const parts=[expand(t.getAttribute('initialization'),start)];for(let n=start;n<start+count;n++)parts.push(expand(t.getAttribute('media'),n));
      await buffer(mime+'; codecs="'+codec+'"',parts,u);
    }
  }else{
    if(text.includes('#EXT-X-STREAM-INF')){const lines=text.trim().split(/\\r?\\n/).filter(x=>x&&!x.startsWith('#'));u=new URL(lines.at(-1),u);text=await(await load(u,rootAuth)).text();}
    const init=/#EXT-X-MAP:URI="([^"]+)"/.exec(text)[1],parts=text.split(/\\r?\\n/).filter(x=>x&&!x.startsWith('#'));
    await buffer('video/mp4; codecs="avc1.42E01E,mp4a.40.2"',[init,...parts],u);
  }
  phase='END_STREAM';ms.endOfStream();phase='PLAY';watch();try{await v.play();}catch(e){fetch('/player-error?phase=PLAY&name='+encodeURIComponent(e.name));}
}catch(e){fetch('/player-error?phase='+phase+'&name='+encodeURIComponent(e.name||'UNKNOWN'));}});
</script>
"""
        .replace("@ROOT@", target())
        .replace("@AUTH@", rootAuth)
        .replace("@SEGMENT@", segmentAuth);
  }

  void respond(Socket s, int code, String mime, byte[] bytes) throws Exception {
    s.getOutputStream()
        .write(
            ("HTTP/1.1 "
                    + code
                    + " OK\r\nContent-Type: "
                    + mime
                    + "\r\nCache-Control: no-store\r\nContent-Length: "
                    + bytes.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    s.getOutputStream().write(bytes);
  }

  void serve(Socket socket) {
    try (Socket s = socket) {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
      String line = in.readLine();
      if (line == null) return;
      String path = line.split(" ")[1];
      Map<String, String> h = new HashMap<>();
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        int at = line.indexOf(':');
        if (at > 0)
          h.put(line.substring(0, at).toLowerCase(Locale.ROOT), line.substring(at + 1).trim());
      }
      if (path.equals("/watch")) {
        respond(s, 200, "text/html", html().getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.equals("/other")) {
        respond(s, 200, "text/html", "<title>Other page</title>".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.startsWith("/player-error")) {
        playerFailure = path.substring("/player-error".length());
        respond(s, 200, "text/plain", new byte[] {1});
        return;
      }
      if (path.equals("/played")) {
        played = true;
        respond(s, 200, "text/plain", new byte[] {1});
        return;
      }
      boolean browser = "cors".equals(h.get("sec-fetch-mode")),
          manifest = path.endsWith(".m3u8") || path.endsWith(".mpd");
      if (manifest) {
        if (!browser) nativeManifests.incrementAndGet();
        boolean once =
            browser
                && rootAuth.equals(h.get("authorization"))
                && first.computeIfAbsent(path, k -> new AtomicInteger()).getAndIncrement() == 0;
        if (!once) {
          if (browser) fetchDenied.incrementAndGet();
          respond(
              s,
              403,
              "text/html",
              "<html>request replay blocked</html>".getBytes(StandardCharsets.UTF_8));
          return;
        }
        byte[] bytes =
            path.endsWith(".mpd")
                ? asset("manifest.mpd")
                : path.endsWith("master.m3u8")
                    ? ("#EXTM3U\n"
                            + "#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=64x64,FRAME-RATE=12\n"
                            + "low.m3u8\n"
                            + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=160x90,FRAME-RATE=24\n"
                            + "1080.m3u8\n")
                        .getBytes(StandardCharsets.UTF_8)
                    : asset("fmp4/playlist.m3u8");
        respond(
            s,
            200,
            path.endsWith(".mpd") ? "application/dash+xml" : "application/vnd.apple.mpegurl",
            bytes);
        return;
      }
      if (path.startsWith("/m/") && (path.endsWith(".m4s") || path.endsWith(".mp4"))) {
        boolean ok =
            segmentAuth.equals(h.get("authorization")) && (!mode.equals("browserOnly") || browser);
        if (browser) browserSegments.incrementAndGet();
        if (!browser) {
          nativeSegments.incrementAndGet();
          if (!ok) segmentDenied.incrementAndGet();
          else {
            transferStarted.countDown();
            if (mode.equals("closeBrowser")) Thread.sleep(700);
          }
        }
        respond(
            s,
            ok ? 200 : 403,
            ok ? "video/mp4" : "text/html",
            ok
                ? asset(
                    (path.contains("-stream") ? "" : "fmp4/")
                        + path.substring(path.lastIndexOf('/') + 1))
                : "<html>segment blocked</html>".getBytes(StandardCharsets.UTF_8));
        return;
      }
      respond(s, 404, "text/plain", new byte[0]);
    } catch (Exception ignored) {
    }
  }

  BrowserSessionManager browser() throws Exception {
    var f = MainActivity.class.getDeclaredField("browser");
    f.setAccessible(true);
    return (BrowserSessionManager) f.get(activity);
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
    var browser = browser();
    long deadline = System.currentTimeMillis() + 40000;
    boolean tapped = false;
    while (System.currentTimeMillis() < deadline) {
      if (!tapped && System.currentTimeMillis() > deadline - 35000) {
        var button =
            androidx.test.uiautomator.UiDevice.getInstance(ins)
                .findObject(androidx.test.uiautomator.By.text("Play fixture"));
        if (button != null) {
          button.click();
          tapped = true;
        }
      }
      for (var c : browser.getDetector().snapshot())
        if (c.getUrl().equals(base + target()) && played)
          return browser.getDetector().forDownload(c);
      Thread.sleep(100);
    }
    fail(
        "Real Gecko MSE playback must advance before capture/download assertions; played="
            + played
            + " playerFailure="
            + playerFailure
            + " browserSegments="
            + browserSegments.get()
            + " candidates="
            + browser.getDetector().snapshot().size());
    return null;
  }

  void run(String kind, boolean staleContext, boolean blocked, boolean closeBrowser)
      throws Exception {
    mode = kind;
    MediaCandidate item = discover();
    assertTrue(played);
    // Independently prove both replay paths fail, without consuming the successful response cache.
    try (HttpSource nativeReplay = new HttpSource(item.getUrl(), item.getHeaders())) {
      try {
        nativeReplay.document(item.getUrl());
        fail("Native manifest replay must fail");
      } catch (DownloadError e) {
        assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
      }
    }
    var provider = MediaNetworkSession.provider();
    assertNotNull(provider);
    int tab = Integer.parseInt(item.getMetadata().get("browserTabId")),
        frame = Integer.parseInt(item.getMetadata().get("frameId"));
    try {
      provider.manifest(
          item.getUrl(), item.getPageUrl(), tab, frame, item.getHeaders(), new TransferControl());
      fail("content.fetch replay must fail");
    } catch (DownloadError e) {
      assertEquals(DownloadError.Category.ACCESS_BLOCKED, e.category);
    }
    assertTrue(nativeManifests.get() > 0);
    assertTrue(fetchDenied.get() > 0);
    var probe = MediaProbeService.shared().preview(item);
    assertFalse(probe.tracks().isEmpty());
    if (kind.equals("master")) {
      assertEquals(90, probe.tracks().get(0).height);
      assertEquals(24, probe.tracks().get(0).frameRate, 0);
      assertEquals(2, probe.documents.size());
    }
    int rootRequests = nativeManifests.get(), browserReplays = fetchDenied.get();
    if (staleContext) {
      Map<String, String> meta = new LinkedHashMap<>(item.getMetadata());
      meta.remove("requestContexts");
      item =
          new MediaCandidate(
              item.getUrl(),
              item.getType(),
              item.getSource(),
              item.getPageUrl(),
              item.getHeaders(),
              item.getDetectedAt(),
              meta);
    }
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
    if (closeBrowser) {
      assertTrue(transferStarted.await(20, TimeUnit.SECONDS));
      ins.runOnMainSync(activity::finish);
      activity = null;
    }
    long deadline = System.currentTimeMillis() + 45000;
    DownloadJobStore.Job result = null;
    while (System.currentTimeMillis() < deadline) {
      result = jobs.store.get(id.get());
      if (Set.of(
              DownloadJobStore.State.COMPLETED,
              DownloadJobStore.State.FAILED,
              DownloadJobStore.State.NEEDS_REFRESH)
          .contains(result.state)) break;
      Thread.sleep(100);
    }
    assertEquals(rootRequests, nativeManifests.get());
    assertEquals(browserReplays, fetchDenied.get());
    assertTrue(nativeSegments.get() > 0);
    String diagnostics =
        context.getSharedPreferences("device_diagnostics", 0).getString("network", "");
    assertTrue(diagnostics.contains("GECKO_RESPONSE_CACHE"));
    assertFalse(diagnostics.contains(rootAuth));
    assertFalse(diagnostics.contains(segmentAuth));
    assertFalse(diagnostics.contains("/m/playlist"));
    if (blocked) {
      assertEquals("ACCESS_BLOCKED", result.error);
      assertTrue(diagnostics.contains("SEGMENT_NATIVE_BLOCKED"));
      jobs.action(id.get(), "CANCEL", null);
    } else {
      assertEquals("category=" + result.error, DownloadJobStore.State.COMPLETED, result.state);
      try (var fd = context.getContentResolver().openFileDescriptor(Uri.parse(result.uri), "r")) {
        var tracks = PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
        assertTrue(tracks.video && tracks.audio);
      }
      context.getContentResolver().delete(Uri.parse(result.uri), null, null);
    }
    if (staleContext && !blocked)
      assertTrue(
          "An old click snapshot must retry with the exact recent segment observation",
          segmentDenied.get() > 0);
  }

  @Test
  public void oneTimeActualPlaybackResponseWorksWhenNativeAndContentFetchBothFail()
      throws Exception {
    run("oneTime", false, false, false);
  }

  @Test
  public void masterAndVariantBothUseOriginalSuccessfulResponses() throws Exception {
    run("master", false, false, false);
  }

  @Test
  public void blockedSegmentRefreshesExactSuccessfulMediaRequestContext() throws Exception {
    run("segmentContext", true, false, false);
  }

  @Test
  public void browserOnlySegmentsRemainPreciselyDiagnosedWithoutSegmentIpc() throws Exception {
    run("browserOnly", false, true, false);
  }

  @Test
  public void nativeTransferContinuesAfterBrowserActivityCloses() throws Exception {
    run("closeBrowser", false, false, true);
  }

  @Test
  public void actualDashPlaybackResponseUsesNativeSplitTrackTransfer() throws Exception {
    run("dash", false, false, false);
  }
}
