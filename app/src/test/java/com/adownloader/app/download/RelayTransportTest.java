package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.Test;

public class RelayTransportTest {
  private final byte[] key = new byte[32];
  private final String id = "synthetic-session", path = "/media/synthetic";

  private byte[] encrypted(byte[] clear, String aad) throws Exception {
    byte[] iv = new byte[12];
    new java.security.SecureRandom().nextBytes(iv);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(iv);
    out.write(c.doFinal(clear));
    return out.toByteArray();
  }

  private byte[] envelope(byte[] clear, boolean terminal) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write("EMR2".getBytes(StandardCharsets.US_ASCII));
    DataOutputStream d = new DataOutputStream(out);
    int index = 0;
    for (int at = 0; at < clear.length; at += 262144) {
      byte[] part = Arrays.copyOfRange(clear, at, Math.min(clear.length, at + 262144));
      byte[] e = encrypted(part, "relay-body:v2:" + id + ":" + path + ":" + index++ + ":data");
      d.writeInt(e.length);
      d.write(e);
    }
    if (terminal) {
      byte[] e = encrypted(new byte[0], "relay-body:v2:" + id + ":" + path + ":" + index + ":end");
      d.writeInt(e.length);
      d.write(e);
    }
    return out.toByteArray();
  }

  private byte[] read(byte[] input, String protocol) throws Exception {
    try (var in =
        new RelayTransport.ClearInput(
            new ByteArrayInputStream(input), protocol, id, path, key, new TransferControl())) {
      return in.readAllBytes();
    }
  }

  @Test
  public void authenticatedFramesAreByteExactAcrossFrameBoundaries() throws Exception {
    byte[] clear = new byte[900001];
    new Random(42).nextBytes(clear);
    assertArrayEquals(clear, read(envelope(clear, true), "emr2"));
  }

  @Test
  public void authenticatedSingleEnvelopeWorks() throws Exception {
    byte[] clear = "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.write("EMR1".getBytes(StandardCharsets.US_ASCII));
    b.write(encrypted(clear, "relay-body:v1:" + id + ":" + path));
    assertArrayEquals(clear, read(b.toByteArray(), "emr1"));
  }

  @Test
  public void missingTerminalFrameCannotFinish() throws Exception {
    assertThrows(IOException.class, () -> read(envelope(new byte[100], false), "emr2"));
  }

  @Test
  public void truncatedTagCannotFinish() throws Exception {
    byte[] e = envelope(new byte[300], true);
    assertThrows(IOException.class, () -> read(Arrays.copyOf(e, e.length - 1), "emr2"));
  }

  @Test
  public void modifiedCiphertextCannotBePublished() throws Exception {
    byte[] e = envelope(new byte[300], true);
    e[30] ^= 1;
    assertThrows(IOException.class, () -> read(e, "emr2"));
  }

  @Test
  public void wrongSessionAndPathCannotAuthenticate() throws Exception {
    byte[] e = envelope(new byte[300], true);
    try (var in =
        new RelayTransport.ClearInput(
            new ByteArrayInputStream(e), "emr2", "other", path, key, new TransferControl())) {
      assertThrows(IOException.class, in::readAllBytes);
    }
  }

  @Test
  public void trailingBytesAndInvalidFrameLengthsAreRejected() throws Exception {
    byte[] e = envelope(new byte[300], true);
    assertThrows(IOException.class, () -> read(Arrays.copyOf(e, e.length + 1), "emr2"));
    e[4] = 0x7f;
    assertThrows(IOException.class, () -> read(e, "emr2"));
  }

  @Test
  public void cancellationInterruptsFrameReader() throws Exception {
    TransferControl c = new TransferControl();
    c.stop(TransferControl.Stop.CANCEL);
    try (var in =
        new RelayTransport.ClearInput(
            new ByteArrayInputStream(envelope(new byte[300], true)), "emr2", id, path, key, c)) {
      assertThrows(TransferControl.Stopped.class, in::readAllBytes);
    }
  }

  @Test
  public void forwardsTheBrowsersOwnAuthenticatedContextToTheRootOrigin() {
    // Login-gated content (a /premium/ page the user is signed in to) answers a relay session that
    // lacks the user's own login cookie with 403 -> ACCESS_BLOCKED. The transport must forward the
    // same authenticated context the normal HLS/DASH transport does — the browser's captured
    // Cookie/Authorization/x-* session headers for this media URL — not a subset that drops them.
    String token = "A".repeat(40);
    Map<String, String> captured = new LinkedHashMap<>();
    captured.put("Cookie", "SESSIONID=user-own-login; member=1");
    captured.put("Authorization", "Bearer user-own-token");
    captured.put("X-Playback-Session", "captured-session");
    captured.put("User-Agent", "GeckoView");
    captured.put("Referer", "https://cdn.example/player");
    Map<String, String> forwarded =
        new RelayTransport(
                "https://cdn.example/media/" + token + "/resource", captured, new TransferControl())
            .forwardedHeaders();
    assertEquals("SESSIONID=user-own-login; member=1", forwarded.get("Cookie"));
    assertEquals("Bearer user-own-token", forwarded.get("Authorization"));
    assertEquals("captured-session", forwarded.get("X-Playback-Session"));
    assertEquals("GeckoView", forwarded.get("User-Agent"));
    assertEquals("https://cdn.example/player", forwarded.get("Referer"));
  }

  @Test
  public void onlyCredentiallessSecureRootOrLoopbackFixtureCanStartSession() {
    String token = "A".repeat(40);
    assertTrue(RelayTransport.supportedRoot("https://cdn.example/media/" + token));
    assertTrue(RelayTransport.supportedRoot("http://127.0.0.1:1234/media/" + token));
    // Path forms this provider actually serves the authorized relay root under.
    assertTrue(RelayTransport.supportedRoot("https://cdn.example/media/" + token + ".shtml"));
    assertTrue(RelayTransport.supportedRoot("https://cdn.example/media/" + token + "/index.m3u8"));
    assertTrue(
        "The observed /media/<token>/resource root is the same authorized relay protocol",
        RelayTransport.supportedRoot("https://cdn.example/media/" + token + "/resource"));
    // Every supported root form derives the same /session handshake endpoint.
    for (String form : List.of("", ".shtml", "/index.m3u8", "/resource"))
      assertEquals(
          "https://cdn.example/media/" + token + "/session",
          RelayTransport.sessionEndpoint(
                  java.net.URI.create("https://cdn.example/media/" + token + form))
              .toString());
    for (String u :
        List.of(
            "http://cdn.example/media/" + token,
            "https://u:p@cdn.example/media/" + token,
            "https://cdn.example/media/" + token + "?x=1",
            "https://cdn.example/media/" + token + "/segment",
            "https://cdn.example/opaque")) assertFalse(u, RelayTransport.supportedRoot(u));
  }
}
