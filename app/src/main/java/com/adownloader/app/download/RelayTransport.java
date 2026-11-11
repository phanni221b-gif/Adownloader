package com.adownloader.app.download;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/**
 * Client-owned public playback session. Never imports browser keys or persistent session secrets.
 */
public final class RelayTransport implements AutoCloseable {
  private final URI root;
  private final TransferControl control;
  private final Map<String, String> headers = new LinkedHashMap<>();
  private byte[] key;
  private String id, session;
  private long expires;
  private boolean legacy;

  public static boolean supportedRoot(String url) {
    try {
      URI u = URI.create(url);
      boolean secure = "https".equals(u.getScheme());
      boolean fixture = "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost());
      return (secure || fixture)
          && u.getHost() != null
          && u.getUserInfo() == null
          && u.getRawQuery() == null
          && u.getRawFragment() == null
          && u.getRawPath()
              .matches("/media/[A-Za-z0-9_-]{40,7000}(?:[.]shtml|/index[.]m3u8|/resource)?");
    } catch (Exception invalid) {
      return false;
    }
  }

  public RelayTransport(String url, Map<String, String> requestHeaders, TransferControl control) {
    if (!supportedRoot(url))
      throw new IllegalArgumentException("Unsupported playback session address");
    root = URI.create(url);
    this.control = control;
    // Carry the browser's own captured request context for this media URL — the same
    // authenticated context (Cookie, Authorization, x-* session/token headers) the normal HLS/DASH
    // transport forwards via MediaHeaders.select. The client-owned ECDH session below stands on its
    // own generated keypair and never imports a browser key or DRM secret; this is only the user's
    // own HTTP login, which the origin itself requires before it will route the handshake at all.
    // connect() restricts every relay request to the root's origin, so these only ever return to
    // the origin that issued them. Dropping them (the earlier user-agent/origin/referer subset)
    // made login-gated content — e.g. a /premium/ page the user is signed in to and already
    // playing — answer the session/resource request with 403 (ACCESS_BLOCKED).
    headers.putAll(MediaHeaders.select(requestHeaders));
  }

