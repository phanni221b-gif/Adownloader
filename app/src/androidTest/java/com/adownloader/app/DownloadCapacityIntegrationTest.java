package com.adownloader.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.jobs.DownloadJobStore;
import com.adownloader.app.jobs.DownloadJobs;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DownloadCapacityIntegrationTest {
  @Test
  public void threeJobsCanTransferWhileFourthWaits() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var jobs = DownloadJobs.get(instrumentation.getTargetContext());
    var ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    int originalLimit = jobs.parallelLimit();
    DownloadJobs.Host host = () -> {};
    long[] ids = new long[4];
    var idle = new CountDownLatch(1);
    try (var server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(8000);
      instrumentation.runOnMainSync(
          () -> {
            jobs.setParallelLimit(3);
            jobs.attach(host);
          });
      for (int i = 0; i < ids.length; i++) {
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/" + i + ".mp4";
        ids[i] =
            jobs.store.enqueue(
                url,
                "VIDEO",
                "Capacity fixture " + i,
                "127.0.0.1",
                new JSONObject().put("url", url).put("headers", new JSONObject()));
      }
      jobs.drain(idle::countDown);
      try (Socket first = server.accept();
          Socket second = server.accept();
          Socket third = server.accept()) {
        for (int i = 0; i < 3; i++) assertTrue(jobs.isExecuting(ids[i]));
        assertFalse(jobs.isExecuting(ids[3]));
        assertEquals(DownloadJobStore.State.QUEUED, jobs.store.get(ids[3]).state);
        assertEquals(1L, idle.getCount());
        instrumentation.runOnMainSync(
            () -> {
              jobs.setParallelLimit(1);
              jobs.detach(host);
            });
      }
    } finally {
      instrumentation.runOnMainSync(
          () -> {
            jobs.detach(host);
            jobs.setParallelLimit(originalLimit);
          });
      jobs.drain(idle::countDown);
      assertTrue(idle.await(20, TimeUnit.SECONDS));
      for (long id : ids) {
        if (id == 0) continue;
        jobs.store.state(id, DownloadJobStore.State.CANCELED, "");
        jobs.store.clearFailedOrCanceled(id);
      }
    }
  }
}
