package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.*;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FinalizedStorageIntegrationTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

  @Test
  public void durableFinalOutputReopensWithoutSourcesAndRejectsCorruption() throws Exception {
    String name = "finalized-tests.db";
    context.deleteDatabase(name);
    long id;
    try {
      try (DownloadJobStore store = new DownloadJobStore(context, name)) {
        id =
            store.enqueue(
                "finalized-fixture",
                "HLS",
                "Finalized.mp4",
                "localhost",
                new JSONObject()
                    .put("url", "http://127.0.0.1:1/unreachable.m3u8")
                    .put("headers", new JSONObject()));
        var uri = store.ensurePending(id, "Finalized-fixture.mp4", "video/mp4");
        try (InputStream input =
                InstrumentationRegistry.getInstrumentation()
                    .getContext()
                    .getAssets()
                    .open("fixtures/direct.mp4");
            OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
          input.transferTo(output);
        }
        File part = new File(store.directory(id), "part-0-0");
        Files.write(part.toPath(), new byte[] {1, 2, 3});
        String hash = JobJournal.hash(part);
        store.part(id, 0, 0, 3, hash);
        try (ParcelFileDescriptor fd =
            context
                .getContentResolver()
                .openFileDescriptor(android.net.Uri.parse(store.get(id).pending), "rw")) {
          TransferControl paused = new TransferControl();
          paused.stop(TransferControl.Stop.PAUSE);
          try {
            new JobJournal(store, id)
                .saveDestinationFinal(fd.getFileDescriptor(), "video/mp4", ".mp4", paused);
            fail();
          } catch (TransferControl.Stopped expected) {
          }
          assertTrue("No deletion before durable checkpoint", part.isFile());
          assertNull(store.finalizedMedia(id));
          PostProcessor.inspect(fd.getFileDescriptor(), 3, true, true);
          new JobJournal(store, id)
              .saveDestinationFinal(
                  fd.getFileDescriptor(), "video/mp4", ".mp4", new TransferControl());
        }
        assertFalse(part.exists());
        assertEquals(hash, store.partHash(id, 0, 0, 3));
        assertNotNull(store.finalizedMedia(id));
      }
      try (DownloadJobStore restored = new DownloadJobStore(context, name);
          ParcelFileDescriptor fd =
              context
                  .getContentResolver()
                  .openFileDescriptor(android.net.Uri.parse(restored.get(id).pending), "rw")) {
        new JobJournal(restored, id).verifyAll();
        var engine =
            new DownloadEngine(
                restored.directory(id),
                (message, percent) -> {},
                new JobJournal(restored, id),
                new TransferControl(),
                fd.getFileDescriptor());
        var result =
            engine.download(
                "http://127.0.0.1:1/unreachable.m3u8",
                MediaCandidate.MediaType.HLS,
                Collections.emptyMap());
        assertNull(result.file);
        assertEquals("video/mp4", result.mime);
        try (DescriptorChannel channel = new DescriptorChannel(fd.getFileDescriptor())) {
          channel.write(java.nio.ByteBuffer.wrap(new byte[] {99}));
          channel.force(false);
        }
        try {
          new JobJournal(restored, id)
              .loadDestinationFinal(fd.getFileDescriptor(), new TransferControl());
          fail();
        } catch (DownloadError error) {
          assertEquals(DownloadError.Category.STORAGE, error.category);
        }
      }
    } finally {
      try (DownloadJobStore store = new DownloadJobStore(context, name)) {
        for (var job : store.list()) {
          store.state(job.id, DownloadJobStore.State.CANCELED, "");
          store.cleanup(job.id);
        }
      }
      context.deleteDatabase(name);
    }
  }

  @Test
  public void descriptorRejectsImpossibleCapacityWithoutAllocatingLargeFiles() throws Exception {
    File file = File.createTempFile("capacity-", ".bin", context.getCacheDir());
    try (ParcelFileDescriptor fd =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE);
        DescriptorChannel channel = new DescriptorChannel(fd.getFileDescriptor())) {
      try {
        channel.reserve(Long.MAX_VALUE);
        fail();
      } catch (DownloadError error) {
        assertEquals(DownloadError.Category.STORAGE_FULL, error.category);
      }
      assertEquals(0, channel.size());
    } finally {
      file.delete();
    }
  }

  @Test
  public void actualJobUsesOnlySQLiteForProgressAndCompletion() throws Exception {
    String name = "single-authority-tests.db";
    context.deleteDatabase(name);
    DownloadIntegrationTest server = new DownloadIntegrationTest();
    server.start();
    try (DownloadJobStore store = new DownloadJobStore(context, name)) {
      var field = DownloadIntegrationTest.class.getDeclaredField("base");
      field.setAccessible(true);
      String url = field.get(server) + "direct.mp4";
      var prefs = context.getSharedPreferences("downloads", 0);
      prefs.edit().remove("items").commit();
      long id =
          store.enqueue(
              "sqlite-only",
              "VIDEO",
              "SQLite-only.mp4",
              "localhost",
              new JSONObject()
                  .put("url", url)
                  .put("headers", new JSONObject().put("Cookie", "PRIVATE_CONTEXT_ONLY")));
      new JobRunner(context, store).run(store.claim(), new TransferControl());
      assertEquals(DownloadJobStore.State.COMPLETED, store.get(id).state);
      assertFalse(prefs.contains("items"));
      assertFalse(prefs.getAll().toString().contains("PRIVATE_CONTEXT_ONLY"));
      context.getContentResolver().delete(android.net.Uri.parse(store.get(id).uri), null, null);
    } finally {
      server.stop();
      context.deleteDatabase(name);
    }
  }

  @org.junit.Test
  public void typedStorageFailureRetainsFullCategoryWithoutLeakingMessage() {
    Exception error =
        new java.io.IOException(
            "private-url-and-cookie",
            new android.system.ErrnoException("write", android.system.OsConstants.ENOSPC));
    assertEquals(DownloadError.Category.STORAGE_FULL, DownloadError.storage(error).category);
    assertEquals("STORAGE_FULL", DownloadError.storage(error).getMessage());
    assertEquals(
        DownloadError.Category.STORAGE,
        DownloadError.storage(
                new android.system.ErrnoException("write", android.system.OsConstants.EIO))
            .category);
  }
}
