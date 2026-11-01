package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StreamingIntegrationTest {
  @Test
  public void descriptorChannelUsesLongOffsetsAndOwnsOnlyItsDuplicate() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File file = File.createTempFile("descriptor-boundary-", ".bin", context.getCacheDir());
    try (ParcelFileDescriptor original =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)) {
      try (DescriptorChannel channel = new DescriptorChannel(original.getFileDescriptor())) {
        long offset = (1L << 31) + 17;
        channel.position(offset);
        channel.write(java.nio.ByteBuffer.wrap(new byte[] {7, 8, 9}));
        channel.force(false);
        assertEquals(offset + 3, channel.size());
        channel.position(offset);
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocateDirect(3);
        assertEquals(3, channel.read(bytes));
        bytes.flip();
        assertEquals(7, bytes.get());
        assertEquals(8, bytes.get());
        assertEquals(9, bytes.get());
        channel.truncate(2);
        assertEquals(2, channel.size());
        assertEquals(2, channel.position());
      }
      assertEquals(2, android.system.Os.fstat(original.getFileDescriptor()).st_size);
    } finally {
      file.delete();
    }
  }

  @Test
  public void virtualSegmentsMuxDirectlyToPendingMediaStoreDescriptor() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    byte[] media;
    try (InputStream input = instrumentation.getContext().getAssets().open("fixtures/direct.mp4")) {
      media = input.readAllBytes();
    }
    File root = Files.createTempDirectory(context.getCacheDir().toPath(), "virtual-mux-").toFile();
    List<File> parts = new ArrayList<>();
    Uri uri = null;
    try {
      for (int offset = 0; offset < media.length; offset += 997) {
        File part = new File(root, "part-" + parts.size());
        Files.write(
            part.toPath(), Arrays.copyOfRange(media, offset, Math.min(media.length, offset + 997)));
        parts.add(part);
      }
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Virtual-mux-fixture.mp4");
      values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Adownloader");
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      uri =
          context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(uri);
      try (JoinedMediaDataSource input = new JoinedMediaDataSource(parts);
          ParcelFileDescriptor output =
              context.getContentResolver().openFileDescriptor(uri, "rwt")) {
        assertNotNull(output);
        PostProcessor.muxSources(
            Collections.singletonList(input), output.getFileDescriptor(), new TransferControl());
        output.getFileDescriptor().sync();
        var result = PostProcessor.inspect(output.getFileDescriptor(), 3, true, true);
        assertTrue(result.video);
        assertTrue(result.audio);
        assertTrue(result.durationUs > 2500000);
      }
      try (var cursor =
          context
              .getContentResolver()
              .query(uri, new String[] {MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
        assertTrue(cursor.moveToFirst());
        assertEquals(1, cursor.getInt(0));
      }
      assertEquals(parts.size(), root.listFiles().length);
    } finally {
      if (uri != null) context.getContentResolver().delete(uri, null, null);
      for (File file : parts) file.delete();
      root.delete();
    }
  }

  @Test
  public void thousandRealHlsSegmentsDownloadAndRemuxWithoutAggregateFile() throws Exception {
    DownloadIntegrationTest fixture = new DownloadIntegrationTest();
    fixture.start();
    var field = DownloadIntegrationTest.class.getDeclaredField("base");
    field.setAccessible(true);
    String url = field.get(fixture) + "stress/playlist.m3u8";
    File root =
        Files.createTempDirectory(
                InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getCacheDir()
                    .toPath(),
                "thousand-hls-")
            .toFile();
    DownloadEngine engine = new DownloadEngine(root, (message, percent) -> {});
    try {
      var probe =
          MediaProbeService.shared()
              .probe(
                  url, MediaCandidate.MediaType.HLS, Collections.emptyMap(), new TransferControl());
      assertEquals(1001, probe.tracks().get(0).parts.size()); // 1000 media segments plus init.
      var result = engine.download(url, MediaCandidate.MediaType.HLS, Collections.emptyMap());
      var inspected = PostProcessor.inspect(result.file, 1000, true, false);
      assertTrue(inspected.video);
      assertFalse(inspected.audio);
      assertEquals(1000000000L, inspected.durationUs, 1000000L);
      assertEquals(1002, root.listFiles().length);
      assertFalse(
          Arrays.stream(root.listFiles()).anyMatch(file -> file.getName().endsWith(".media")));
    } finally {
      engine.cleanup();
      assertEquals(0, root.listFiles().length);
      root.delete();
      fixture.stop();
    }
  }

  @Test
  public void startupDeletesOnlyUnreferencedOwnedPendingRows() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var store = com.adownloader.app.jobs.DownloadJobs.get(context).store;
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
    com.adownloader.app.jobs.DownloadJobs.get(context).whenReady(ready::countDown);
    assertTrue(ready.await(15, java.util.concurrent.TimeUnit.SECONDS));
    long id =
        store.enqueue(
            "orphan-reconcile-fixture",
            "VIDEO",
            "Protected pending fixture.mp4",
            "localhost",
            new org.json.JSONObject()
                .put("url", "https://example.invalid/media.mp4")
                .put("headers", new org.json.JSONObject()));
    Uri protectedUri = store.ensurePending(id, "Protected pending fixture.mp4", "video/mp4"),
        orphan = null,
        published = null;
    store.state(id, com.adownloader.app.jobs.DownloadJobStore.State.PAUSED, "");
    try {
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Orphan-fixture.mp4");
      values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Adownloader");
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      orphan =
          context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(orphan);
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Published-fixture.mp4");
      values.put(MediaStore.MediaColumns.IS_PENDING, 0);
      published =
          context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(published);
      store.reconcile();
      try (var cursor =
          context
              .getContentResolver()
              .query(orphan, new String[] {MediaStore.MediaColumns._ID}, null, null, null)) {
        assertTrue(cursor == null || !cursor.moveToFirst());
      }
      for (Uri kept : new Uri[] {protectedUri, published})
        try (var cursor =
            context
                .getContentResolver()
                .query(kept, new String[] {MediaStore.MediaColumns._ID}, null, null, null)) {
          assertNotNull(cursor);
          assertTrue(cursor.moveToFirst());
        }
    } finally {
      store.state(id, com.adownloader.app.jobs.DownloadJobStore.State.CANCELED, "");
      store.cleanup(id);
      store.clearTerminal(com.adownloader.app.jobs.DownloadJobStore.State.CANCELED);
      if (orphan != null) context.getContentResolver().delete(orphan, null, null);
      if (published != null) context.getContentResolver().delete(published, null, null);
    }
  }
}
