package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.util.*;
import org.junit.*;

/**
 * Reproduction of on-device report F-764c24bf (yasyadong04.tv, VPN off): both the browser stack and
 * the direct stack failed before any HTTP response, and the report named the unrelated earlier
 * cache-miss event ("gecko-response-cache", status 0) as the failing transport, with no way to tell
 * address lookup, refusal, reset, timeout or TLS apart.
 */
public class NetworkFailureTest {
  @After
  public void stop() {
    HttpConnections.installBrowser(null);
  }

  @Test
  public void javaConnectionFailuresAreClassified() {
    assertEquals("dns", NetworkFailure.kind(new UnknownHostException("x")));
    assertEquals("timeout", NetworkFailure.kind(new SocketTimeoutException("x")));
    assertEquals("refused", NetworkFailure.kind(new ConnectException("x")));
    assertEquals("tls", NetworkFailure.kind(new javax.net.ssl.SSLHandshakeException("x")));
    assertEquals("reset", NetworkFailure.kind(new SocketException("Connection reset")));
    assertEquals("reset", NetworkFailure.kind(new IOException("wrap", new SocketException("Connection reset by peer"))));
    assertEquals("other", NetworkFailure.kind(new IOException("x")));
  }

  @Test
  public void geckoWebRequestErrorCodesMapToKinds() {
    // Values read from this GeckoView build's WebRequestError constants.
    assertEquals("dns", NetworkFailure.geckoKind(37));
    assertEquals("timeout", NetworkFailure.geckoKind(51));
    assertEquals("refused", NetworkFailure.geckoKind(67));
    assertEquals("reset", NetworkFailure.geckoKind(147));
    assertEquals("reset", NetworkFailure.geckoKind(35));
    assertEquals("tls", NetworkFailure.geckoKind(34));
    assertEquals("tls", NetworkFailure.geckoKind(50));
    assertEquals("offline", NetworkFailure.geckoKind(115));
    assertEquals("other", NetworkFailure.geckoKind(17));
    // Still a SocketException, so existing retry/fallback handling is unchanged.
    assertTrue(new NetworkFailure.Typed("m", "reset") instanceof SocketException);
    assertEquals("reset", NetworkFailure.kind(new NetworkFailure.Typed("m", "reset")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void bothStacksFailingBeforeAnyResponseAreReportedPerTransportWithTheirKind()
      throws Exception {
    // A port with nothing listening: the direct stack is refused.
    int port;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = probe.getLocalPort();
    }
    String url = "http://127.0.0.1:" + port + "/hls/50451C0C.m3u8";
    // The browser stack fails with a Gecko connection reset (WebRequestError ERROR_NET_RESET).
    HttpConnections.installBrowser(
        u ->
            new HttpURLConnection(u) {
              public void connect() {}

              public void disconnect() {}

              public boolean usingProxy() {
                return false;
              }

              public int getResponseCode() throws IOException {
                throw new NetworkFailure.Typed("Gecko request failed: 3/147", "reset");
              }
            });
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(173, "HLS", url, "http://127.0.0.1/", Map.of());
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(session);
        HttpSource source = new HttpSource(url, Map.of(), new TransferControl())) {
      DownloadError e =
          assertThrows(DownloadError.class, () -> source.document(url, null, "root"));
      assertEquals(DownloadError.Category.NETWORK, e.category);
      Map<String, Object> report = session.report(e, e.category, "DOWNLOAD_FAILURE");
      Map<String, Object> failure = (Map<String, Object>) report.get("failure");
      assertEquals("the stack that failed last is named, not an unrelated cache event", "native", failure.get("transport"));
      assertEquals("refused", failure.get("netError"));
      List<Map<String, Object>> history = (List<Map<String, Object>>) report.get("eventHistory");
      assertTrue(
          "the browser stack's connection reset is recorded",
          history.stream().anyMatch(ev -> "gecko".equals(ev.get("transport")) && "reset".equals(ev.get("netError"))));
      assertTrue(
          "the direct stack's refusal is recorded",
          history.stream().anyMatch(ev -> "native".equals(ev.get("transport")) && "refused".equals(ev.get("netError"))));
      assertFalse(report.toString().contains("50451C0C"));
    }
  }
}
