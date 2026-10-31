package com.adownloader.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CheckpointIntegrationTest {
  private DownloadIntegrationTest server;
  private String base;
  private DownloadJobStore store;

  @Before
  public void start() throws Exception {
    server = new DownloadIntegrationTest();
    server.start();
    Field field = DownloadIntegrationTest.class.getDeclaredField("base");
    field.setAccessible(true);
    base = (String) field.get(server);
    android.content.Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    c.deleteDatabase("checkpoint-tests.db");
    store = new DownloadJobStore(c, "checkpoint-tests.db");
  }

  @After
  public void close() throws Exception {
    for (DownloadJobStore.Job j : store.list()) {
      store.state(j.id, DownloadJobStore.State.CANCELED, "");
      store.cleanup(j.id);
    }
    store.close();
    server.stop();
  }

  private void resume(String path, MediaCandidate.MediaType type) throws Exception {
    long id =
        store.enqueue(
            "test", type.name(), "video", "localhost", new JSONObject().put("url", base + path));
    store.claim();
    File directory = store.directory(id);
    TransferControl stopped = new TransferControl();
    JobJournal persisted = new JobJournal(store, id);
    EngineCheckpoint first =
        new EngineCheckpoint() {
          public List<ManifestParser.Track> loadPlan() throws Exception {
            return persisted.loadPlan();
          }

          public void savePlan(List<ManifestParser.Track> t) throws Exception {
            persisted.savePlan(t);
          }

          public boolean verified(File f, int t, int p) throws Exception {
            return persisted.verified(f, t, p);
          }

          public void saved(File f, int t, int p) throws Exception {
            persisted.saved(f, t, p);
            if (p == 0) stopped.stop(TransferControl.Stop.PAUSE);
          }

          public void copy(HttpSource s, String u, File f, TransferControl c) throws Exception {
            persisted.copy(s, u, f, c);
          }
        };
    try {
      new DownloadEngine(directory, (p, v) -> {}, first, stopped)
          .download(base + path, type, Collections.emptyMap());
      fail("Must stop after a committed segment");
    } catch (TransferControl.Stopped expected) {
    }
    final int[] reused = {0};
    TransferControl control = new TransferControl();
    EngineCheckpoint second =
        new EngineCheckpoint() {
          public List<ManifestParser.Track> loadPlan() throws Exception {
            return persisted.loadPlan();
          }

          public void savePlan(List<ManifestParser.Track> t) throws Exception {
            fail("The persisted plan must be reused");
          }

          public boolean verified(File f, int t, int p) throws Exception {
            boolean ok = persisted.verified(f, t, p);
            if (ok) reused[0]++;
            return ok;
          }

          public void saved(File f, int t, int p) throws Exception {
            if (p == 0 && t == 0) fail("Committed part must not be downloaded again");
            persisted.saved(f, t, p);
          }

          public void copy(HttpSource s, String u, File f, TransferControl c) throws Exception {
            persisted.copy(s, u, f, c);
          }
        };
    DownloadEngine.Result result =
        new DownloadEngine(directory, (p, v) -> {}, second, control)
            .download(base + path, type, Collections.emptyMap());
    assertTrue(reused[0] > 0);
    PostProcessor.Inspection inspected = PostProcessor.inspect(result.file, 3, true, true);
    assertTrue(inspected.audio && inspected.video);
  }

  @Test
  public void hlsCommittedSegmentsSurviveWorkerRestart() throws Exception {
    resume("playlist.m3u8", MediaCandidate.MediaType.HLS);
  }

  @Test
  public void dashCommittedSegmentsSurviveWorkerRestart() throws Exception {
    resume("manifest.mpd", MediaCandidate.MediaType.DASH);
  }
}