  /** The request headers this transport forwards to the root origin; for tests. */
  Map<String, String> forwardedHeaders() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }

  public void page(String page) {
    if (page == null) return;
    try {
      URI u = URI.create(page);
      if (u.getHost() != null
          && Set.of("http", "https").contains(u.getScheme())
          && u.getUserInfo() == null) {
        headers.putIfAbsent("Origin", u.getScheme() + "://" + u.getRawAuthority());
        headers.putIfAbsent("Referer", u.resolve("/").toString());
      }
    } catch (Exception ignored) {
    }
  }

  private HttpURLConnection connect(URI url) throws Exception {
    control.check();
    if (!MediaHeaders.origin(root.toString()).equals(MediaHeaders.origin(url.toString()))
        || url.getUserInfo() != null
        || url.getRawFragment() != null
        || !url.getRawPath().startsWith("/media/"))
      throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
    HttpURLConnection c = (HttpURLConnection) url.toURL().openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(20000);
    c.setReadTimeout(45000);
    c.setRequestProperty("Accept-Encoding", "identity");
    headers.forEach(c::setRequestProperty);
    control.attach(c);
    return c;
  }

  /** The authorized handshake endpoint for a relay root, independent of its path form. */
  static URI sessionEndpoint(URI root) {
    return URI.create(
        root.toString().replaceFirst("(?:[.]shtml|/index[.]m3u8|/resource)$", "") + "/session");
  }

  private JSONObject post(JSONObject body) throws Exception {
    URI endpoint = sessionEndpoint(root);
    HttpURLConnection c = connect(endpoint);
    try {
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(bytes);
      }
      int status = c.getResponseCode();
      if (status != 200)
        throw DownloadError.http(
            status, endpoint.toString(), false, false, System.currentTimeMillis());
      try (InputStream in = c.getInputStream()) {
        return new JSONObject(new String(bounded(in, 65536), StandardCharsets.UTF_8));
      }
    } finally {
      control.detach(c);
      c.disconnect();
    }
  }

  private static byte[] bounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + (long) n > max) throw new IOException("Playback response limit");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  private static String encode(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static byte[] coordinate(BigInteger v) {
    byte[] a = v.toByteArray(), b = new byte[32];
    System.arraycopy(
        a, Math.max(0, a.length - 32), b, Math.max(0, 32 - a.length), Math.min(32, a.length));
    return b;
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] hmac(byte[] key, byte[] data) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(key, "HmacSHA256"));
    return m.doFinal(data);
  }

  private void accept(JSONObject data) throws Exception {
    String nextId = data.getString("id"), nextSession = data.getString("session");
    long nextExpiry = data.getLong("expiresAt");
    if (!"emr1".equals(data.optString("protocol"))
        || !nextId.matches("[A-Za-z0-9_-]{1,128}")
        || nextSession.isEmpty()
        || nextSession.length() > 32768
        || nextSession.chars().anyMatch(c -> c < 33 || c > 126)
        || nextExpiry <= System.currentTimeMillis() / 1000
        || nextExpiry > System.currentTimeMillis() / 1000 + 86400)
      throw new IOException("Invalid playback session");
    if (id != null && !id.equals(nextId))
      throw new IOException("Playback renewal changed identity");
    id = nextId;
    session = nextSession;
    expires = nextExpiry;
  }

  private void ready() throws Exception {
    control.check();
    if (legacy) return;
    if (key != null && expires > System.currentTimeMillis() / 1000 + 60) return;
    if (key != null) {
      long time = System.currentTimeMillis() / 1000;
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      c.updateAAD(utf8("renew:" + id + ":" + time));
      byte[] encrypted = c.doFinal(utf8("EMR renew request"));
      byte[] proof = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, proof, 0, 12);
      System.arraycopy(encrypted, 0, proof, 12, encrypted.length);
      accept(
          post(
              new JSONObject()
                  .put("renew", session)
                  .put("time", time)
                  .put("proof", encode(proof))));
      return;
    }
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair own = gen.generateKeyPair();
    ECPublicKey pub = (ECPublicKey) own.getPublic();
    JSONObject jwk =
        new JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", encode(coordinate(pub.getW().getAffineX())))
            .put("y", encode(coordinate(pub.getW().getAffineY())));
    JSONObject data = post(new JSONObject().put("publicKey", jwk));
    if ("hls".equals(data.optString("protocol"))) {
      legacy = true;
      return;
    }
    accept(data);
    JSONObject remote = data.getJSONObject("serverKey");
    if (!"EC".equals(remote.optString("kty")) || !"P-256".equals(remote.optString("crv")))
      throw new IOException("Invalid playback public key");
    byte[] x = Base64.getUrlDecoder().decode(remote.getString("x")),
        y = Base64.getUrlDecoder().decode(remote.getString("y"));
    if (x.length != 32 || y.length != 32) throw new IOException("Invalid playback coordinate");
    PublicKey server =
        KeyFactory.getInstance("EC")
            .generatePublic(
                new ECPublicKeySpec(
                    new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), pub.getParams()));
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(own.getPrivate());
    agreement.doPhase(server, true);
    byte[] shared = agreement.generateSecret(), prk = hmac(utf8(id), shared);
    byte[] info = utf8("encrypted-relay-transport:v1\u0001");
    try {
      key = hmac(prk, info);
    } finally {
      Arrays.fill(shared, (byte) 0);
      Arrays.fill(prk, (byte) 0);
    }
  }

  public HttpURLConnection open(String url, long offset, long length) throws Exception {
    if (offset != 0 || length > 0)
      throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
    ready();
    URI u = URI.create(url);
    HttpURLConnection raw = connect(u);
    try {
      if (!legacy) raw.setRequestProperty("X-Playback-Session", session);
      int status = raw.getResponseCode();
      if (status != 200)
        throw DownloadError.http(status, url, true, false, System.currentTimeMillis());
      if (legacy) return raw;
      String envelope = raw.getHeaderField("X-Relay-Envelope");
      if (!"emr1".equals(envelope) && !"emr2".equals(envelope))
        throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
      InputStream clear =
          new ClearInput(raw.getInputStream(), envelope, id, u.getRawPath(), key, control);
      HttpURLConnection response =
          new HttpURLConnection(u.toURL()) {
            public void connect() {}

            public boolean usingProxy() {
              return false;
            }

            public void disconnect() {
              try {
                clear.close();
              } catch (IOException ignored) {
              }
              control.detach(this);
              control.detach(raw);
              raw.disconnect();
            }

            public int getResponseCode() {
              return 200;
            }

            public InputStream getInputStream() {
              return clear;
            }

            public long getContentLengthLong() {
              return -1;
            }

            public String getHeaderField(String name) {
              return Set.of(
                          "content-length",
                          "content-range",
                          "etag",
                          "last-modified",
                          "x-relay-envelope")
                      .contains(name.toLowerCase(Locale.ROOT))
                  ? null
                  : raw.getHeaderField(name);
            }
          };
      control.detach(raw);
      control.attach(response);
      return response;
    } catch (Exception e) {
      control.detach(raw);
      raw.disconnect();
      control.check();
      throw e;
    }
  }

  /** EMR2 authenticates one <=256KiB frame at a time and requires the terminal tag. */
  static final class ClearInput extends InputStream {
    private final DataInputStream in;
    private final String envelope, id, path;
    private final byte[] key;
    private final TransferControl control;
    private byte[] clear = new byte[0];
    private int at, index;
    private boolean ended, started;

    ClearInput(
        InputStream input,
        String envelope,
        String id,
        String path,
        byte[] key,
        TransferControl control) {
      in = new DataInputStream(input);
      this.envelope = envelope;
      this.id = id;
      this.path = path;
      this.key = key.clone();
      this.control = control;
    }

    private boolean next() throws IOException {
      control.check();
      if (ended) return false;
      try {
        if (!started) {
          started = true;
          byte[] magic = new byte[4];
          in.readFully(magic);
          if (!new String(magic, StandardCharsets.US_ASCII)
              .equals(envelope.toUpperCase(Locale.ROOT)))
            throw new IOException("Playback envelope mismatch");
        }
        boolean last = false;
        byte[] encrypted, iv;
        String aad;
        if (envelope.equals("emr1")) {
          iv = new byte[12];
          in.readFully(iv);
          encrypted = bounded(in, 8388608 + 16);
          aad = "relay-body:v1:" + id + ":" + path;
          last = true;
        } else {
          int length = in.readInt();
          if (length < 28 || length > 256 * 1024 + 28)
            throw new IOException("Invalid playback frame length");
          iv = new byte[12];
          in.readFully(iv);
          encrypted = new byte[length - 12];
          in.readFully(encrypted);
          last = length == 28;
          aad = "relay-body:v2:" + id + ":" + path + ":" + index++ + ":" + (last ? "end" : "data");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(utf8(aad));
        clear = cipher.doFinal(encrypted);
        at = 0;
        if (last) {
          if (in.read() != -1) throw new IOException("Trailing playback data");
          ended = true;
        }
        if (!envelope.equals("emr1") && last && clear.length != 0)
          throw new IOException("Invalid terminal playback frame");
        return clear.length > 0;
      } catch (GeneralSecurityException | IllegalArgumentException bad) {
        throw new IOException("Playback authentication failed");
      }
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) return 0;
      while (at == clear.length) {
        Arrays.fill(clear, (byte) 0);
        if (!next()) return -1;
      }
      int n = Math.min(len, clear.length - at);
      System.arraycopy(clear, at, b, off, n);
      at += n;
      return n;
    }

    public void close() throws IOException {
      Arrays.fill(key, (byte) 0);
      Arrays.fill(clear, (byte) 0);
      in.close();
    }
  }

  public void close() {
    if (key != null) Arrays.fill(key, (byte) 0);
    key = null;
    id = null;
    session = null;
    expires = 0;
  }
}
