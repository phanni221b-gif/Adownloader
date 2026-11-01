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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.*;

@RunWith(AndroidJUnit4.class)
public class BrowserCompatibilityTest {
  private final Instrumentation app = InstrumentationRegistry.getInstrumentation();
  private final Context context = app.getTargetContext();
  private ServerSocket server;
  private ExecutorService network;
  private final AtomicInteger takeover = new AtomicInteger(), cookies = new AtomicInteger();
  private final AtomicReference<String> storage = new AtomicReference<>();
  private MainActivity activity;
  private String base;
  private byte[] media;
  private final java.util.Map<String, AtomicInteger> visits = new ConcurrentHashMap<>();

  @Before
  public void start() throws Exception {
    try (InputStream in = app.getContext().getAssets().open("fixtures/direct.mp4")) {
      media = in.readAllBytes();
    }
    server = new ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"));
    base = "http://127.0.0.1:" + server.getLocalPort();
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
      boolean cookie = false;
      String line;
      while ((line = in.readLine()) != null && !line.isEmpty())
        if (line.startsWith("Cookie:") && line.contains("browser_fixture=present")) cookie = true;
      if (path.startsWith("/takeover")) takeover.incrementAndGet();
      if (path.startsWith("/storage?")) storage.set(Uri.parse(path).getQueryParameter("value"));
      if (path.startsWith("/check") && cookie) cookies.incrementAndGet();
      String status = "200 OK",
          extra = "",
          body = "<title>ordinary</title><h1>seekneedle seekneedle</h1>";
      if (path.equals("/chain1")) {
        Thread.sleep(11000);
        status = "302 Found";
        extra = "Location: http://localhost:" + server.getLocalPort() + "/chain2\r\n";
      } else if (path.equals("/chain2")) {
        status = "302 Found";
        extra = "Location: http://127.0.0.2:" + server.getLocalPort() + "/callback\r\n";
      } else if (path.equals("/callback")) {
        body =
            "<title>callback-ready</title><h1>seekneedle"
                + " seekneedle</h1><script>setTimeout(()=>location.href='"
                + base
                + "/takeover',700);</script>";
      } else if (path.equals("/seed")) {
        extra = "Set-Cookie: browser_fixture=present; Path=/; HttpOnly\r\n";
        body =
            "<title>seed-ready</title><script>localStorage.setItem('browser_fixture','present');fetch('/check');</script>";
      } else if (path.equals("/check")) {
        body =
            "<title>check-ready</title><script>fetch('/storage?value='+encodeURIComponent(localStorage.getItem('browser_fixture')||''));</script>";
      }
      if (path.startsWith("/tab-")) {
        visits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        String id = path.substring(5);
        body =
            "<title>tab-"
                + id
                + "</title><script>fetch('/media?tab="
                + id
                + "').then(r=>r.arrayBuffer());</script>";
      }
      boolean mediaResponse = path.startsWith("/media?");
      byte[] bytes = mediaResponse ? media : body.getBytes(StandardCharsets.UTF_8);
      client
          .getOutputStream()
          .write(
              ("HTTP/1.1 "
                      + status
                      + "\r\nContent-Type: "
                      + (mediaResponse ? "video/mp4" : "text/html; charset=utf-8")
                      + "\r\nContent-Length: "
                      + bytes.length
                      + "\r\n"
                      + extra
                      + "Connection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().write(bytes);
    } catch (Exception ignored) {
    }
  }

  private BrowserSessionManager browser() throws Exception {
    var field = MainActivity.class.getDeclaredField("browser");
    field.setAccessible(true);
    return (BrowserSessionManager) field.get(activity);
  }

  private void open(String path) {
    activity =
        (MainActivity)
            app.startActivitySync(
                new Intent(context, MainActivity.class)
                    .setData(Uri.parse(base + path))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
  }

  private void awaitTitle(String expected) throws Exception {
    long end = System.currentTimeMillis() + 60000;
    while (System.currentTimeMillis() < end) {
      AtomicReference<String> title = new AtomicReference<>();
      app.runOnMainSync(
          () -> {
            try {
              title.set(browser().getTitle());
            } catch (Exception ignored) {
            }
          });
      if (expected.equals(title.get())) return;
      Thread.sleep(100);
    }
    fail("Browser fixture did not commit expected page");
  }

  @After
  public void stop() throws Exception {
    if (activity != null) app.runOnMainSync(() -> activity.finish());
    server.close();
    network.shutdownNow();
  }

  @Test
  public void delayedThreeHostUserChainWorksAndScriptTakeoverIsBlocked() throws Exception {
    open("/chain1");
    awaitTitle("callback-ready");
    Thread.sleep(1600);
    assertEquals("Unsolicited navigation must not reach the other host", 0, takeover.get());
    BrowserSessionManager manager = browser();
    GeckoSession original = manager.getSession();
    CountDownLatch found = new CountDownLatch(1);
    AtomicBoolean matched = new AtomicBoolean();
    app.runOnMainSync(
        () ->
            manager
                .getSession()
                .getFinder()
                .find("seekneedle", GeckoSession.FINDER_FIND_FORWARD)
                .accept(
                    result -> {
                      matched.set(result.found);
                      found.countDown();
                    },
                    error -> found.countDown()));
    assertTrue(found.await(10, TimeUnit.SECONDS));
    assertTrue(matched.get());
    app.runOnMainSync(() -> manager.setDesktop(true));
    assertSame(original, manager.getSession());
    assertTrue(manager.isDesktop());
  }

  @Test
  public void clearingHostReallyRemovesCookieAndDomStorage() throws Exception {
    open("/seed");
    awaitTitle("seed-ready");
    long end = System.currentTimeMillis() + 10000;
    while (cookies.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(50);
    assertTrue(cookies.get() > 0);
    BrowserSessionManager manager = browser();
    CountDownLatch cleared = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    app.runOnMainSync(
        () -> {
          manager.close();
          manager
              .getRuntime()
              .getStorageController()
              .clearDataFromHost(
                  "127.0.0.1",
                  StorageController.ClearFlags.SITE_DATA
                      | StorageController.ClearFlags.AUTH_SESSIONS)
              .accept(
                  done -> {
                    success.set(true);
                    cleared.countDown();
                  },
                  error -> cleared.countDown());
        });
    assertTrue(cleared.await(15, TimeUnit.SECONDS));
    assertTrue(success.get());
    int previous = cookies.get();
    storage.set(null);
    app.runOnMainSync(() -> manager.recover(base + "/check"));
    awaitTitle("check-ready");
    end = System.currentTimeMillis() + 10000;
    while (storage.get() == null && System.currentTimeMillis() < end) Thread.sleep(50);
    assertEquals("", storage.get());
    assertEquals(previous, cookies.get());
  }

  @Test
  public void fiveRetainedTabsKeepTheirOwnMediaContext() throws Exception {
    open("/tab-0");
    awaitTitle("tab-0");
    BrowserSessionManager manager = browser();
    java.util.List<GeckoSession> sessions = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      if (i > 0) {
        String target = base + "/tab-" + i;
        app.runOnMainSync(() -> assertTrue(manager.newTab(target)));
        awaitTitle("tab-" + i);
      }
      var tab = manager.tabs().get(i);
      sessions.add(tab.session);
      long end = System.currentTimeMillis() + 15000;
      while (tab.detector.snapshot().isEmpty() && System.currentTimeMillis() < end)
        Thread.sleep(50);
      assertEquals("One media response belongs to this tab", 1, tab.detector.snapshot().size());
      assertEquals(base + "/media?tab=" + i, tab.detector.snapshot().get(0).getUrl());
    }
    app.runOnMainSync(() -> assertFalse(manager.newTab(base + "/sixth")));
    for (int i = 0; i < 5; i++) {
      int index = i;
      app.runOnMainSync(() -> manager.selectTab(index));
      assertSame(sessions.get(i), manager.getSession());
      assertEquals(1, visits.get("/tab-" + i).get());
    }
    assertEquals(5, manager.tabs().size());
  }

  @Test
  public void persistedHistoryWhitelistsFieldsAndEncryptsLegacyState() throws Exception {
    String url = base + "/watch?token=PRIVATE_SIGNED_BROWSER_URL";
    String raw =
        new JSONObject()
            .put(
                "history",
                new JSONObject()
                    .put("index", 1)
                    .put(
                        "entries",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("url", url)
                                    .put("title", "Browser title")
                                    .put("structuredCloneState", "PRIVATE_STATE"))))
            .put("formdata", new JSONObject().put("password", "PRIVATE_FORM_VALUE"))
            .put("storage", "PRIVATE_STORAGE")
            .toString();
    String safe = SafeSessionState.capture(GeckoSession.SessionState.fromString(raw));
    assertNotNull(safe);
    assertTrue(safe.contains("PRIVATE_SIGNED_BROWSER_URL"));
    assertFalse(safe.contains("PRIVATE_FORM_VALUE"));
    assertFalse(safe.contains("PRIVATE_STATE"));
    assertFalse(safe.contains("PRIVATE_STORAGE"));
    context
        .getSharedPreferences("MainActivity", 0)
        .edit()
        .putString("session", raw)
        .putString("last_url", url)
        .commit();
    BrowserDataStore store = new BrowserDataStore(context);
    try {
      CountDownLatch loaded = new CountDownLatch(1);
      store.load(state -> loaded.countDown());
      assertTrue(loaded.await(15, TimeUnit.SECONDS));
      assertFalse(context.getSharedPreferences("MainActivity", 0).contains("session"));
      assertFalse(context.getSharedPreferences("MainActivity", 0).contains("last_url"));
      byte[] bytes =
          java.nio.file.Files.readAllBytes(
              new File(context.getFilesDir(), "browser-state.enc").toPath());
      String disk = new String(bytes, StandardCharsets.ISO_8859_1);
      assertFalse(disk.contains("PRIVATE_SIGNED_BROWSER_URL"));
      assertFalse(disk.contains("PRIVATE_FORM_VALUE"));
    } finally {
      store.close();
    }
  }
}
