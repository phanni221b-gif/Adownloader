package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.MediaNetworkSession;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * On-device verification (real GeckoView, not mocked events) that the player-media-outcome
 * observation actually fires from live page network activity: a page that fetches a media segment
 * served 200 is recorded as a success for its host, and one served 403 is recorded as a failure for
 * its host. This is the end-to-end proof behind observedPlayerMediaHosts — so an empty registry on a
 * real site means the player made no observable media request, not that the pipeline is broken.
 */
@RunWith(AndroidJUnit4.class)
public class PlayerMediaObservationIntegrationTest {
  private final Instrumentation app = InstrumentationRegistry.getInstrumentation();
  private final Context context = app.getTargetContext();
  private ServerSocket server;
  private ExecutorService network;
  private MainActivity activity;
  private int port;

  @Before
  public void start() throws Exception {
    app.getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    MediaNetworkSession.clearPlayerOutcomes();
    server = new ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"));
    port = server.getLocalPort();
    network = Executors.newCachedThreadPool();
    network.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket socket = server.accept();
              network.execute(() -> serve(socket));
            } catch (IOException ignored) {
            }
        });
  }

  private void serve(Socket socket) {
    try (Socket client = socket) {
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
      String first = in.readLine();
      if (first == null) return;
      String path = first.split(" ")[1];
      while ((first = in.readLine()) != null && !first.isEmpty()) {}
      String status = "200 OK", type = "text/html; charset=utf-8";
      byte[] bytes;
      if (path.startsWith("/ok.ts")) {
        type = "video/mp2t";
        bytes = new byte[2048];
        new Random(3).nextBytes(bytes);
      } else if (path.startsWith("/blocked.ts")) {
        status = "403 Forbidden";
        bytes = "<html>access denied</html>".getBytes(StandardCharsets.UTF_8);
      } else {
        // The page fetches a same-origin 200 segment and a cross-host 403 segment; errors are
        // swallowed so a CORS rejection on the blocked fetch does not stop the page.
        String blocked = "http://127.0.0.2:" + port + "/blocked.ts";
        bytes =
            ("<title>obs-ready</title><script>"
                    + "fetch('/ok.ts').then(r=>r.arrayBuffer()).catch(()=>{});"
                    + "fetch('" + blocked + "').catch(()=>{});"
                    + "</script>")
                .getBytes(StandardCharsets.UTF_8);
      }
      client
          .getOutputStream()
          .write(
              ("HTTP/1.1 "
                      + status
                      + "\r\nContent-Type: "
                      + type
                      + "\r\nContent-Length: "
                      + bytes.length
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().write(bytes);
    } catch (Exception ignored) {
    }
  }

  @After
  public void stop() throws Exception {
    if (activity != null) app.runOnMainSync(() -> activity.finish());
    server.close();
    network.shutdownNow();
    MediaNetworkSession.clearPlayerOutcomes();
  }

  @Test
  public void livePageMediaFetchesAreObservedAsHostSuccessAndFailure() throws Exception {
    activity =
        (MainActivity)
            app.startActivitySync(
                new Intent(context, MainActivity.class)
                    .setData(Uri.parse("http://127.0.0.1:" + port + "/obs"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));

    Map<String, Integer> snapshot = Map.of();
    long end = System.currentTimeMillis() + 60000;
    while (System.currentTimeMillis() < end) {
      snapshot = MediaNetworkSession.playerMediaSnapshot();
      if (snapshot.containsKey("127.0.0.1") && snapshot.containsKey("127.0.0.2")) break;
      Thread.sleep(250);
    }

    Integer ok = snapshot.get("127.0.0.1");
    Integer blocked = snapshot.get("127.0.0.2");
    assertNotNull("a 200 media fetch must be observed for its host, on real GeckoView", ok);
    assertTrue("the 200 media host is recorded as a success", ok >= 200 && ok < 300);
    assertNotNull("a 403 media fetch must be observed for its host, on real GeckoView", blocked);
    assertEquals("the 403 media host is recorded with its real status", Integer.valueOf(403), blocked);
  }
}
