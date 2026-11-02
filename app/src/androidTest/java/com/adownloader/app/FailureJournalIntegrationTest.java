package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.browser.DetectionFailureWatchdog;
import com.adownloader.app.diagnostics.FailureJournal;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import com.adownloader.app.media.MediaDetector;
import com.adownloader.app.ui.FailureJournalUi;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FailureJournalIntegrationTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private FailureJournal journal;
  private DownloadJobStore store;
  private ServerSocket server;
  private ExecutorService serverIo;
  private volatile boolean direct;
  private final List<Uri> published = new ArrayList<>();
  private static final String[] SECRETS = {
    "SUPER_SECRET_COOKIE_123",
    "Bearer VERY_SECRET_TOKEN",
    "SIGNED_QUERY_SECRET",
    "CUSTOM_PLAYBACK_SECRET_789"
  };

  @Before
  public void setup() throws Exception {
    journal = FailureJournal.get(context);
    journal.install();
    journal.clear().get(10, TimeUnit.SECONDS);
    context.deleteDatabase("failure-privacy-jobs.db");
    store = new DownloadJobStore(context, "failure-privacy-jobs.db");
    server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    serverIo = Executors.newCachedThreadPool();
    serverIo.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket socket = server.accept();
              serverIo.execute(() -> serve(socket));
            } catch (IOException ignored) {
            }
        });
  }

  private void serve(Socket socket) {
    try (Socket s = socket) {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
      String line = in.readLine();
      if (line == null) return;
      while ((line = in.readLine()) != null && !line.isEmpty()) {}
      byte[] body;
      if (direct) {
        try (InputStream a =
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("fixtures/direct.mp4")) {
          body = a.readAllBytes();
        }
      } else body = "<html>Cloudflare challenge</html>".getBytes(StandardCharsets.UTF_8);
      s.getOutputStream()
          .write(
              ("HTTP/1.1 "
                      + (direct ? "200 OK" : "403 Forbidden")
                      + "\r\nContent-Type: "
                      + (direct ? "video/mp4" : "text/html")
                      + "\r\nContent-Length: "
                      + body.length
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      s.getOutputStream().write(body);
    } catch (Exception ignored) {
    }
  }

  @After
  public void close() throws Exception {
    server.close();
    serverIo.shutdownNow();
    for (var j : store.list()) {
      if (!j.uri.isEmpty()) published.add(Uri.parse(j.uri));
      store.state(j.id, DownloadJobStore.State.CANCELED, "");
      store.cleanup(j.id);
    }
    store.close();
    context.deleteDatabase("failure-privacy-jobs.db");
    for (Uri u : published) context.getContentResolver().delete(u, null, null);
    journal.clear().get(10, TimeUnit.SECONDS);
  }

  private long job(String type) throws Exception {
    String url =
        "http://127.0.0.1:"
            + server.getLocalPort()
            + "/media."
            + (type.equals("HLS") ? "m3u8" : "mp4")
            + "?token="
            + SECRETS[2];
    return store.enqueue(
        UUID.randomUUID().toString(),
        type,
        "Synthetic privacy fixture",
        "127.0.0.1",
        new JSONObject()
            .put("url", url)
            .put("page", "https://page.test/watch?session=" + SECRETS[2])
            .put(
                "headers",
                new JSONObject()
                    .put("Cookie", "session=" + SECRETS[0])
                    .put("Authorization", SECRETS[1])
                    .put("X-Playback-Token", SECRETS[3]))
            .put(
                "metadata",
                new JSONObject()
                    .put("detectionSource", "response-signature")
                    .put("title", "Fixture")));
  }

  private JSONObject runFailure() throws Exception {
    long id = job("HLS");
    new JobRunner(context, store).run(store.claim(), new TransferControl());
    assertEquals(DownloadJobStore.State.NEEDS_REFRESH, store.get(id).state);
    journal.barrier().get(10, TimeUnit.SECONDS);
    JSONObject report = journal.latestAsync(id).get(10, TimeUnit.SECONDS);
    assertNotNull(report);
    assertEquals(id, report.getLong("jobId"));
    return report;
  }

  @Test
  public void actualRoot403CreatesLinkedPrivateReportAndReadableJsonFileProviderExport()
      throws Exception {
    JSONObject report = runFailure();
    assertTrue(report.getString("failureId").matches("F-[a-f0-9-]{36}"));
    JSONObject failure = report.getJSONObject("failure");
    assertEquals("ROOT_MANIFEST", failure.getString("stage"));
    assertEquals("ACCESS_BLOCKED", failure.getString("category"));
    assertEquals(403, failure.getInt("httpStatus"));
    assertTrue(report.getJSONArray("eventHistory").length() > 1);
    String text = report.toString();
    assertTrue(text.contains("cookie"));
    assertTrue(text.contains("authorization"));
    assertTrue(text.contains("x-playback-token"));
    for (String secret : SECRETS) assertFalse(text.contains(secret));
    File file = journal.exportAsync(report.getLong("jobId")).get(10, TimeUnit.SECONDS);
    Intent intent = FailureJournalUi.intent(context, file);
    Uri uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
    assertEquals("content", uri.getScheme());
    assertEquals(context.getPackageName() + ".diagnostics", uri.getAuthority());
    assertEquals("application/json", intent.getType());
    assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      String exported = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(report.getString("failureId"), new JSONObject(exported).getString("failureId"));
      for (String secret : SECRETS) assertFalse(exported.contains(secret));
    }
    File evidence = new File("/sdcard/Download/Adownloader-ui-proof/failure-journal-example.json");
    evidence.getParentFile().mkdirs();
    Files.copy(file.toPath(), evidence.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertNoSecrets(context.getDataDir());
    File outside = new File(context.getCacheDir(), "not-a-diagnostic.txt");
    Files.writeString(outside.toPath(), "fixture");
    try {
      FailureJournalUi.intent(context, outside);
      fail("Provider must not expose the entire cache");
    } catch (IllegalArgumentException expected) {
    } finally {
      outside.delete();
    }
  }

  @Test
  public void aggregatesCountRepeatedFailuresButKeepIndividualEventsAndDistinctStages()
      throws Exception {
    for (int i = 0; i < 3; i++) {
      var s =
          new FailureDiagnostics.Session(
              88, "HLS", "https://cdn.test/root?token=PRIVATE", "https://page.test/", Map.of());
      s.phase(FailureDiagnostics.Stage.ROOT_MANIFEST);
      s.fail(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE");
      s.complete();
    }
    journal.barrier().get(10, TimeUnit.SECONDS);
    assertEquals(3, (int) journal.countAsync().get());
    JSONObject first = journal.latestAsync(88).get();
    assertEquals(3, first.getJSONObject("aggregate").getInt("occurrenceCount"));
    var s =
        new FailureDiagnostics.Session(
            88, "HLS", "https://cdn.test/root", "https://page.test/", Map.of());
    s.phase(FailureDiagnostics.Stage.VIDEO_SEGMENT);
    s.fail(null, DownloadError.Category.ACCESS_BLOCKED, "DOWNLOAD_FAILURE");
    journal.barrier().get(10, TimeUnit.SECONDS);
    JSONObject second = journal.latestAsync(88).get();
    assertNotEquals(first.getString("fingerprint"), second.getString("fingerprint"));
    assertEquals(1, second.getJSONObject("aggregate").getInt("occurrenceCount"));
  }

  @Test
  public void retentionIsBoundedAndTerminalCleanupDeletesOnlyThatJobsReports() throws Exception {
    for (int i = 0; i < 106; i++) {
      var s =
          new FailureDiagnostics.Session(
              1000 + i, "HLS", "https://cdn.test/root", "https://page.test/", Map.of());
      for (int n = 0; n < 55; n++)
        s.event(Map.of("action", "PHASE", "stage", "VIDEO_SEGMENT", "segmentIndex", n));
      s.fail(null, DownloadError.Category.NETWORK, "DOWNLOAD_FAILURE");
      s.complete();
    }
    journal.barrier().get(30, TimeUnit.SECONDS);
    assertEquals(100, (int) journal.countAsync().get());
    assertNull(journal.latestAsync(1000).get());
    assertEquals(50, journal.latestAsync(1105).get().getJSONArray("eventHistory").length());
    JSONObject actual = runFailure();
    long id = actual.getLong("jobId");
    File exported = journal.exportAsync(id).get();
    store.state(id, DownloadJobStore.State.CANCELED, "");
    store.clearFailedOrCanceled(id);
    journal.barrier().get(10, TimeUnit.SECONDS);
    assertNull(journal.latestAsync(id).get());
    assertNotNull(journal.latestAsync(1105).get());
    journal.clear().get(10, TimeUnit.SECONDS);
    assertFalse(exported.exists());
    assertEquals(0, (int) journal.countAsync().get());
  }

  @Test
  public void normalCompletionCreatesNoFailureReportAndClearingDiagnosticsKeepsPublishedMedia()
      throws Exception {
    direct = true;
    long id = job("VIDEO");
    new JobRunner(context, store).run(store.claim(), new TransferControl());
    var completed = store.get(id);
    assertEquals(DownloadJobStore.State.COMPLETED, completed.state);
    Uri uri = Uri.parse(completed.uri);
    published.add(uri);
    journal.barrier().get(10, TimeUnit.SECONDS);
    assertEquals(0, (int) journal.countAsync().get());
    var failure = new FailureDiagnostics.Session(99999, "UNKNOWN", "", "", Map.of());
    failure.fail(null, DownloadError.Category.UNKNOWN, "DOWNLOAD_FAILURE");
    journal.barrier().get(10, TimeUnit.SECONDS);
    File exported = journal.exportAsync(99999).get();
    assertTrue(exported.exists());
    journal.clear().get(10, TimeUnit.SECONDS);
    assertFalse(exported.exists());
    assertEquals(DownloadJobStore.State.COMPLETED, store.get(id).state);
    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
      assertTrue(input.read() >= 0);
    }
  }

  @Test
  public void detectionWatchdogRequiresPlaybackOrMseAndDiscardsNavigation() throws Exception {
    var ins = InstrumentationRegistry.getInstrumentation();
    MediaDetector detector = new MediaDetector(null);
    Handler handler = new Handler(Looper.getMainLooper());
    DetectionFailureWatchdog monitor =
        new DetectionFailureWatchdog(context, handler, id -> detector, 120);
    ins.runOnMainSync(
        () -> {
          monitor.page(1, "https://page.test/a");
          monitor.metrics(1, new JSONObject());
        });
    Thread.sleep(250);
    journal.barrier().get(10, TimeUnit.SECONDS);
    assertEquals(0, (int) journal.countAsync().get());
    JSONObject mse = new JSONObject().put("blobPlayers", 1);
    ins.runOnMainSync(
        () -> {
          monitor.metrics(1, mse);
          monitor.page(1, "https://page.test/b");
        });
    Thread.sleep(250);
    journal.barrier().get(10, TimeUnit.SECONDS);
    assertEquals(0, (int) journal.countAsync().get());
    JSONObject progress = new JSONObject().put("playbackObserved", 1);
    ins.runOnMainSync(() -> monitor.metrics(1, progress));
    Thread.sleep(300);
    journal.barrier().get(10, TimeUnit.SECONDS);
    JSONObject failure = journal.latestAsync(0).get();
    assertEquals("DETECTION", failure.getJSONObject("failure").getString("stage"));
    assertEquals(
        "PLAYBACK_WITHOUT_CANDIDATE", failure.getJSONObject("failure").getString("reasonCode"));
    assertTrue(failure.getJSONObject("media").getBoolean("geckoPlaybackObserved"));
    ins.runOnMainSync(monitor::close);
  }

  @Test
  public void settingsShareButtonLaunchesAJsonChooserAndDeviceDiagnosticsButtonIsConnected()
      throws Exception {
    JSONObject report = runFailure();
    var ins = InstrumentationRegistry.getInstrumentation();
    java.util.concurrent.atomic.AtomicReference<Intent> captured =
        new java.util.concurrent.atomic.AtomicReference<>();
    CountDownLatch chosen = new CountDownLatch(1);
    android.app.Instrumentation.ActivityMonitor monitor =
        new android.app.Instrumentation.ActivityMonitor() {
          @Override
          public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
              captured.set(intent);
              chosen.countDown();
              return new android.app.Instrumentation.ActivityResult(
                  android.app.Activity.RESULT_CANCELED, new Intent());
            }
            return null;
          }
        };
    ins.addMonitor(monitor);
    MainActivity activity = null;
    try {
      activity =
          (MainActivity)
              ins.startActivitySync(
                  new Intent(context, MainActivity.class)
                      .setData(Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/watch"))
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      MainActivity screen = activity;
      ins.runOnMainSync(
          () -> {
            ((com.google.android.material.bottomnavigation.BottomNavigationView)
                    screen.findViewById(R.id.main_navigation))
                .setSelectedItemId(R.id.nav_settings);
          });
      ins.waitForIdleSync();
      ins.runOnMainSync(
          () -> {
            var button = screen.findViewById(R.id.setting_failure_share);
            button.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, button.getWidth(), button.getHeight()), true);
            button.performClick();
          });
      assertTrue(chosen.await(10, TimeUnit.SECONDS));
      Intent send = (Intent) captured.get().getParcelableExtra(Intent.EXTRA_INTENT);
      assertEquals(Intent.ACTION_SEND, send.getAction());
      assertEquals("application/json", send.getType());
      Uri uri = (Uri) send.getParcelableExtra(Intent.EXTRA_STREAM);
      try (InputStream input = context.getContentResolver().openInputStream(uri)) {
        JSONObject shared =
            new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(report.getString("failureId"), shared.getString("failureId"));
      }
      File proof = new File("/sdcard/Download/Adownloader-ui-proof/failure-journal-settings.png");
      proof.getParentFile().mkdirs();
      androidx.test.uiautomator.UiDevice.getInstance(ins).takeScreenshot(proof);
      ins.runOnMainSync(() -> screen.findViewById(R.id.setting_diagnostics).performClick());
      assertNotNull(
          androidx.test.uiautomator.UiDevice.getInstance(ins)
              .wait(
                  androidx.test.uiautomator.Until.findObject(
                      androidx.test.uiautomator.By.res(context.getPackageName(), "sheet_title")
                          .text("기기 진단 정보")),
                  3000));
    } finally {
      if (activity != null) {
        MainActivity screen = activity;
        ins.runOnMainSync(screen::finish);
      }
      ins.removeMonitor(monitor);
    }
  }

  private void assertNoSecrets(File file) throws Exception {
    if (Files.isSymbolicLink(file.toPath())) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) assertNoSecrets(child);
      return;
    }
    if (!file.isFile()) return;
    byte[] previous = new byte[0], buffer = new byte[65536];
    try (InputStream in = new FileInputStream(file)) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        byte[] block = new byte[previous.length + n];
        System.arraycopy(previous, 0, block, 0, previous.length);
        System.arraycopy(buffer, 0, block, previous.length, n);
        String text = new String(block, StandardCharsets.ISO_8859_1);
        for (String secret : SECRETS)
          assertFalse(
              "Plaintext synthetic secret in private application storage", text.contains(secret));
        previous = Arrays.copyOfRange(block, Math.max(0, block.length - 128), block.length);
      }
    }
  }
}
