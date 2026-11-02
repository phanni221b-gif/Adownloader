package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TerminalCleanupIntegrationTest {
  Context context;
  DownloadJobStore store;
  List<Uri> media = new ArrayList<>();

  @Before
  public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.deleteDatabase("cleanup-tests.db");
    store = new DownloadJobStore(context, "cleanup-tests.db");
  }

  @After
  public void close() {
    for (var j : store.list()) {
      store.state(j.id, DownloadJobStore.State.CANCELED, "");
      store.cleanup(j.id);
    }
    for (Uri u : media) context.getContentResolver().delete(u, null, null);
    store.close();
    context.deleteDatabase("cleanup-tests.db");
  }

  long job(DownloadJobStore.State state) throws Exception {
    JSONObject request =
        new JSONObject()
            .put("url", "https://fixture.test/media")
            .put("headers", new JSONObject().put("Cookie", "PRIVATE_JOB_COOKIE"))
            .put("cookieState", "PRIVATE_JOB_SESSION")
            .put("padding", "x".repeat(600000));
    long id =
        store.enqueue(
            "cleanup-" + UUID.randomUUID(), "VIDEO", "cleanup fixture", "fixture.test", request);
    File dir = store.directory(id);
    Files.write(new File(dir, "output.partial").toPath(), new byte[] {1, 2});
    File segments = new File(dir, "segments");
    assertTrue(segments.mkdir());
    Files.write(new File(segments, "part-0.tmp").toPath(), new byte[] {3, 4});
    store.part(id, 0, 0, 2, "fixture-hash");
    store.finalizedMedia(id, 2, "fixture-hash", "video/mp4", "mp4");
    store.state(id, state, "");
    return id;
  }

  Uri pending(long id) throws Exception {
    Uri u = store.ensurePending(id, "cleanup-" + id + ".mp4", "video/mp4");
    media.add(u);
    try (OutputStream out = context.getContentResolver().openOutputStream(u);
        InputStream in =
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("fixtures/direct.mp4")) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    return u;
  }

  void publish(Uri u) {
    ContentValues v = new ContentValues();
    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
    assertEquals(1, context.getContentResolver().update(u, v, null, null));
  }

  boolean exists(Uri u) {
    try (Cursor c =
        context
            .getContentResolver()
            .query(u, new String[] {MediaStore.MediaColumns._ID}, null, null, null)) {
      return c != null && c.moveToFirst();
    }
  }

  byte[] bytes(Uri u) throws Exception {
    try (InputStream in = context.getContentResolver().openInputStream(u);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toByteArray();
    }
  }

  int count(String table, long id) {
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*) FROM "
                    + table
                    + " WHERE "
                    + (table.equals("jobs") ? "id" : "job")
                    + "=?",
                new String[] {"" + id})) {
      assertTrue(c.moveToFirst());
      return c.getInt(0);
    }
  }

  @Test
  public void mixedTerminalCleanupRemovesOnlyFailedAndCanceledIncludingAllJobArtifacts()
      throws Exception {
    var states =
        List.of(
            DownloadJobStore.State.FAILED,
            DownloadJobStore.State.FAILED,
            DownloadJobStore.State.CANCELED,
            DownloadJobStore.State.CANCELED,
            DownloadJobStore.State.PAUSED,
            DownloadJobStore.State.NEEDS_REFRESH,
            DownloadJobStore.State.COMPLETED,
            DownloadJobStore.State.QUEUED,
            DownloadJobStore.State.PREPARING,
            DownloadJobStore.State.DOWNLOADING,
            DownloadJobStore.State.FINALIZING);
    Map<Long, Uri> outputs = new LinkedHashMap<>();
    Map<Long, File> dirs = new LinkedHashMap<>();
    Map<Long, String> contexts = new HashMap<>();
    long completed = -1;
    byte[] published = null;
    for (var state : states) {
      long id =
          job(
              state == DownloadJobStore.State.COMPLETED
                  ? DownloadJobStore.State.FINALIZING
                  : state);
      outputs.put(id, pending(id));
      dirs.put(id, store.directory(id));
      if (state == DownloadJobStore.State.COMPLETED) {
        completed = id;
        publish(outputs.get(id));
        published = bytes(outputs.get(id));
        store.complete(id, outputs.get(id).toString(), "completed cleanup fixture");
      }
      contexts.put(
          id, state == DownloadJobStore.State.COMPLETED ? null : store.request(id).toString());
      assertEquals(state == DownloadJobStore.State.COMPLETED, count("contexts", id) == 0);
    }
    store.clearTerminal(DownloadJobStore.State.FAILED);
    for (var e : outputs.entrySet()) {
      long id = e.getKey();
      int index = new ArrayList<>(outputs.keySet()).indexOf(id);
      boolean remove = DownloadJobStore.failedOrCanceled(states.get(index));
      if (remove) {
        assertNull(store.get(id));
        assertFalse(dirs.get(id).exists());
        assertFalse(exists(e.getValue()));
        for (String table : List.of("jobs", "parts", "contexts", "finalized_media"))
          assertEquals(table, 0, count(table, id));
      } else {
        assertNotNull(store.get(id));
        assertEquals(states.get(index), store.get(id).state);
        assertTrue(dirs.get(id).exists());
        assertTrue(exists(e.getValue()));
        if (states.get(index) != DownloadJobStore.State.COMPLETED)
          assertEquals(contexts.get(id), store.request(id).toString());
        else assertEquals(outputs.get(id).toString(), store.get(id).uri);
        assertEquals(1, count("parts", id));
        assertNotNull(store.finalizedMedia(id));
      }
    }
    assertArrayEquals(published, bytes(outputs.get(completed)));
    assertEquals(7, store.list().size());
    store.clearTerminal(DownloadJobStore.State.FAILED);
    assertEquals(7, store.list().size());
  }

  @Test
  public void staleFailedOrCanceledStateCannotDeleteAlreadyPublishedMedia() throws Exception {
    for (var state : List.of(DownloadJobStore.State.FAILED, DownloadJobStore.State.CANCELED)) {
      long id = job(state);
      Uri u = pending(id);
      publish(u);
      byte[] before = bytes(u);
      ContentValues v = new ContentValues();
      v.put("uri", u.toString());
      store.getWritableDatabase().update("jobs", v, "id=?", new String[] {"" + id});
      store.clearTerminal(DownloadJobStore.State.FAILED);
      assertNull(store.get(id));
      assertTrue(exists(u));
      assertArrayEquals(before, bytes(u));
    }
  }

  @Test
  public void staleFailureMayForgetItsRecordWithoutTouchingAnotherCompletedJob() throws Exception {
    long done = job(DownloadJobStore.State.FINALIZING);
    Uri file = pending(done);
    publish(file);
    store.complete(done, file.toString(), "shared completed fixture");
    byte[] expected = bytes(file);
    long failed = job(DownloadJobStore.State.FAILED);
    store.pending(failed, file.toString());
    store.clearTerminal(DownloadJobStore.State.FAILED);
    assertNull(store.get(failed));
    assertEquals(DownloadJobStore.State.COMPLETED, store.get(done).state);
    assertEquals(file.toString(), store.get(done).uri);
    assertArrayEquals(expected, bytes(file));
  }

  @Test
  public void sharedPendingOutputIsPreservedUntilTheOtherJobReleasesItsReference()
      throws Exception {
    long failed = job(DownloadJobStore.State.FAILED), paused = job(DownloadJobStore.State.PAUSED);
    Uri u = pending(failed);
    store.pending(paused, u.toString());
    store.clearTerminal(DownloadJobStore.State.FAILED);
    assertNotNull(store.get(failed));
    assertEquals("CLEANUP_PENDING", store.get(failed).error);
    assertTrue(exists(u));
    assertNotNull(store.get(paused));
    assertTrue(store.directory(paused).exists());
    store.pending(paused, "");
    store.clearTerminal(DownloadJobStore.State.FAILED);
    assertNull(store.get(failed));
    assertFalse(exists(u));
    assertEquals(DownloadJobStore.State.PAUSED, store.get(paused).state);
  }

  @Test
  public void busyCanceledJobWaitsForItsWorkerAndRecoverableJobsAreNeverPurged() throws Exception {
    long canceled = job(DownloadJobStore.State.CANCELED),
        paused = job(DownloadJobStore.State.PAUSED),
        refresh = job(DownloadJobStore.State.NEEDS_REFRESH);
    File dir = store.directory(canceled);
    store.clearTerminal(DownloadJobStore.State.FAILED, Set.of(canceled));
    assertNotNull(store.get(canceled));
    assertTrue(dir.exists());
    store.clearFailedOrCanceled(paused);
    store.clearFailedOrCanceled(refresh);
    assertNotNull(store.get(paused));
    assertNotNull(store.get(refresh));
    store.clearFailedOrCanceled(canceled);
    assertNull(store.get(canceled));
    assertFalse(dir.exists());
  }

  @Test
  public void jobTemporarySymlinkCannotDeleteAnotherJobsFiles() throws Exception {
    long failed = job(DownloadJobStore.State.FAILED), paused = job(DownloadJobStore.State.PAUSED);
    File link = new File(store.directory(failed), "foreign-job"),
        other = new File(store.directory(paused), "keep.partial");
    Files.write(other.toPath(), new byte[] {9, 8, 7});
    android.system.Os.symlink(store.directory(paused).getAbsolutePath(), link.getAbsolutePath());
    try {
      store.clearTerminal(DownloadJobStore.State.FAILED);
      assertNotNull(store.get(failed));
      assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(other.toPath()));
    } finally {
      Files.deleteIfExists(link.toPath());
    }
    store.clearTerminal(DownloadJobStore.State.FAILED);
    assertNull(store.get(failed));
    assertTrue(other.exists());
  }
}
