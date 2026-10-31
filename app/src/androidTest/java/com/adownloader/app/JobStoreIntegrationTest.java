package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class JobStoreIntegrationTest {
  private Context context;
  private DownloadJobStore store;

  @Before
  public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.deleteDatabase("store-tests.db");
    store = new DownloadJobStore(context, "store-tests.db");
  }

  @After
  public void close() {
    for (DownloadJobStore.Job job : store.list()) {
      store.state(job.id, DownloadJobStore.State.CANCELED, "");
      store.cleanup(job.id);
    }
    store.close();
    context.deleteDatabase("store-tests.db");
  }

  private long job() throws Exception {
    return store.enqueue(
        "identity",
        "VIDEO",
        "test",
        "example.test",
        new JSONObject()
            .put("url", "https://example.test/video?token=TOP_SECRET")
            .put("headers", new JSONObject().put("Cookie", "auth=COOKIE_SECRET")));
  }

  @Test
  public void authenticatedContextIsEncryptedAndBoundToJob() throws Exception {
    long id = job();
    assertTrue(store.request(id).getString("url").contains("TOP_SECRET"));
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery("SELECT secret FROM jobs WHERE id=?", new String[] {"" + id})) {
      assertTrue(c.moveToFirst());
      byte[] encrypted = c.getBlob(0);
      String raw = new String(encrypted, StandardCharsets.ISO_8859_1);
      assertFalse(raw.contains("TOP_SECRET"));
      assertFalse(raw.contains("COOKIE_SECRET"));
      JobSecrets secrets = new JobSecrets();
      try {
        secrets.open(id + 1, encrypted);
        fail();
      } catch (java.security.GeneralSecurityException expected) {
      }
    }
    JobSecrets secrets = new JobSecrets();
    assertFalse(java.util.Arrays.equals(secrets.seal(id, "same"), secrets.seal(id, "same")));
    for (String suffix : new String[] {"", "-wal"}) {
      File file = new File(context.getDatabasePath("store-tests.db").getPath() + suffix);
      if (file.isFile()) {
        String disk = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(disk.contains("TOP_SECRET"));
        assertFalse(disk.contains("COOKIE_SECRET"));
      }
    }
  }

  @Test
  public void previousProcessRecoversToPausedWithoutLosingContext() throws Exception {
    long id = job();
    assertEquals(id, store.claim().id);
    store
        .getWritableDatabase()
        .execSQL("UPDATE jobs SET pid=-123,state='DOWNLOADING' WHERE id=" + id);
    store.close();
    store = new DownloadJobStore(context, "store-tests.db");
    store.reconcile();
    assertEquals(DownloadJobStore.State.PAUSED, store.get(id).state);
    assertTrue(store.request(id).getString("url").contains("TOP_SECRET"));
  }

  @Test
  public void cancelRemovesTempAndPendingMediaStore() throws Exception {
    long id = job();
    File directory = store.directory(id);
    Files.write(new File(directory, "scratch").toPath(), new byte[] {1, 2});
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "cancel-test.mp4");
    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri uri =
        context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    assertNotNull(uri);
    store.pending(id, uri.toString());
    store.state(id, DownloadJobStore.State.CANCELED, "");
    store.cleanup(id);
    assertFalse(directory.exists());
    assertEquals(DownloadJobStore.State.CANCELED, store.get(id).state);
    try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
      assertTrue(c == null || c.getCount() == 0);
    }
  }

  @Test
  public void migrationIsOnceAndDoesNotInventResumableJobs() throws Exception {
    android.content.SharedPreferences prefs = context.getSharedPreferences("downloads", 0);
    String backup = prefs.getString("items", "{}");
    try {
      JSONObject rows =
          new JSONObject()
              .put(
                  "old-done",
                  new JSONObject()
                      .put("state", "완료")
                      .put("uri", "content://media/external/video/media/123"))
              .put("old-active", new JSONObject().put("state", "다운로드 중"));
      prefs.edit().putString("items", rows.toString()).commit();
      store.migrate();
      store.migrate();
      assertEquals(2, store.list().size());
      assertEquals(
          1,
          store.list().stream().filter(j -> j.state == DownloadJobStore.State.COMPLETED).count());
      assertEquals(
          1, store.list().stream().filter(j -> j.state == DownloadJobStore.State.FAILED).count());
    } finally {
      prefs.edit().putString("items", backup).commit();
    }
  }

  @Test
  public void queuePriorityAndOwnershipAreDurable() throws Exception {
    long first = job(), second = job(), third = job();
    store.prioritize(third);
    assertEquals(third, store.claim().id);
    assertTrue(DownloadJobStore.ownedHere(store.get(third)));
    assertEquals(first, store.claim().id);
    assertEquals(second, store.claim().id);
    assertNull(store.claim());
    store
        .getWritableDatabase()
        .execSQL("UPDATE jobs SET owner='previous-process-same-pid' WHERE id=" + first);
    store.reconcile();
    assertEquals(DownloadJobStore.State.PAUSED, store.get(first).state);
    assertEquals(DownloadJobStore.State.PREPARING, store.get(second).state);
    assertFalse(
        store.transition(
            first, DownloadJobStore.State.DOWNLOADING, DownloadJobStore.State.FINALIZING));
    store.state(first, DownloadJobStore.State.CANCELED, "");
    assertFalse(
        store.transition(
            first, DownloadJobStore.State.PREPARING, DownloadJobStore.State.DOWNLOADING));
  }

  @Test
  public void encryptedThousandSegmentPlanFitsAndIsBounded() throws Exception {
    long id = job();
    JSONObject request = store.request(id);
    JSONArray parts = new JSONArray();
    java.util.Random random = new java.util.Random(719);
    for (int i = 0; i < 1000; i++) {
      byte[] token = new byte[1500];
      random.nextBytes(token);
      parts.put(
          new JSONObject()
              .put(
                  "url",
                  "https://cdn.example.test/asset/segment-"
                      + i
                      + ".m4s?Signature="
                      + android.util.Base64.encodeToString(
                          token, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP))
              .put("range", i * 4096));
    }
    request.put("stress-plan", parts);
    store.writeContext(id, request);
    assertEquals(1000, store.request(id).getJSONArray("stress-plan").length());
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*),MAX(length(data)),SUM(length(data)) FROM contexts WHERE job=?",
                new String[] {"" + id})) {
      assertTrue(c.moveToFirst());
      assertTrue(c.getInt(0) >= 2);
      assertTrue(c.getInt(1) <= 512 * 1024);
      assertTrue(c.getLong(2) > 1024 * 1024);
    }
    store.close();
    store = new DownloadJobStore(context, "store-tests.db");
    assertEquals(1000, store.request(id).getJSONArray("stress-plan").length());
  }
}
