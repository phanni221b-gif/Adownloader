package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.adownloader.app.browser.BrowserSessionManager;
import com.adownloader.app.diagnostics.FailureJournal;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Synthetic player responses only. No real site URLs, keys or decryption. */
@RunWith(AndroidJUnit4.class)
public class TabsAndTransportIntegrationTest {
  private final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
  private final Context context = ins.getTargetContext();
  private MainActivity activity;
  private ServerSocket server;
  private ExecutorService io;
  private String base;
  private volatile boolean encrypted;
  private final AtomicInteger mediaRequests = new AtomicInteger();
  private final AtomicBoolean intact = new AtomicBoolean();
  private final java.util.List<Uri> published = new java.util.ArrayList<>();

  @Before
  public void setup() throws Exception {
    ins.getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort();
    io = Executors.newCachedThreadPool();
    io.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket c = server.accept();
              io.execute(() -> serve(c));
            } catch (IOException stopped) {
            }
        });
    FailureJournal.get(context).clear().get(10, TimeUnit.SECONDS);
  }

  @After
  public void cleanup() throws Exception {
    if (activity != null) ins.runOnMainSync(activity::finish);
    server.close();
    io.shutdownNow();
    for (Uri u : published) context.getContentResolver().delete(u, null, null);
    FailureJournal.get(context).clear().get(10, TimeUnit.SECONDS);
  }

  private byte[] asset(String name) throws Exception {
    try (InputStream in = ins.getContext().getAssets().open("fixtures/" + name)) {
      return in.readAllBytes();
    }
  }

  private void serve(Socket socket) {
    try (Socket c = socket) {
      var input =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
      String first = input.readLine();
      if (first == null) return;
      String target = first.split(" ")[1], path = target.split("\\?", 2)[0], line;
      while ((line = input.readLine()) != null && !line.isEmpty()) {}
      String mime = "text/html; charset=utf-8", extra = "";
      byte[] bytes;
      if (path.equals("/watch"))
        bytes =
            ("<title>Opaque player fixture</title><video muted></video><div"
                 + " data-video-url='/opaque'></div><script>setTimeout(()=>fetch('/opaque',{headers:{'X-Playback-Session':'PRIVATE_FIXTURE_SESSION'}}).then(r=>r.arrayBuffer()).then(b=>{if("
                    + (encrypted
                        ? "new Uint8Array(b)[0]===69"
                        : "new TextDecoder().decode(b).startsWith('#EXTM3U')")
                    + ")fetch('/intact');}),600);</script>")
                .getBytes(StandardCharsets.UTF_8);
      else if (path.equals("/opaque")) {
        mediaRequests.incrementAndGet();
        mime = "application/octet-stream";
        if (encrypted) {
          extra = "X-Relay-Envelope: emr2\r\n";
          bytes =
              "EMR2synthetic-opaque-bytes-not-a-real-encrypted-video"
                  .getBytes(StandardCharsets.UTF_8);
        } else bytes = asset("playlist.m3u8");
      } else if (path.endsWith(".ts")) {
        mime = "video/mp2t";
        bytes = asset(path.substring(1));
      } else if (path.equals("/intact")) {
        intact.set(true);
        bytes = "OK".getBytes(StandardCharsets.US_ASCII);
      } else
        bytes =
            ("<title>"
                    + (target.contains("Two") ? "Two" : target.contains("Three") ? "Three" : "One")
                    + "</title><h1>Synthetic tab</h1>")
                .getBytes(StandardCharsets.UTF_8);
      c.getOutputStream()
          .write(
              ("HTTP/1.1 200 OK\r\nContent-Type: "
                      + mime
                      + "\r\n"
                      + extra
                      + "Cache-Control: no-store\r\nContent-Length: "
                      + bytes.length
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      c.getOutputStream().write(bytes);
    } catch (Exception ignored) {
    }
  }

  private BrowserSessionManager browser() throws Exception {
    var field = MainActivity.class.getDeclaredField("browser");
    field.setAccessible(true);
    return (BrowserSessionManager) field.get(activity);
  }

  private void open(String path) {
    activity =
        (MainActivity)
            ins.startActivitySync(
                new Intent(context, MainActivity.class)
                    .setData(Uri.parse(base + path))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
  }

  private void title(String expected) throws Exception {
    long end = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < end) {
      var manager = browser();
      if (manager.tabs().stream()
          .anyMatch(t -> t.session == manager.getSession() && expected.equals(t.title))) return;
      Thread.sleep(100);
    }
    fail("Expected active tab title " + expected);
  }

  private MediaCandidate candidate(MediaCandidate.MediaType type) throws Exception {
    long end = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < end) {
      var found =
          browser().getDetector().snapshot().stream().filter(c -> c.getType() == type).findFirst();
      if (found.isPresent() && intact.get())
        return browser().getDetector().forDownload(found.get());
      Thread.sleep(100);
    }
    fail("Expected scoped media recognition " + type);
    return null;
  }

  private void screenshot(String name) throws Exception {
    File file = new File("/sdcard/Download/Adownloader-ui-proof/" + name);
    file.getParentFile().mkdirs();
    Bitmap bitmap = ins.getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    try (var out = new FileOutputStream(file)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      bitmap.recycle();
    }
  }

  @Test
  public void hintedExtensionlessPlainHlsIsCapturedAndDownloaded() throws Exception {
    open("/watch");
    var media = candidate(MediaCandidate.MediaType.HLS);
    assertTrue(intact.get());
    assertTrue(media.getUrl().endsWith("/opaque"));
    DownloadJobs jobs = DownloadJobs.get(context);
    CountDownLatch ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    AtomicLong id = new AtomicLong(-1);
    CountDownLatch saved = new CountDownLatch(1);
    jobs.enqueue(
        media,
        v -> {
          id.set(v);
          saved.countDown();
        });
    assertTrue(saved.await(15, TimeUnit.SECONDS));
    assertTrue(id.get() > 0);
    long end = System.currentTimeMillis() + 30000;
    DownloadJobStore.Job job = null;
    while (System.currentTimeMillis() < end) {
      job = jobs.store.get(id.get());
      if (job.state == DownloadJobStore.State.COMPLETED
          || job.state == DownloadJobStore.State.FAILED
          || job.state == DownloadJobStore.State.NEEDS_REFRESH) break;
      Thread.sleep(100);
    }
    assertEquals("error=" + job.error, DownloadJobStore.State.COMPLETED, job.state);
    published.add(Uri.parse(job.uri));
    assertEquals("Do not replay a captured hinted manifest", 1, mediaRequests.get());
  }

  @Test
  public void encryptedEnvelopeShowsUnsupportedWithoutReplayOrJobAndProducesSafeReport()
      throws Exception {
    encrypted = true;
    open("/watch");
    var media = candidate(MediaCandidate.MediaType.PROTECTED);
    assertTrue(intact.get());
    assertTrue(media.getHeaders().isEmpty());
    ins.runOnMainSync(() -> activity.findViewById(R.id.media_action).performClick());
    var device = UiDevice.getInstance(ins);
    assertTrue(device.wait(Until.hasObject(By.text("보호된 전송")), 5000));
    var download = device.findObject(By.res(context.getPackageName(), "media_download"));
    assertNotNull(download);
    assertFalse(download.isEnabled());
    screenshot("unsupported-transport.png");
    DownloadJobs jobs = DownloadJobs.get(context);
    CountDownLatch ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    int before = jobs.store.list().size();
    AtomicLong id = new AtomicLong(99);
    CountDownLatch saved = new CountDownLatch(1);
    jobs.enqueue(
        media,
        v -> {
          id.set(v);
          saved.countDown();
        });
    assertTrue(saved.await(10, TimeUnit.SECONDS));
    assertEquals(-1, id.get());
    assertEquals(before, jobs.store.list().size());
    assertEquals(1, mediaRequests.get());
    FailureJournal journal = FailureJournal.get(context);
    journal.barrier().get(10, TimeUnit.SECONDS);
    var report = journal.latestAsync(0).get(10, TimeUnit.SECONDS);
    assertNotNull(report);
    assertEquals(
        "ENCRYPTED_TRANSPORT_UNSUPPORTED", report.getJSONObject("failure").getString("reasonCode"));
    assertFalse(report.toString().contains("PRIVATE_FIXTURE_SESSION"));
    assertFalse(report.toString().contains("/opaque"));
    File proof =
        new File("/sdcard/Download/Adownloader-ui-proof/unsupported-transport-example.json");
    Files.copy(
        journal.exportAsync(0).get().toPath(), proof.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  public void toolbarTabCounterCardsSwitchAndCloseBackgroundActiveAndLastTab() throws Exception {
    open("/tab?name=One");
    title("One");
    var manager = browser();
    ins.runOnMainSync(() -> assertTrue(manager.newTab(base + "/tab?name=Two")));
    title("Two");
    ins.runOnMainSync(() -> assertTrue(manager.newTab(base + "/tab?name=Three")));
    title("Three");
    ins.runOnMainSync(
        () -> {
          TextView counter = activity.findViewById(R.id.browser_tabs);
          assertEquals("3", counter.getText().toString());
          assertTrue(counter.getLeft() >= activity.findViewById(R.id.browser_refresh).getRight());
          assertTrue(counter.getRight() <= activity.findViewById(R.id.browser_menu).getLeft());
          counter.performClick();
        });
    var device = UiDevice.getInstance(ins);
    assertTrue(device.wait(Until.hasObject(By.text("열린 탭 3")), 5000));
    screenshot("tab-overview.png");
    device.findObject(By.desc("Two 탭 닫기")).click();
    assertTrue(device.wait(Until.hasObject(By.text("열린 탭 2")), 3000));
    assertEquals(2, manager.tabs().size());
    title("Three");
    device.findObject(By.text("One")).click();
    title("One");
    ins.runOnMainSync(() -> activity.findViewById(R.id.browser_tabs).performClick());
    assertTrue(device.wait(Until.hasObject(By.text("열린 탭 2")), 3000));
    device.findObject(By.desc("One 탭 닫기")).click();
    title("Three");
    assertTrue(device.wait(Until.hasObject(By.text("열린 탭 1")), 3000));
    device.findObject(By.desc("Three 탭 닫기")).click();
    long end = System.currentTimeMillis() + 3000;
    while (!"about:blank".equals(manager.tabs().get(0).url) && System.currentTimeMillis() < end)
      Thread.sleep(100);
    assertEquals(1, manager.tabs().size());
    assertEquals("about:blank", manager.tabs().get(0).url);
    ins.runOnMainSync(
        () ->
            assertEquals(
                "1", ((TextView) activity.findViewById(R.id.browser_tabs)).getText().toString()));
    for (var tab : manager.tabs())
      if (tab.preview != null)
        assertTrue(tab.preview.getWidth() <= 240 && tab.preview.getHeight() <= 160);
    assertFalse(manager.snapshotTabs(true).toString().contains("preview"));
  }
}
