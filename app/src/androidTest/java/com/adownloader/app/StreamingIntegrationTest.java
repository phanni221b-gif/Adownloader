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
}
