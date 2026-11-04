package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.media.MediaExtractor;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.adownloader.app.download.*;
import com.adownloader.app.jobs.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Independent authorized-session server, synthetic HLS only; no site secrets or browser keys. */
@RunWith(AndroidJUnit4.class)
public class RelaySessionIntegrationTest {
  private final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
  private final Context context = ins.getTargetContext();
  private ServerSocket listener;
  private ExecutorService io;
  private String base, root;
  private File directory;
  private MainActivity activity;
  private final List<Uri> published = new ArrayList<>();
  private final Map<String, byte[]> sessions = new ConcurrentHashMap<>();
  private final AtomicInteger handshakes = new AtomicInteger(),
      renewals = new AtomicInteger(),
      segments = new AtomicInteger(),
      low = new AtomicInteger();
  private final AtomicBoolean leaked = new AtomicBoolean();
  private volatile boolean renewSoon, tamper, deny;

  @Before
  public void setup() throws Exception {
    ins.getUiAutomation()
        .executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS")
        .close();
    listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + listener.getLocalPort();
    root = base + "/media/" + "A".repeat(40);
    directory = new File(context.getCacheDir(), "relay-fixture-" + System.nanoTime());
    assertTrue(directory.mkdirs());
    io = Executors.newCachedThreadPool();
    io.execute(
        () -> {
          while (!listener.isClosed())
            try {
              Socket s = listener.accept();
              io.execute(() -> serve(s));
            } catch (IOException stopped) {
            }
        });
  }

  @After
  public void cleanup() throws Exception {
    if (activity != null) ins.runOnMainSync(activity::finish);
    listener.close();
    io.shutdownNow();
    for (Uri u : published) context.getContentResolver().delete(u, null, null);
    for (File f : Objects.requireNonNull(directory.listFiles())) f.delete();
    directory.delete();
    for (byte[] k : sessions.values()) Arrays.fill(k, (byte) 0);
    sessions.clear();
  }

