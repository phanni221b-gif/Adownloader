package com.adownloader.app.playback;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import com.adownloader.app.download.HttpConnections;
import com.adownloader.app.download.TransferControl;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/**
 * A download-permitted HLS test environment (local server) in two configurations: normal (master,
 * AES-128 media playlist, TS segments, key) and access-restricted (the segment path answers a 403
 * HTML challenge). Verifies the stage-by-stage diagnosis, the bounded request count, that a refusal
 * stops the probe, and the comparison with the player's own requests.
 */
public class PlaybackDiagnosticsTest {
  private ServerSocket server;
  private Thread thread;
  private volatile boolean restricted;
  private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

  @Before
  public void start() throws Exception {
    HttpConnections.installBrowser(null);
    server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    thread =
        new Thread(
            () -> {
              while (!server.isClosed())
                try (Socket socket = server.accept()) {
                  BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                  String request = in.readLine();
                  String line;
                  while ((line = in.readLine()) != null && !line.isEmpty()) {}
                  String path = request == null ? "/" : request.split(" ")[1];
                  hits.computeIfAbsent(path.replaceAll("\\d+\\.ts$", "N.ts"), k -> new AtomicInteger()).incrementAndGet();
                  respond(socket.getOutputStream(), path);
                } catch (Exception stopped) {
                  if (!server.isClosed()) throw new RuntimeException(stopped);
                }
            });
    thread.start();
  }

  @After
  public void stop() throws Exception {
    PlaybackDiagnostics.discard();
    server.close();
    thread.join(2000);
  }

