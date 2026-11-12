package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Exercises actual Android extractors/muxer and the bundled Gecko native bridge. */
@RunWith(AndroidJUnit4.class)
public class DownloadIntegrationTest {
  private ServerSocket server;
  private ExecutorService workers;
  private String base;

  @Before
  public void start() throws Exception {
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort() + "/";
    workers = Executors.newCachedThreadPool();
    workers.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket client = server.accept();
              workers.execute(() -> serve(client));
            } catch (IOException ignored) {
            }
        });
  }

  @After
  public void stop() throws Exception {
    server.close();
    workers.shutdownNow();
  }

  private byte[] fixture(String name) throws IOException {
    try (InputStream in =
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("fixtures/" + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return out.toByteArray();
    }
  }

  private void serve(Socket socket) {
    try (Socket client = socket) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
      String first = reader.readLine();
      if (first == null) return;
      String path = first.split(" ")[1].substring(1).split("\\?")[0];
      String line, range = null;
      while ((line = reader.readLine()) != null && !line.isEmpty())
        if (line.toLowerCase(Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
      byte[] body;
      String mime;
      if (path.endsWith(".html")) {
        body =
            ("<!doctype html><meta charset='utf-8'><meta name='viewport'"
                 + " content='width=device-width,initial-scale=1'><title>Adownloader"
                 + " test</title><style>:root{color-scheme:light dark}body{font:16px"
                 + " sans-serif;padding:24px;margin:0;background:light-dark(#fafafb,#151518);color:light-dark(#202027,#f0eff3)}video{width:100%;border-radius:14px;margin:16px"
                 + " 0}p{line-height:1.6;color:#777}h1{font-size:24px}a{color:#bf246f;display:block;margin:16px"
                 + " 0}</style><h1>미디어 테스트</h1><p>원본 그대로 저장하고, 좋아하는 플레이어로 감상하세요.</p><video"
                 + " preload='auto' controls src='"
                    + base
                    + "direct.mp4'></video><p>Adownloader UI 검증용 합성 영상입니다.</p><a"
                    + " href='second.html'>다음 페이지</a><a target='_blank' href='popup.html'>새 창"
                    + " 테스트</a>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mime = "text/html";
      } else if (path.equals("encrypted/master.m3u8")) {
        body =
            ("#EXTM3U\n"
                 + "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://test\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"
                 + "#EXT-X-STREAM-INF:BANDWIDTH=200000\n"
                 + "playlist.m3u8\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mime = "application/vnd.apple.mpegurl";
      } else if (path.equals("encrypted/playlist.m3u8")) {
        String playlist =
            new String(fixture("playlist.m3u8"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:7")
                .replaceFirst(
                    "(?m)^#EXTINF:",
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"first.key\",IV=0x2a\n#EXTINF:")
                .replaceFirst(
                    "(?m)^(playlist0\\.ts\\r?\\n)",
                    "$1#EXT-X-KEY:METHOD=AES-128,URI=\"next.key\"\n");
        body = playlist.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mime = "application/vnd.apple.mpegurl";
      } else if (path.equals("encrypted/first.key") || path.equals("encrypted/next.key")) {
        body = aesKey(path.endsWith("first.key"));
        mime = "application/octet-stream";
      } else if (path.matches("encrypted/playlist[0-9]+\\.ts")) {
        String name = path.substring("encrypted/".length());
        int segment = Integer.parseInt(name.substring("playlist".length(), name.length() - 3));
        byte[] iv =
            java.nio.ByteBuffer.allocate(16).putLong(8, segment == 0 ? 42 : 7 + segment).array();
        var cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            new javax.crypto.spec.SecretKeySpec(aesKey(segment == 0), "AES"),
            new javax.crypto.spec.IvParameterSpec(iv));
        body = cipher.doFinal(fixture(name));
        mime = "video/mp2t";
      } else {
        body = fixture(path);
        mime =
            path.endsWith("mp4")
                ? "video/mp4"
                : path.endsWith("m3u8")
                    ? "application/vnd.apple.mpegurl"
                    : path.endsWith("mpd") ? "application/dash+xml" : "application/octet-stream";
      }
      if (path.endsWith(".ts") && !path.startsWith("stress/")) Thread.sleep(100);
      int start = 0, end = body.length - 1;
      if (range != null) {
        String[] bounds = range.replace("bytes=", "").split("-");
        start = Integer.parseInt(bounds[0]);
        if (bounds.length > 1) end = Math.min(end, Integer.parseInt(bounds[1]));
      }
      String header =
          "HTTP/1.1 "
              + (range == null ? "200 OK" : "206 Partial Content")
              + "\r\nContent-Type: "
              + mime
              + "\r\nContent-Length: "
              + (end - start + 1)
              + "\r\nAccept-Ranges: bytes\r\n"
              + (range == null
                  ? ""
                  : "Content-Range: bytes " + start + "-" + end + "/" + body.length + "\r\n")
              + "Connection: close\r\n\r\n";
      OutputStream output = client.getOutputStream();
      output.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      output.write(body, start, end - start + 1);
      output.flush();
    } catch (Exception ignored) {
    }
  }

  private static byte[] aesKey(boolean first) {
    byte[] key = new byte[16];
    Arrays.fill(key, (byte) (first ? 0x23 : 0x56));
    return key;
  }

  private DownloadEngine engine() {
    return new DownloadEngine(
        InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
        (text, percent) -> {});
  }

  @Test
  public void directMp4IsBitExact() throws Exception {
    DownloadEngine engine = engine();
    try {
      DownloadEngine.Result result =
          engine.download(
              base + "direct.mp4", MediaCandidate.MediaType.VIDEO, Collections.emptyMap());
      byte[] saved;
      try (InputStream in = new FileInputStream(result.file);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        saved = out.toByteArray();
      }
      assertArrayEquals(
          MessageDigest.getInstance("SHA-256").digest(fixture("direct.mp4")),
          MessageDigest.getInstance("SHA-256").digest(saved));
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void hlsTsRemuxHasBothTracksAndSeeks() throws Exception {
    DownloadEngine engine = engine();
    try {
      DownloadEngine.Result result =
          engine.download(
              base + "playlist.m3u8", MediaCandidate.MediaType.HLS, Collections.emptyMap());
      PostProcessor.inspect(result.file, 3, true, true);
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void hlsAes128DownloadsWithRotatedKeyAndBothIvModes() throws Exception {
    DownloadEngine engine = engine();
    try {
      var result =
          engine.download(base + "encrypted/master.m3u8", MediaCandidate.MediaType.HLS, Map.of());
      var tracks = PostProcessor.inspect(result.file, 3, true, true);
      assertTrue(tracks.video && tracks.audio);
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void hlsDiscontinuityJoinsTimelinesLosslessly() throws Exception {
    // Before v0.7 any #EXT-X-DISCONTINUITY failed the whole download.
    DownloadEngine engine = engine();
    try {
      DownloadEngine.Result result =
          engine.download(
              base + "disc/joined.m3u8", MediaCandidate.MediaType.HLS, Collections.emptyMap());
      // 3 s + 2 s: the second clip's restarted timestamps are shifted, not overlapped.
      PostProcessor.Inspection inspection = PostProcessor.inspect(result.file, 5, true, true);
      assertTrue(inspection.durationUs > 4_500_000);
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void hlsDiscontinuityWithDifferentFormatFailsClearly() throws Exception {
    DownloadEngine engine = engine();
    try {
      engine.download(
          base + "disc/mismatch.m3u8", MediaCandidate.MediaType.HLS, Collections.emptyMap());
      fail("A resolution change must not be stitched into one track");
    } catch (DownloadError expected) {
      assertEquals(DownloadError.Category.HLS_DISCONTINUITY, expected.category);
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void dashSeparateTracksMuxAndSeek() throws Exception {
    DownloadEngine engine = engine();
    try {
      DownloadEngine.Result result =
          engine.download(
              base + "manifest.mpd", MediaCandidate.MediaType.DASH, Collections.emptyMap());
      PostProcessor.inspect(result.file, 3, true, true);
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void hlsExternalAudioIsIncluded() throws Exception {
    DownloadEngine engine = engine();
    try {
      DownloadEngine.Result result =
          engine.download(
              base + "master.m3u8", MediaCandidate.MediaType.HLS, Collections.emptyMap());
      PostProcessor.inspect(result.file, 3, true, true);
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void queuedForegroundDownloadsFinishAfterBrowserCloses() throws Exception {
    android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    android.content.SharedPreferences prefs = context.getSharedPreferences("downloads", 0);
    prefs.edit().clear().commit();
    com.adownloader.app.jobs.DownloadJobs jobs = com.adownloader.app.jobs.DownloadJobs.get(context);
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, java.util.concurrent.TimeUnit.SECONDS));
    Set<Long> existing = new HashSet<>();
    for (var job : jobs.store.list()) existing.add(job.id);
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(base + "index.html"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    for (int i = 0; i < 2; i++)
      context.startForegroundService(
          new Intent(context, DownloadService.class)
              .setAction(DownloadService.ACTION_DOWNLOAD)
              .putExtra(DownloadService.EXTRA_URL, base + "playlist.m3u8")
              .putExtra(DownloadService.EXTRA_TYPE, "HLS"));
    instrumentation.runOnMainSync(activity::finish);
    long deadline = System.currentTimeMillis() + 60_000;
    int complete = 0;
    while (System.currentTimeMillis() < deadline) {
      complete = 0;
      for (var job : jobs.store.list())
        if (!existing.contains(job.id)
            && job.state == com.adownloader.app.jobs.DownloadJobStore.State.COMPLETED) complete++;
      if (complete == 2) break;
      Thread.sleep(250);
    }
    assertEquals("Both queued foreground jobs must finish after Activity closure", 2, complete);
    assertFalse("SQLite must be the only download-state authority", prefs.contains("items"));
    int created = 0;
    for (var job : jobs.store.list())
      if (!existing.contains(job.id)) {
        created++;
        assertEquals(com.adownloader.app.jobs.DownloadJobStore.State.COMPLETED, job.state);
        assertNotNull(job.uri);
        context.getContentResolver().delete(Uri.parse(job.uri), null, null);
      }
    assertEquals(2, created);
  }

  @Test
  public void geckoLaunchAndNativeMediaBridge() throws Exception {
    android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(base + "index.html"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      long deadline = System.currentTimeMillis() + 60_000;
      boolean[] found = {false};
      while (System.currentTimeMillis() < deadline && !found[0]) {
        instrumentation.runOnMainSync(
            () -> found[0] = hasCandidate(activity.getWindow().getDecorView()));
        Thread.sleep(250);
      }
      assertTrue("Gecko WebExtension must detect the actual media response", found[0]);
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  private boolean hasCandidate(View view) {
    if (view instanceof TextView && view.getId() == R.id.media_action)
      return ((TextView) view).getText().toString().matches("[1-9][0-9]*");
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (hasCandidate(((ViewGroup) view).getChildAt(i))) return true;
    return false;
  }
}
