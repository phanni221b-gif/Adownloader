package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.jobs.*;
import com.adownloader.app.media.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AuthenticatedMediaIntegrationTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private ServerSocket server;
  private ExecutorService network;
  private String base;
  private byte[] media;
  private final AtomicInteger denied = new AtomicInteger();
  private final String cookie = "fixture_auth=PRIVATE_MEDIA_COOKIE";

  @Before
  public void setup() throws Exception {
    try (InputStream in = instrumentation.getContext().getAssets().open("fixtures/direct.mp4")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      media = out.toByteArray();
    }
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort();
    network = Executors.newCachedThreadPool();
    network.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket client = server.accept();
              network.execute(() -> serve(client));
            } catch (IOException ignored) {
            }
        });
  }

  @After
  public void stop() throws Exception {
    server.close();
    network.shutdownNow();
  }

  private void serve(Socket client) {
    try (Socket socket = client) {
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String request = reader.readLine();
      if (request == null) return;
      String path = request.split(" ")[1];
      Map<String, String> headers = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        int at = line.indexOf(':');
        if (at > 0)
          headers.put(
              line.substring(0, at).toLowerCase(Locale.ROOT), line.substring(at + 1).trim());
      }
      boolean page = path.startsWith("/auth"),
          allowed = page || headers.getOrDefault("cookie", "").contains(cookie);
      byte[] body =
          page
              ? ("<!doctype html><meta charset='utf-8'><title>인증 영상</title><h1>Authenticated"
                     + " fixture</h1><script>fetch('/protected/play?id=1').then(r=>r.arrayBuffer());</script>")
                  .getBytes(StandardCharsets.UTF_8)
              : allowed ? media : new byte[0];
      if (!allowed) denied.incrementAndGet();
      String extra =
          page
              ? "Set-Cookie: " + cookie + "; Path=/; HttpOnly; SameSite=Lax\r\n"
              : "Content-Disposition: attachment;"
                  + " filename*=UTF-8''%EC%9B%90%EB%B3%B8%20%EC%98%81%EC%83%81.mp4\r\n"
                  + "ETag: \"auth-fixture-v1\"\r\n";
      String response =
          "HTTP/1.1 "
              + (allowed ? "200 OK" : "403 Forbidden")
              + "\r\nContent-Type: "
              + (page ? "text/html; charset=utf-8" : "video/mp4")
              + "\r\nContent-Length: "
              + body.length
              + "\r\n"
              + extra
              + "Connection: close\r\n\r\n";
      socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(body);
    } catch (Exception ignored) {
    }
  }

  @Test
  public void extensionlessResponseKeepsOnlyItsActualRequestCookie() throws Exception {
    HttpURLConnection missing =
        (HttpURLConnection) new URL(base + "/protected/play?id=1").openConnection();
    assertEquals(403, missing.getResponseCode());
    missing.disconnect();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(base + "/auth"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Uri output = null;
    try {
      java.lang.reflect.Field field = MainActivity.class.getDeclaredField("detector");
      field.setAccessible(true);
      MediaDetector detector = (MediaDetector) field.get(activity);
      MediaCandidate selected = null;
      long end = System.currentTimeMillis() + 60000;
      while (System.currentTimeMillis() < end) {
        for (MediaCandidate item : detector.snapshot())
          if (item.getUrl().equals(base + "/protected/play?id=1")
              && item.getHeaders().values().stream().anyMatch(value -> value.contains(cookie))
              && "인증 영상".equals(item.getMetadata().get("title"))) selected = item;
        if (selected != null) break;
        Thread.sleep(100);
      }
      assertNotNull(
          "Actual MIME-classified network response must retain authenticated context", selected);
      assertEquals("network-response", selected.getSource());
      assertFalse(
          selected.getHeaders().keySet().stream()
              .anyMatch(key -> key.equalsIgnoreCase("Authorization")));
      assertTrue(selected.getMetadata().get("disposition").contains("filename*="));
      DownloadJobs jobs = DownloadJobs.get(context);
      CountDownLatch stored = new CountDownLatch(1);
      long[] id = {-1};
      MediaCandidate candidate = selected;
      instrumentation.runOnMainSync(
          () ->
              jobs.enqueue(
                  candidate,
                  value -> {
                    id[0] = value;
                    stored.countDown();
                  }));
      assertTrue(stored.await(15, TimeUnit.SECONDS));
      assertTrue(id[0] > 0);
      DownloadJobStore.Job completed = null;
      end = System.currentTimeMillis() + 60000;
      while (System.currentTimeMillis() < end) {
        DownloadJobStore.Job job = jobs.store.get(id[0]);
        if (job.state == DownloadJobStore.State.COMPLETED) {
          completed = job;
          break;
        }
        if (job.state == DownloadJobStore.State.FAILED
            || job.state == DownloadJobStore.State.NEEDS_REFRESH)
          fail("Authenticated transfer failed: " + job.error);
        Thread.sleep(50);
      }
      assertNotNull(completed);
      assertEquals("원본 영상.mp4", completed.name);
      output = Uri.parse(completed.uri);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = context.getContentResolver().openInputStream(output)) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(media), digest.digest());
      assertEquals("Only the deliberate unauthenticated request may be rejected", 1, denied.get());
      CountDownLatch secondStored = new CountDownLatch(1);
      long[] secondId = {-1};
      instrumentation.runOnMainSync(
          () ->
              jobs.enqueue(
                  candidate,
                  value -> {
                    secondId[0] = value;
                    secondStored.countDown();
                  }));
      assertTrue(secondStored.await(15, TimeUnit.SECONDS));
      DownloadJobStore.Job second = null;
      long secondDeadline = System.currentTimeMillis() + 60000;
      while (System.currentTimeMillis() < secondDeadline) {
        DownloadJobStore.Job current = jobs.store.get(secondId[0]);
        if (current.state == DownloadJobStore.State.COMPLETED) {
          second = current;
          break;
        }
        Thread.sleep(50);
      }
      assertNotNull("Second download must finish without replacing the first", second);
      assertEquals("원본 영상 (1).mp4", second.name);
      context.getContentResolver().delete(Uri.parse(second.uri), null, null);
      for (String suffix : new String[] {"", "-wal"}) {
        File db = new File(context.getDatabasePath("download-jobs.db").getPath() + suffix);
        if (db.isFile())
          assertFalse(
              new String(Files.readAllBytes(db.toPath()), StandardCharsets.ISO_8859_1)
                  .contains("PRIVATE_MEDIA_COOKIE"));
      }
      File history = new File(context.getApplicationInfo().dataDir, "shared_prefs/downloads.xml");
      if (history.isFile())
        assertFalse(
            new String(Files.readAllBytes(history.toPath()), StandardCharsets.UTF_8)
                .contains("PRIVATE_MEDIA_COOKIE"));
    } finally {
      if (output != null) context.getContentResolver().delete(output, null, null);
      instrumentation.runOnMainSync(activity::finish);
    }
  }
}
