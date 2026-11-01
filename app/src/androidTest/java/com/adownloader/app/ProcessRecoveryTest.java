package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Driven by an external adb harness, so force-stop really destroys the process and its sockets. */
@RunWith(AndroidJUnit4.class)
public class ProcessRecoveryTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private static final String BASE = "http://127.0.0.1:9009";

  private JSONObject get(String path) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
    connection.setReadTimeout(15000);
    try (InputStream in = connection.getInputStream()) {
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      connection.disconnect();
    }
  }

  private DownloadJobs launch() throws Exception {
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    instrumentation.startActivitySync(
        new Intent(context, MainActivity.class)
            .setData(Uri.parse(BASE + "/page"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    DownloadJobs jobs = DownloadJobs.get(context);
    CountDownLatch ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    return jobs;
  }

  private void prepare(String kind, String path, MediaCandidate.MediaType type) throws Exception {
    get("/reset");
    DownloadJobs jobs = launch();
    CountDownLatch ready = new CountDownLatch(1);
    long[] id = {-1};
    instrumentation.runOnMainSync(
        () ->
            jobs.enqueue(
                new MediaCandidate(
                    BASE + path,
                    type,
                    "test",
                    BASE + "/page",
                    Collections.singletonMap("Cookie", "fixture=PRIVATE_REQUEST_CONTEXT"),
                    System.currentTimeMillis()),
                value -> {
                  id[0] = value;
                  ready.countDown();
                }));
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    assertTrue(id[0] > 0);
    context.getSharedPreferences("recovery-test", 0).edit().putLong(kind, id[0]).commit();
    long deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      DownloadJobStore.Job job = jobs.store.get(id[0]);
      boolean committed;
      if (type == MediaCandidate.MediaType.VIDEO) committed = job.bytes >= 1024 * 1024;
      else
        try (android.database.Cursor cursor =
            jobs.store
                .getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM parts WHERE job=?", new String[] {"" + id[0]})) {
          cursor.moveToFirst();
          committed = cursor.getInt(0) >= (type == MediaCandidate.MediaType.DASH ? 2 : 1);
        }
      if (committed) {
        assertNotEquals(DownloadJobStore.State.COMPLETED, job.state);
        return;
      }
      Thread.sleep(25);
    }
    fail("No durable checkpoint before force-stop");
  }

  private void recover(String kind, boolean direct) throws Exception {
    DownloadJobs jobs = launch();
    long id = context.getSharedPreferences("recovery-test", 0).getLong(kind, -1);
    DownloadJobStore.Job recovered = jobs.store.get(id);
    assertNotNull(recovered);
    assertEquals(
        "A dead worker must never appear active", DownloadJobStore.State.PAUSED, recovered.state);
    assertTrue(
        jobs.store
            .request(id)
            .getJSONObject("headers")
            .getString("Cookie")
            .contains("PRIVATE_REQUEST_CONTEXT"));
    String firstPart = null;
    if (!direct) {
      List<ManifestParser.Track> plan =
          PlanCodec.decode(jobs.store.request(id).getJSONArray("plan"));
      firstPart = Uri.parse(plan.get(0).parts.get(kind.equals("dash") ? 1 : 0).url).getPath();
    }
    instrumentation.runOnMainSync(() -> jobs.action(id, "RESUME", null));
    long deadline = System.currentTimeMillis() + 60000;
    DownloadJobStore.Job complete = null;
    while (System.currentTimeMillis() < deadline) {
      DownloadJobStore.Job job = jobs.store.get(id);
      if (job.state == DownloadJobStore.State.COMPLETED) {
        complete = job;
        break;
      }
      if (job.state == DownloadJobStore.State.FAILED
          || job.state == DownloadJobStore.State.NEEDS_REFRESH)
        fail("Recovery failed: " + job.error);
      Thread.sleep(100);
    }
    assertNotNull("Resumed job must finish", complete);
    Uri uri = Uri.parse(complete.uri);
    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
      assertNotNull(fd);
      PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
    }
    JSONObject stats = get("/stats");
    if (direct) {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      try (InputStream in = context.getContentResolver().openInputStream(uri)) {
        byte[] buffer = new byte[128 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
      }
      assertEquals(stats.getString("sha256"), DirectTransfer.hex(hash.digest()));
      boolean range = false;
      JSONArray offsets = stats.getJSONArray("ranges");
      for (int i = 0; i < offsets.length(); i++) range |= offsets.getLong(i) > 0;
      assertTrue("Actual HTTP Range must be used", range);
    } else
      assertEquals(
          "Committed first segment must not be requested twice",
          1,
          stats.getJSONObject("counts").getInt(firstPart));
    context.getContentResolver().delete(uri, null, null);
  }

  @Test
  public void prepareDirect() throws Exception {
    prepare("direct", "/process/direct", MediaCandidate.MediaType.VIDEO);
  }

  @Test
  public void recoverDirect() throws Exception {
    recover("direct", true);
  }

  @Test
  public void prepareHls() throws Exception {
    prepare("hls", "/process/hls/playlist.m3u8", MediaCandidate.MediaType.HLS);
  }

  @Test
  public void recoverHls() throws Exception {
    recover("hls", false);
  }

  @Test
  public void prepareDash() throws Exception {
    prepare("dash", "/process/dash/manifest.mpd", MediaCandidate.MediaType.DASH);
  }

  @Test
  public void recoverDash() throws Exception {
    recover("dash", false);
  }

  private long enqueue(DownloadJobs jobs, String path) throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    long[] id = {-1};
    instrumentation.runOnMainSync(
        () ->
            jobs.enqueue(
                new MediaCandidate(
                    BASE + path,
                    MediaCandidate.MediaType.VIDEO,
                    "test",
                    BASE + "/page",
                    Collections.emptyMap(),
                    System.currentTimeMillis()),
                value -> {
                  id[0] = value;
                  ready.countDown();
                }));
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    assertTrue(id[0] > 0);
    ContentValues name = new ContentValues();
    name.put("name", "Fixture " + id[0]);
    jobs.store.getWritableDatabase().update("jobs", name, "id=?", new String[] {"" + id[0]});
    return id[0];
  }

  private DownloadJobStore.Job await(DownloadJobs jobs, long id, DownloadJobStore.State expected)
      throws Exception {
    long end = System.currentTimeMillis() + 60000;
    while (System.currentTimeMillis() < end) {
      DownloadJobStore.Job job = jobs.store.get(id);
      if (job.state == expected && !jobs.isExecuting(id)) return job;
      if (expected == DownloadJobStore.State.COMPLETED
          && (job.state == DownloadJobStore.State.FAILED
              || job.state == DownloadJobStore.State.NEEDS_REFRESH))
        fail("Unexpected failure: " + job.error);
      Thread.sleep(25);
    }
    throw new AssertionError("State timeout: " + jobs.store.get(id).state);
  }

  private void checkpoint(DownloadJobs jobs, long id) throws Exception {
    long end = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < end) {
      if (jobs.store.get(id).bytes >= 1024 * 1024) return;
      Thread.sleep(25);
    }
    fail("No progress");
  }

  private void action(DownloadJobs jobs, long id, String action) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> jobs.action(id, action, done::countDown));
    assertTrue(done.await(15, TimeUnit.SECONDS));
  }

  @Test
  public void pauseCancelRetry() throws Exception {
    DownloadJobs jobs = launch();
    get("/reset");
    long first = enqueue(jobs, "/process/direct");
    checkpoint(jobs, first);
    action(jobs, first, "PAUSE");
    DownloadJobStore.Job paused = await(jobs, first, DownloadJobStore.State.PAUSED);
    assertTrue(paused.bytes > 0);
    assertTrue(jobs.store.directory(first).listFiles().length > 0);
    action(jobs, first, "RESUME");
    DownloadJobStore.Job complete = await(jobs, first, DownloadJobStore.State.COMPLETED);
    assertEquals("", complete.pending);
    JSONArray ranges = get("/stats").getJSONArray("ranges");
    boolean resumed = false;
    for (int i = 0; i < ranges.length(); i++) resumed |= ranges.getLong(i) > 0;
    assertTrue(resumed);
    context.getContentResolver().delete(Uri.parse(complete.uri), null, null);
    long canceled = enqueue(jobs, "/process/direct");
    checkpoint(jobs, canceled);
    File workspace = jobs.store.directory(canceled);
    android.content.ContentValues values = new android.content.ContentValues();
    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "cancel-pending.mp4");
    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
    Uri pending =
        context
            .getContentResolver()
            .insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    assertNotNull(pending);
    jobs.store.pending(canceled, pending.toString());
    action(jobs, canceled, "CANCEL");
    DownloadJobStore.Job stopped = await(jobs, canceled, DownloadJobStore.State.CANCELED);
    assertEquals("", stopped.pending);
    assertFalse(workspace.exists());
    try (android.database.Cursor cursor =
        context.getContentResolver().query(pending, null, null, null, null)) {
      assertTrue(cursor == null || !cursor.moveToFirst());
    }
    long retry = enqueue(jobs, "/process/fail-once");
    DownloadJobStore.Job failed = await(jobs, retry, DownloadJobStore.State.FAILED);
    assertEquals("SERVER", failed.error);
    action(jobs, retry, "RETRY");
    DownloadJobStore.Job retried = await(jobs, retry, DownloadJobStore.State.COMPLETED);
    context.getContentResolver().delete(Uri.parse(retried.uri), null, null);
    assertEquals(2, get("/stats").getJSONObject("counts").getInt("/process/fail-once"));
    long immediate = enqueue(jobs, "/process/direct");
    checkpoint(jobs, immediate);
    action(jobs, immediate, "PAUSE");
    action(jobs, immediate, "RESUME");
    DownloadJobStore.Job resumedJob = await(jobs, immediate, DownloadJobStore.State.COMPLETED);
    context.getContentResolver().delete(Uri.parse(resumedJob.uri), null, null);
  }

  private void tap(String resource, String text) throws Exception {
    tapJob(-1, resource, text);
  }

  private void tapJob(long job, String resource, String text) throws Exception {
    androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(0);
    androidx.test.uiautomator.UiDevice device =
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation);
    androidx.test.uiautomator.BySelector selector =
        androidx.test.uiautomator.By.res("com.adownloader.app", resource);
    if (text != null) selector = selector.text(text);
    if (job > 0)
      selector =
          selector.hasAncestor(
              androidx.test.uiautomator.By.res("com.adownloader.app", "download_row")
                  .hasDescendant(
                      androidx.test.uiautomator.By.res("com.adownloader.app", "item_title")
                          .text("Fixture " + job)));
    androidx.test.uiautomator.UiObject2 view =
        device.wait(androidx.test.uiautomator.Until.findObject(selector), 10000);
    assertNotNull("Missing actionable UI: " + resource, view);
    android.graphics.Rect previous = null;
    int stable = 0;
    long until = SystemClock.elapsedRealtime() + 5000;
    while (stable < 3 && SystemClock.elapsedRealtime() < until) {
      view = device.findObject(selector);
      if (view != null) {
        android.graphics.Rect bounds = view.getVisibleBounds();
        if (bounds.equals(previous) && !bounds.isEmpty()) stable++;
        else stable = 0;
        previous = bounds;
      }
      Thread.sleep(75);
    }
    assertTrue("Button must finish moving before touch: " + resource, stable >= 3);
    System.out.println("UI_TAP " + resource + " " + SystemClock.elapsedRealtime() + " " + previous);
    view.click();
  }

  @Test
  public void uiPauseResumeCancel() throws Exception {
    DownloadJobs jobs = launch();
    long first = enqueue(jobs, "/process/direct");
    checkpoint(jobs, first);
    tap("nav_downloads", null);
    tapJob(first, "item_primary", "일시정지");
    await(jobs, first, DownloadJobStore.State.PAUSED);
    tapJob(first, "item_primary", "재개");
    DownloadJobStore.Job complete = await(jobs, first, DownloadJobStore.State.COMPLETED);
    context.getContentResolver().delete(Uri.parse(complete.uri), null, null);
    long canceled = enqueue(jobs, "/process/direct");
    checkpoint(jobs, canceled);
    tapJob(canceled, "item_secondary", "취소");
    tap("action_confirm", "취소");
    assertTrue(
        "Cancel confirmation must be dismissed",
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            .wait(
                androidx.test.uiautomator.Until.gone(
                    androidx.test.uiautomator.By.res("com.adownloader.app", "action_confirm")),
                5000));
    DownloadJobStore.Job stopped = await(jobs, canceled, DownloadJobStore.State.CANCELED);
    assertEquals("", stopped.pending);
  }

  private long enqueueSource(DownloadJobs jobs, String path, MediaCandidate.MediaType type)
      throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    long[] id = {-1};
    instrumentation.runOnMainSync(
        () ->
            jobs.enqueue(
                new MediaCandidate(
                    BASE + path,
                    type,
                    "test",
                    BASE + "/page",
                    Collections.emptyMap(),
                    System.currentTimeMillis()),
                value -> {
                  id[0] = value;
                  done.countDown();
                }));
    assertTrue(done.await(15, TimeUnit.SECONDS));
    assertTrue(id[0] > 0);
    return id[0];
  }

  private void parts(DownloadJobs jobs, long id, int count) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < until) {
      try (android.database.Cursor c =
          jobs.store
              .getReadableDatabase()
              .rawQuery("SELECT COUNT(*) FROM parts WHERE job=?", new String[] {"" + id})) {
        c.moveToFirst();
        if (c.getInt(0) >= count) return;
      }
      Thread.sleep(20);
    }
    fail("No committed media segment");
  }

  private void refreshCase(DownloadJobs jobs, MediaCandidate.MediaType type, boolean changed)
      throws Exception {
    get("/reset");
    String extension =
        type == MediaCandidate.MediaType.HLS
            ? "m3u8"
            : type == MediaCandidate.MediaType.DASH ? "mpd" : "mp4";
    String path = "/process/asset-123." + extension;
    long id = enqueueSource(jobs, path + "?token=old", type);
    boolean direct = type == MediaCandidate.MediaType.VIDEO;
    if (direct) checkpoint(jobs, id);
    else parts(jobs, id, type == MediaCandidate.MediaType.DASH ? 2 : 1);
    action(jobs, id, "PAUSE");
    await(jobs, id, DownloadJobStore.State.PAUSED);
    String reusedPart = null;
    if (!direct) {
      List<ManifestParser.Track> plan =
          PlanCodec.decode(jobs.store.request(id).getJSONArray("plan"));
      reusedPart =
          Uri.parse(plan.get(0).parts.get(type == MediaCandidate.MediaType.DASH ? 1 : 0).url)
              .getPath();
    }
    get(changed ? "/change" : "/expire");
    action(jobs, id, "RESUME");
    DownloadJobStore.Job expired = await(jobs, id, DownloadJobStore.State.NEEDS_REFRESH);
    assertEquals("EXPIRED_URL", expired.error);
    MediaCandidate fresh =
        new MediaCandidate(
            BASE + path + "?token=new",
            type,
            "network-response",
            BASE + "/page",
            Collections.emptyMap(),
            System.currentTimeMillis());
    CountDownLatch offered = new CountDownLatch(1);
    long[] matched = {-1};
    jobs.offerRefresh(
        fresh,
        value -> {
          matched[0] = value;
          offered.countDown();
        });
    assertTrue(offered.await(10, TimeUnit.SECONDS));
    assertEquals(id, matched[0]);
    CountDownLatch attached = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> jobs.refresh(id, fresh, message -> attached.countDown()));
    assertTrue(attached.await(20, TimeUnit.SECONDS));
    DownloadJobStore.Job complete = await(jobs, id, DownloadJobStore.State.COMPLETED);
    Uri uri = Uri.parse(complete.uri);
    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
      assertNotNull(fd);
      PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
    }
    JSONObject stats = get("/stats");
    if (direct) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = context.getContentResolver().openInputStream(uri)) {
        byte[] buffer = new byte[128 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      assertEquals(stats.getString("sha256"), DirectTransfer.hex(digest.digest()));
      JSONArray offsets = stats.getJSONArray("ranges");
      boolean positive = false;
      for (int n = 0; n < offsets.length(); n++) positive |= offsets.getLong(n) > 0;
      assertEquals("Changed files must restart; unchanged files must resume", !changed, positive);
    } else
      assertEquals(
          "Saved media parts must survive signed URL refresh",
          1,
          stats.getJSONObject("counts").getInt(reusedPart));
    context.getContentResolver().delete(uri, null, null);
  }

  @Test
  public void refreshSources() throws Exception {
    DownloadJobs jobs = launch();
    refreshCase(jobs, MediaCandidate.MediaType.VIDEO, false);
    refreshCase(jobs, MediaCandidate.MediaType.VIDEO, true);
    refreshCase(jobs, MediaCandidate.MediaType.HLS, false);
    refreshCase(jobs, MediaCandidate.MediaType.DASH, false);
  }
}
