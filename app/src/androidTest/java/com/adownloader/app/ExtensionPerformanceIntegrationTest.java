package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.browser.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExtensionPerformanceIntegrationTest {
  @Test
  public void syntheticPageMeasuresLoadingAndExtensionWorkWithoutSensitiveUrls() throws Exception {
    var ins = InstrumentationRegistry.getInstrumentation();
    var context = ins.getTargetContext();
    ServerSocket server = new ServerSocket(0);
    ExecutorService network = Executors.newCachedThreadPool();
    network.execute(
        () -> {
          while (!server.isClosed())
            try {
              Socket socket = server.accept();
              network.execute(
                  () -> {
                    try (Socket c = socket) {
                      BufferedReader in =
                          new BufferedReader(new InputStreamReader(c.getInputStream()));
                      String request = in.readLine();
                      if (request == null) return;
                      boolean page = request.split(" ")[1].startsWith("/page");
                      String line;
                      while ((line = in.readLine()) != null && !line.isEmpty()) {}
                      String html =
                          "<!doctype html><meta charset='utf-8'><title>Benchmark"
                              + " fixture</title><h1>Benchmark fixture</h1><div"
                              + " id='updates'></div><script>for(let i=0;i<200;i++){let img=new"
                              + " Image();img.src='/pixel-'+i+'.svg';document.body.append(img);}let"
                              + " n=0;function mutate(){let"
                              + " x=document.createElement('span');x.textContent='item';document.getElementById('updates').append(x);if(++n<300)setTimeout(mutate,0);}mutate();setTimeout(()=>{let"
                              + " v=document.createElement('video');v.preload='none';v.src='/late/movie.mp4';document.body.append(v);},1000);</script>";
                      byte[] b =
                          (page
                                  ? html
                                  : "<svg xmlns='http://www.w3.org/2000/svg' width='1'"
                                      + " height='1'></svg>")
                              .getBytes(StandardCharsets.UTF_8);
                      c.getOutputStream()
                          .write(
                              ("HTTP/1.1 200 OK\r\nContent-Type: "
                                      + (page ? "text/html; charset=utf-8" : "image/svg+xml")
                                      + "\r\nCache-Control: no-store\r\nContent-Length: "
                                      + b.length
                                      + "\r\nConnection: close\r\n\r\n")
                                  .getBytes(StandardCharsets.US_ASCII));
                      c.getOutputStream().write(b);
                    } catch (Exception ignored) {
                    }
                  });
            } catch (IOException ignored) {
            }
        });
    Activity activity =
        ins.startActivitySync(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/page"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    try {
      java.lang.reflect.Field field = MainActivity.class.getDeclaredField("browser");
      field.setAccessible(true);
      BrowserSessionManager browser = (BrowserSessionManager) field.get(activity);
      long deadline = System.currentTimeMillis() + 30000;
      JSONObject metrics = null;
      while (System.currentTimeMillis() < deadline) {
        metrics = browser.getDetector().performanceMetrics;
        if (metrics != null && metrics.optDouble("navLoadMs") > 0) break;
        Thread.sleep(100);
      }
      assertNotNull("Performance report must arrive from the actual Gecko extension", metrics);
      assertTrue(metrics.optDouble("navLoadMs") > 0);
      assertTrue(metrics.optDouble("navDclMs") > 0);
      assertTrue(metrics.optInt("beforeCalls") >= 200);
      assertTrue(metrics.optInt("requestMapMax") <= 1024);
      boolean late =
          browser.getDetector().snapshot().stream()
              .anyMatch(c -> c.getUrl().contains("/late/movie.mp4"));
      assertTrue("Late dynamic media stays detectable", late);
      Files.write(
          new File(context.getExternalFilesDir(null), "extension-performance.json").toPath(),
          metrics.toString(2).getBytes(StandardCharsets.UTF_8));
      android.util.Log.i("Adownloader", "EXTENSION_PERFORMANCE " + metrics.toString());
      androidx.test.uiautomator.UiDevice.getInstance(ins)
          .executeShellCommand("mkdir -p /sdcard/Download/Adownloader-ui-proof");
      androidx.test.uiautomator.UiDevice.getInstance(ins)
          .executeShellCommand(
              "cp "
                  + new File(context.getExternalFilesDir(null), "extension-performance.json")
                      .getAbsolutePath()
                  + " /sdcard/Download/Adownloader-ui-proof/extension-performance.json");
    } finally {
      ins.runOnMainSync(activity::finish);
      server.close();
      network.shutdownNow();
    }
  }
}
