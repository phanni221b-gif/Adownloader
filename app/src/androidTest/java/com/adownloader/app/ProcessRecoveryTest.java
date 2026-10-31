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
          committed = cursor.getInt(0) > 0;
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
      firstPart = Uri.parse(plan.get(0).parts.get(0).url).getPath();
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
}
