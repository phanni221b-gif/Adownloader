package com.adownloader.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProbeIntegrationTest {
  @Test
  public void oneTimeManifestPreviewThenDownloadUsesOneGet() throws Exception {
    ServerSocket server = new ServerSocket(0);
    ExecutorService network = Executors.newCachedThreadPool();
    AtomicInteger roots = new AtomicInteger();
    network.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket socket = server.accept();
              network.execute(
                  () -> {
                    try (Socket client = socket) {
                      BufferedReader in =
                          new BufferedReader(
                              new InputStreamReader(
                                  client.getInputStream(), StandardCharsets.US_ASCII));
                      String request = in.readLine();
                      if (request == null) return;
                      String name = request.split(" ")[1].substring(1);
                      String line;
                      while ((line = in.readLine()) != null && !line.isEmpty()) {}
                      boolean root = name.equals("once.m3u8"),
                          allowed = !root || roots.incrementAndGet() == 1;
                      byte[] body;
                      if (!allowed) body = new byte[0];
                      else
                        try (InputStream fixture =
                            InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open("fixtures/" + (root ? "playlist.m3u8" : name))) {
                          ByteArrayOutputStream out = new ByteArrayOutputStream();
                          byte[] buffer = new byte[8192];
                          int n;
                          while ((n = fixture.read(buffer)) != -1) out.write(buffer, 0, n);
                          body = out.toByteArray();
                        }
                      String header =
                          "HTTP/1.1 "
                              + (allowed ? "200 OK" : "403 Forbidden")
                              + "\r\nContent-Type: "
                              + (root
                                  ? "application/vnd.apple.mpegurl"
                                  : "application/octet-stream")
                              + "\r\nContent-Length: "
                              + body.length
                              + "\r\nConnection: close\r\n\r\n";
                      client.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                      client.getOutputStream().write(body);
                    } catch (Exception ignored) {
                    }
                  });
            } catch (IOException ignored) {
            }
        });
    String url = "http://127.0.0.1:" + server.getLocalPort() + "/once.m3u8";
    DownloadEngine engine =
        new DownloadEngine(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            (text, pct) -> {});
    try {
      MediaProbeResult preview =
          MediaProbeService.shared()
              .preview(url, MediaCandidate.MediaType.HLS, Collections.emptyMap());
      assertFalse(preview.tracks().isEmpty());
      DownloadEngine.Result result =
          engine.download(url, MediaCandidate.MediaType.HLS, Collections.emptyMap());
      PostProcessor.inspect(result.file, 3, true, true);
      assertEquals("Preview and engine must share the one-time manifest", 1, roots.get());
    } finally {
      engine.cleanup();
      server.close();
      network.shutdownNow();
    }
  }
}