  private static byte[] text(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] hmac(byte[] k, byte[] v) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(k, "HmacSHA256"));
    return m.doFinal(v);
  }

  private static byte[] coordinate(BigInteger v) {
    byte[] a = v.toByteArray(), b = new byte[32];
    System.arraycopy(
        a, Math.max(0, a.length - 32), b, Math.max(0, 32 - a.length), Math.min(32, a.length));
    return b;
  }

  private static String encode(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private byte[] asset(String name) throws Exception {
    try (InputStream in = ins.getContext().getAssets().open("fixtures/" + name)) {
      return in.readAllBytes();
    }
  }

  private byte[] encrypt(String id, String path, byte[] clear, String protocol) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(text(protocol.toUpperCase(Locale.ROOT)));
    DataOutputStream d = new DataOutputStream(out);
    int index = 0;
    if (protocol.equals("emr1")) {
      byte[] e = frame(sessions.get(id), clear, "relay-body:v1:" + id + ":" + path);
      d.write(e);
    } else {
      for (int at = 0; at < clear.length; at += 262144) {
        byte[] e =
            frame(
                sessions.get(id),
                Arrays.copyOfRange(clear, at, Math.min(clear.length, at + 262144)),
                "relay-body:v2:" + id + ":" + path + ":" + index++ + ":data");
        d.writeInt(e.length);
        d.write(e);
      }
      byte[] end =
          frame(
              sessions.get(id),
              new byte[0],
              "relay-body:v2:" + id + ":" + path + ":" + index + ":end");
      d.writeInt(end.length);
      d.write(end);
    }
    byte[] bytes = out.toByteArray();
    if (tamper && protocol.equals("emr2")) bytes[bytes.length - 1] ^= 1;
    return bytes;
  }

  private static byte[] frame(byte[] key, byte[] clear, String aad) throws Exception {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    c.updateAAD(text(aad));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(iv);
    out.write(c.doFinal(clear));
    return out.toByteArray();
  }

  private JSONObject handshake(JSONObject body) throws Exception {
    if (body.has("renew")) {
      String id = body.getString("renew").replace("SESSION_", "");
      byte[] proof = Base64.getUrlDecoder().decode(body.getString("proof"));
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(sessions.get(id), "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(proof, 12)));
      c.updateAAD(text("renew:" + id + ":" + body.getLong("time")));
      assertEquals(
          "EMR renew request",
          new String(c.doFinal(proof, 12, proof.length - 12), StandardCharsets.UTF_8));
      renewals.incrementAndGet();
      return data(id, false);
    }
    String id = "synthetic-" + handshakes.incrementAndGet();
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = g.generateKeyPair();
    ECPublicKey pub = (ECPublicKey) pair.getPublic();
    JSONObject client = body.getJSONObject("publicKey");
    PublicKey remote =
        KeyFactory.getInstance("EC")
            .generatePublic(
                new ECPublicKeySpec(
                    new ECPoint(
                        new BigInteger(1, Base64.getUrlDecoder().decode(client.getString("x"))),
                        new BigInteger(1, Base64.getUrlDecoder().decode(client.getString("y")))),
                    pub.getParams()));
    KeyAgreement a = KeyAgreement.getInstance("ECDH");
    a.init(pair.getPrivate());
    a.doPhase(remote, true);
    byte[] shared = a.generateSecret(), prk = hmac(text(id), shared);
    sessions.put(id, hmac(prk, text("encrypted-relay-transport:v1\u0001")));
    Arrays.fill(shared, (byte) 0);
    Arrays.fill(prk, (byte) 0);
    return data(id, renewSoon)
        .put(
            "serverKey",
            new JSONObject()
                .put("kty", "EC")
                .put("crv", "P-256")
                .put("x", encode(coordinate(pub.getW().getAffineX())))
                .put("y", encode(coordinate(pub.getW().getAffineY()))));
  }

  private JSONObject data(String id, boolean soon) throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("session", "SESSION_" + id)
        .put("protocol", "emr1")
        .put("expiresAt", System.currentTimeMillis() / 1000 + (soon ? 30 : 3600));
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      if (c == 10) break;
      if (c != 13) b.write(c);
      if (b.size() > 8192) throw new IOException();
    }
    return b.toString(StandardCharsets.US_ASCII);
  }

  private void serve(Socket s) {
    try (Socket socket = s) {
      InputStream in = socket.getInputStream();
      String[] first = line(in).split(" ");
      if (first.length < 2) return;
      String path = first[1];
      Map<String, String> h = new HashMap<>();
      String l;
      while (!(l = line(in)).isEmpty()) {
        int at = l.indexOf(':');
        if (at > 0) h.put(l.substring(0, at).toLowerCase(Locale.ROOT), l.substring(at + 1).trim());
      }
      if (h.containsKey("cookie") || h.containsKey("authorization")) leaked.set(true);
      int status = 200;
      String mime = "application/octet-stream", protocol = "";
      byte[] bytes;
      if (path.endsWith("/session")) {
        byte[] body = in.readNBytes(Integer.parseInt(h.getOrDefault("content-length", "0")));
        if (deny) {
          status = 403;
          bytes = text("{}");
        } else
          bytes =
              text(handshake(new JSONObject(new String(body, StandardCharsets.UTF_8))).toString());
        mime = "application/json";
      } else if (path.equals("/watch")) {
        mime = "text/html";
        bytes =
            text(
                "<title>Authorized relay fixture</title><div data-video-url='"
                    + root
                    + "'></div><script>setTimeout(()=>fetch('"
                    + root
                    + "'),500)</script>");
      } else {
        String session = h.getOrDefault("x-playback-session", ""),
            id = session.replace("SESSION_", "");
        if (!sessions.containsKey(id)) {
          // The synthetic page supplies only an observed envelope. Download must make its own
          // session.
          if (path.equals(URI.create(root).getPath())) {
            protocol = "emr1";
            bytes = text("EMR1synthetic-playback-observation");
          } else {
            status = 403;
            bytes = text("{}");
          }
        } else {
          protocol = path.endsWith(".ts") ? "emr2" : "emr1";
          byte[] clear;
          if (path.equals(URI.create(root).getPath()))
            clear =
                text(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=640x360,FRAME-RATE=30\n"
                        + base
                        + "/media/"
                        + id
                        + "/low.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1920x1080,FRAME-RATE=60\n"
                        + base
                        + "/media/"
                        + id
                        + "/high.m3u8\n");
          else if (!path.startsWith("/media/" + id + "/")) {
            status = 403;
            clear = text("{}");
          } else if (path.endsWith(".m3u8")) {
            if (path.contains("low")) low.incrementAndGet();
            String playlist = new String(asset("playlist.m3u8"), StandardCharsets.UTF_8);
            for (int n = 0; n < 3; n++)
              playlist =
                  playlist.replace(
                      "playlist" + n + ".ts", base + "/media/" + id + "/playlist" + n + ".ts");
            clear = text(playlist);
          } else {
            segments.incrementAndGet();
            clear = asset(path.substring(path.lastIndexOf('/') + 1));
          }
          bytes = status == 200 ? encrypt(id, path, clear, protocol) : clear;
        }
      }
      OutputStream out = socket.getOutputStream();
      out.write(
          text(
              "HTTP/1.1 "
                  + status
                  + " OK\r\nContent-Type: "
                  + mime
                  + "\r\n"
                  + (protocol.isEmpty() ? "" : "X-Relay-Envelope: " + protocol + "\r\n")
                  + "Content-Length: "
                  + bytes.length
                  + "\r\nConnection: close\r\n\r\n"));
      out.write(bytes);
    } catch (Throwable ignored) {
    }
  }

  private MediaCandidate candidate() {
    return new MediaCandidate(
        root,
        MediaCandidate.MediaType.RELAY_HLS,
        "protected-response",
        base + "/watch",
        Map.of(
            "Cookie",
            "PRIVATE_COOKIE",
            "Authorization",
            "PRIVATE_AUTH",
            "X-Playback-Session",
            "OLD_BROWSER_SESSION"),
        System.currentTimeMillis(),
        Map.of("transportProtection", "encrypted-relay"));
  }

  private void validate(File f) throws Exception {
    MediaExtractor e = new MediaExtractor();
    try {
      e.setDataSource(f.getPath());
      assertTrue(e.getTrackCount() > 0);
      assertTrue(f.length() > 1000);
    } finally {
      e.release();
    }
  }

  @Test
  public void previewAndDownloadOwnDifferentSessionsAndSelectHighestOriginal() throws Exception {
    var preview = MediaProbeService.shared().preview(candidate());
    assertTrue(preview.tracks().stream().anyMatch(t -> t.height == 1080));
    DownloadEngine engine = new DownloadEngine(directory, (t, p) -> {});
    try {
      var result =
          engine.download(root, MediaCandidate.MediaType.RELAY_HLS, candidate().getHeaders());
      validate(result.file);
      assertTrue(handshakes.get() >= 2);
      assertEquals(0, low.get());
      assertEquals(3, segments.get());
      assertFalse(leaked.get());
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void sessionRenewalUsesAuthenticatedProofAndOriginalSession() throws Exception {
    renewSoon = true;
    DownloadEngine engine = new DownloadEngine(directory, (t, p) -> {});
    try {
      validate(engine.download(root, MediaCandidate.MediaType.RELAY_HLS, Map.of()).file);
      assertEquals(1, handshakes.get());
      assertTrue(renewals.get() > 0);
      assertEquals(3, segments.get());
    } finally {
      engine.cleanup();
    }
  }

  @Test
  public void deniedSessionAndModifiedTerminalTagCannotProduceCompletedOutput() throws Exception {
    deny = true;
    try (HttpSource source = new HttpSource(root, Map.of()).relay(true)) {
      assertThrows(DownloadError.class, () -> source.document(root));
    }
    assertEquals(0, segments.get());
    deny = false;
    tamper = true;
    DownloadEngine engine = new DownloadEngine(directory, (t, p) -> {});
    try {
      assertThrows(
          Exception.class,
          () -> engine.download(root, MediaCandidate.MediaType.RELAY_HLS, Map.of()));
    } finally {
      engine.cleanup();
    }
    assertEquals(0, Objects.requireNonNull(directory.listFiles()).length);
  }

  @Test
  public void geckoDetectionEnablesSessionHlsAndJobCompletesInMediaStore() throws Exception {
    activity =
        (MainActivity)
            ins.startActivitySync(
                new Intent(context, MainActivity.class)
                    .setData(Uri.parse(base + "/watch"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    var field = MainActivity.class.getDeclaredField("browser");
    field.setAccessible(true);
    var browser = (com.adownloader.app.browser.BrowserSessionManager) field.get(activity);
    MediaCandidate item = null;
    long end = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < end) {
      var found =
          browser.getDetector().snapshot().stream()
              .filter(c -> c.getType() == MediaCandidate.MediaType.RELAY_HLS)
              .findFirst();
      if (found.isPresent()) {
        item = browser.getDetector().forDownload(found.get());
        break;
      }
      Thread.sleep(100);
    }
    assertNotNull(item);
    ins.runOnMainSync(() -> activity.findViewById(R.id.media_action).performClick());
    UiDevice device = UiDevice.getInstance(ins);
    assertTrue(device.wait(Until.hasObject(By.text("세션 HLS")), 5000));
    assertTrue(device.findObject(By.res(context.getPackageName(), "media_download")).isEnabled());
    DownloadJobs jobs = DownloadJobs.get(context);
    CountDownLatch ready = new CountDownLatch(1);
    jobs.whenReady(ready::countDown);
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    AtomicLong id = new AtomicLong(-1);
    CountDownLatch saved = new CountDownLatch(1);
    jobs.enqueue(
        item,
        v -> {
          id.set(v);
          saved.countDown();
        });
    assertTrue(saved.await(10, TimeUnit.SECONDS));
    assertTrue(id.get() > 0);
    DownloadJobStore.Job job = null;
    end = System.currentTimeMillis() + 40000;
    while (System.currentTimeMillis() < end) {
      job = jobs.store.get(id.get());
      if (Set.of(
              DownloadJobStore.State.COMPLETED,
              DownloadJobStore.State.FAILED,
              DownloadJobStore.State.NEEDS_REFRESH)
          .contains(job.state)) break;
      Thread.sleep(100);
    }
    assertEquals("error=" + job.error, DownloadJobStore.State.COMPLETED, job.state);
    published.add(Uri.parse(job.uri));
    assertFalse(jobs.store.request(id.get()).toString().contains("SESSION_synthetic"));
    assertEquals(0, low.get());
    File proof = new File("/sdcard/Download/Adownloader-ui-proof/relay-session-download.png");
    proof.getParentFile().mkdirs();
    var screenshot = ins.getUiAutomation().takeScreenshot();
    try (var out = new FileOutputStream(proof)) {
      assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      screenshot.recycle();
    }
  }
}