  private void respond(OutputStream out, String path) throws IOException {
    String status = "200 OK", type;
    byte[] body;
    if (path.equals("/live/master.m3u8")) {
      type = "application/vnd.apple.mpegurl";
      body = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\"\n"
              + "720/index.m3u8\n").getBytes(StandardCharsets.UTF_8);
    } else if (path.equals("/live/720/index.m3u8")) {
      type = "application/vnd.apple.mpegurl";
      StringBuilder p = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n");
      p.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n");
      for (int i = 0; i < 6; i++) p.append("#EXTINF:4.0,\nseg").append(i).append(".ts\n");
      p.append("#EXT-X-ENDLIST\n");
      body = p.toString().getBytes(StandardCharsets.UTF_8);
    } else if (path.startsWith("/live/720/seg")) {
      if (restricted) {
        status = "403 Forbidden";
        type = "text/html";
        body = "<!DOCTYPE html><html><title>Just a moment...</title><body>challenge</body></html>".getBytes(StandardCharsets.UTF_8);
      } else {
        type = "video/mp2t";
        body = new byte[188 * 4];
        for (int i = 0; i < 4; i++) body[i * 188] = 0x47;
      }
    } else if (path.equals("/live/720/key.bin")) {
      type = "application/octet-stream";
      body = new byte[16];
    } else {
      status = "404 Not Found";
      type = "text/plain";
      body = "missing".getBytes(StandardCharsets.UTF_8);
    }
    out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body);
  }

  private String base() {
    return "http://127.0.0.1:" + server.getLocalPort() + "/live/";
  }

  private MediaCandidate candidate() {
    return new MediaCandidate(
        base() + "master.m3u8", MediaCandidate.MediaType.HLS, "dom-media",
        "http://127.0.0.1:" + server.getLocalPort() + "/watch/1", Map.of(), 0, Map.of());
  }

  private int hit(String path) {
    AtomicInteger n = hits.get(path);
    return n == null ? 0 : n.get();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> conclusions(Map<String, Object> report) {
    return (List<Map<String, Object>>) report.get("conclusions");
  }

  private static boolean has(Map<String, Object> report, String code) {
    return conclusions(report).stream().anyMatch(c -> code.equals(c.get("code")));
  }

  @Test
  public void normalPlaybackEnvironmentVerifiesEveryStage() {
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    PlaybackProbe probe =
        PlaybackProbe.run(candidate(), session.salt, new TransferControl(), PlaybackProbe::defaultSource);
    assertEquals("completed", probe.stoppedReason);
    assertEquals("master", probe.root.playlistType);
    assertEquals(HlsInspection.Protection.AES_128, probe.media.protection);
    assertEquals(6, probe.media.segmentCount);
    List<String> kinds = probe.steps.stream().map(s -> s.stage + ":" + s.kind).toList();
    assertEquals(
        List.of("ROOT_MANIFEST:PLAYLIST_OK", "VARIANT_PLAYLIST:PLAYLIST_OK", "VIDEO_SEGMENT:MEDIA_OK",
            "VIDEO_SEGMENT:MEDIA_OK", "AES_KEY:KEY_OK"),
        kinds);
    // Bounded: one request per step, nothing repeated.
    assertEquals(1, hit("/live/master.m3u8"));
    assertEquals(1, hit("/live/720/index.m3u8"));
    assertEquals(2, hit("/live/720/segN.ts"));
    assertEquals(1, hit("/live/720/key.bin"));

    session.probe(probe);
    session.candidates(List.of(candidate()));
    session.ingest(playerBatch(session, 200, true));
    PlaybackDiagnostics.stop();
    Map<String, Object> report = session.report(Map.of("versionName", "test"));
    @SuppressWarnings("unchecked")
    Map<String, Object> stages = (Map<String, Object>) report.get("stages");
    assertEquals("detected", stages.get("candidateDetection"));
    assertEquals("verified", stages.get("manifestValidation"));
    assertEquals("verified", stages.get("segmentValidation"));
    assertEquals("progressing", stages.get("playback"));
    assertTrue(has(report, "MANIFEST_AES128"));
    assertTrue(has(report, "DOWNLOAD_SEGMENT_OK"));
    assertTrue(has(report, "SAME_STREAM"));
    assertTrue(has(report, "PATH_NATIVE_HLS"));
    assertFalse(has(report, "EME_IN_USE"));
  }

  @Test
  public void accessRestrictedSegmentsStopAtTheFirstRefusal() {
    restricted = true;
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    PlaybackProbe probe =
        PlaybackProbe.run(candidate(), session.salt, new TransferControl(), PlaybackProbe::defaultSource);
    assertEquals("segment-challenge", probe.stoppedReason);
    PlaybackProbe.Step last = probe.steps.get(probe.steps.size() - 1);
    assertEquals("VIDEO_SEGMENT", last.stage);
    assertEquals("HTML_CHALLENGE", last.kind);
    assertEquals(403, last.httpStatus);
    // The refusal is respected: no second segment, no key request.
    assertEquals(1, hit("/live/720/segN.ts"));
    assertEquals(0, hit("/live/720/key.bin"));

    session.probe(probe);
    session.candidates(List.of(candidate()));
    // The in-page player fetched the very same segments successfully, sending its cookie.
    session.ingest(playerBatch(session, 200, true));
    PlaybackDiagnostics.stop();
    Map<String, Object> report = session.report(null);
    @SuppressWarnings("unchecked")
    Map<String, Object> stages = (Map<String, Object>) report.get("stages");
    assertEquals("verified", stages.get("manifestValidation"));
    assertEquals("failed:HTML_CHALLENGE", stages.get("segmentValidation"));
    assertTrue(has(report, "DOWNLOAD_VIDEO_SEGMENT_HTML_CHALLENGE"));
    assertTrue(has(report, "REFUSAL_RESPECTED"));
    assertTrue(has(report, "SAME_STREAM_CONTEXT_DIFFERS"));
  }

  @Test
  public void aPlayerOnAnotherStreamIsReportedAsADifferentStream() {
    restricted = true;
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    session.probe(PlaybackProbe.run(candidate(), session.salt, new TransferControl(), PlaybackProbe::defaultSource));
    session.candidates(List.of(candidate()));
    Map<String, Object> batch = playerBatch(session, 200, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> net = (List<Map<String, Object>>) batch.get("net");
    for (Map<String, Object> r : net) {
      r.put("key", DiagKeys.key(session.salt, "https://cdn.other.test/x/" + r.get("ext") + ".ts"));
      r.put("host", "cdn.other.test");
    }
    session.ingest(batch);
    Map<String, Object> report = session.report(null);
    assertTrue(has(report, "DIFFERENT_STREAM"));
  }

  @Test
  public void playbackWithoutVisiblePlayerRequestsIsFlaggedAsInferred() {
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    Map<String, Object> batch = playerBatch(session, 200, false);
    batch.put("net", List.of());
    session.ingest(batch);
    Map<String, Object> report = session.report(null);
    Map<String, Object> c =
        conclusions(report).stream().filter(x -> "PLAYER_REQUESTS_NOT_VISIBLE".equals(x.get("code"))).findFirst().orElseThrow();
    assertEquals(true, c.get("inferred"));
  }

  @Test
  public void drmManifestIsNotProbedFurther() throws Exception {
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    HlsInspection fairplay =
        HlsInspection.inspect("https://a.test/v.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\ns.ts\n");
    assertEquals(HlsInspection.Protection.DRM, fairplay.protection);
    assertEquals(Set.of("FairPlay"), fairplay.drmSystems);
    HlsInspection widevine =
        HlsInspection.inspect("https://a.test/v.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,AAAA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:4,\ns.ts\n");
    assertEquals(HlsInspection.Protection.DRM, widevine.protection);
    assertEquals(Set.of("Widevine"), widevine.drmSystems);
    HlsInspection clear = HlsInspection.inspect("https://a.test/v.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST\n");
    assertEquals(HlsInspection.Protection.CLEAR, clear.protection);
    HlsInspection sample = HlsInspection.inspect("https://a.test/v.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4,\ns.ts\n");
    assertEquals(HlsInspection.Protection.SAMPLE_AES, sample.protection);
    HlsInspection session2 = HlsInspection.inspect("https://a.test/m.m3u8",
        "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n");
    assertEquals(HlsInspection.Protection.DRM, session2.protection);
    assertEquals(1, session2.sessionKeys);
  }

  @Test
  public void responsesAreSeparatedIntoChallengeAccessHttpAndFormat() {
    byte[] challenge = "<html><title>Just a moment...</title></html>".getBytes(StandardCharsets.UTF_8);
    byte[] page = "<!doctype html><html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
    byte[] ts = new byte[400];
    ts[0] = 0x47;
    ts[188] = 0x47;
    assertEquals(ResponseKind.HTML_CHALLENGE, ResponseKind.classify(403, "text/html", challenge, challenge.length, "media"));
    assertEquals(ResponseKind.HTML_CHALLENGE, ResponseKind.classify(200, "text/html", challenge, challenge.length, "media"));
    assertEquals(ResponseKind.ACCESS_DENIED, ResponseKind.classify(403, "text/html", page, page.length, "media"));
    assertEquals(ResponseKind.ACCESS_DENIED, ResponseKind.classify(401, "", new byte[0], 0, "media"));
    assertEquals(ResponseKind.NOT_FOUND, ResponseKind.classify(404, "text/plain", new byte[1], 1, "media"));
    assertEquals(ResponseKind.RATE_LIMITED, ResponseKind.classify(429, "", new byte[0], 0, "media"));
    assertEquals(ResponseKind.SERVER_ERROR, ResponseKind.classify(503, "", new byte[0], 0, "media"));
    assertEquals(ResponseKind.HTML_INSTEAD_OF_MEDIA, ResponseKind.classify(200, "text/html", page, page.length, "media"));
    assertEquals(ResponseKind.MEDIA_FORMAT_ERROR, ResponseKind.classify(200, "application/json", "{}".getBytes(), 2, "media"));
    assertEquals(ResponseKind.EMPTY_BODY, ResponseKind.classify(200, "video/mp2t", new byte[0], 0, "media"));
    assertEquals(ResponseKind.MEDIA_OK, ResponseKind.classify(200, "video/mp2t", ts, ts.length, "media"));
    byte[] disguised = new byte[1200];
    disguised[0] = (byte) 0x89; disguised[1] = 'P'; disguised[2] = 'N'; disguised[3] = 'G';
    disguised[100] = 0x47; disguised[288] = 0x47; disguised[476] = 0x47;
    assertEquals("image-prefixed-ts", ResponseKind.signature(disguised, disguised.length));
    assertEquals(ResponseKind.MEDIA_OK, ResponseKind.classify(200, "image/png", disguised, disguised.length, "media"));
    assertEquals("challenge", ResponseKind.HTML_CHALLENGE.group);
    assertEquals("access", ResponseKind.ACCESS_DENIED.group);
    assertEquals("http", ResponseKind.NOT_FOUND.group);
    assertEquals("format", ResponseKind.MEDIA_FORMAT_ERROR.group);
  }

  @Test
  public void theExportedReportCarriesNoUrlQueryOrCredential() {
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    Map<String, Object> batch = playerBatch(session, 200, true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> net = new ArrayList<>((List<Map<String, Object>>) batch.get("net"));
    Map<String, Object> planted = new LinkedHashMap<>(net.get(0));
    planted.put("host", "evil.test/?token=SECRETVALUE");
    planted.put("key", "https://evil.test/a?sid=SECRETVALUE");
    planted.put("error", "Cookie: sid=SECRETVALUE");
    net.add(planted);
    batch.put("net", net);
    @SuppressWarnings("unchecked")
    Map<String, Object> sample = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) batch.get("media")).get(0)).get("samples")).get(0);
    sample.put("errorMessage", "failed https://cdn.test/a.ts?token=SECRETVALUE");
    sample.put("debug", Map.of("decoder.videoDecoderName", "android decoder (c2.exynos.h264)", "url", "https://x.test/?a=SECRETVALUE"));
    session.ingest(batch);
    session.candidates(List.of(new MediaCandidate(
        "https://cdn.test/v/master.m3u8?token=SECRETVALUE", MediaCandidate.MediaType.HLS, "dom-media",
        "https://page.test/watch?id=SECRETVALUE", Map.of("Cookie", "sid=SECRETVALUE", "Referer", "https://page.test/"), 0)));
    String json = Json.write(session.report(Map.of("versionName", "0.8.12")));
    assertFalse(json, json.contains("SECRETVALUE"));
    assertFalse(json, json.contains("://"));
    assertFalse(json, json.matches("(?s).*[?&][A-Za-z]+=.*"));
    assertTrue(json.contains("\"host\": \"cdn.test\""));
    assertTrue(json.contains("c2.exynos.h264"));
    assertTrue(json.contains("\"headerNames\""));
    assertEquals("x", Privacy.scrub(Map.of("k", "x")).get("k"));
    assertEquals(Privacy.REDACTED, Privacy.scrub(List.of("https://a.test/")).get(0));
  }

  @Test
  public void thePollRequiresTheCurrentSessionAndEndsWithStop() {
    assertEquals(false, PlaybackDiagnostics.poll(Map.of()).get("active"));
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.start();
    Map<String, Object> state = PlaybackDiagnostics.poll(Map.of("session", "P-old", "net", List.of(Map.of("type", "media"))));
    assertEquals(true, state.get("active"));
    assertEquals(session.salt, state.get("salt"));
    assertTrue(session.netRecords().isEmpty()); // a batch from another session is ignored
    PlaybackDiagnostics.stop();
    assertEquals(false, PlaybackDiagnostics.poll(Map.of()).get("active"));
    assertEquals("", PlaybackDiagnostics.poll(Map.of()).get("salt"));
  }

  @Test
  public void keysMatchTheExtensionAlgorithm() {
    // Same vector as ci/test-playback-diag.cjs.
    assertEquals("dffc033f49b5c52b", DiagKeys.key("0123456789abcdef", "https://cdn.test/a/seg1.ts?x=1"));
    assertEquals(
        DiagKeys.key("0123456789abcdef", "https://CDN.test:443/a/seg1.ts#f"),
        DiagKeys.key("0123456789abcdef", "https://cdn.test/a/seg1.ts?other=2"));
    assertNotEquals(
        DiagKeys.key("0123456789abcdef", "https://cdn.test/a/seg1.ts"),
        DiagKeys.key("fedcba9876543210", "https://cdn.test/a/seg1.ts"));
  }

  /** What the extension sends while the player plays the test stream (6 segment requests). */
  private Map<String, Object> playerBatch(PlaybackDiagnostics.Session session, int status, boolean cookie) {
    List<Object> net = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("tab", 3);
      r.put("frame", 0);
      r.put("type", "media");
      r.put("status", status);
      r.put("mime", "video/mp2t");
      r.put("host", "127.0.0.1");
      r.put("ext", "ts");
      r.put("key", DiagKeys.key(session.salt, base() + "720/seg" + i + ".ts"));
      r.put("cookie", cookie);
      r.put("referer", true);
      net.add(r);
    }
    Map<String, Object> sample = new LinkedHashMap<>();
    sample.put("index", 0);
    sample.put("tag", "video");
    sample.put("srcKind", "http-hls");
    sample.put("srcHost", "127.0.0.1");
    sample.put("currentTime", 0.0);
    sample.put("paused", false);
    sample.put("readyState", 4);
    sample.put("bufferedAhead", 12.0);
    Map<String, Object> later = new LinkedHashMap<>(sample);
    later.put("currentTime", 9.5);
    later.put("events", Map.of("playing", 1, "waiting", 1));
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("tab", 3);
    frame.put("frame", 0);
    frame.put("top", true);
    frame.put("samples", new ArrayList<>(List.of(sample, later)));
    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put("session", session.id);
    batch.put("net", net);
    batch.put("media", new ArrayList<>(List.of(frame)));
    return batch;
  }
}
