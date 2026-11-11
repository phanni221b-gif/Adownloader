package com.adownloader.app.browser;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import org.mozilla.geckoview.*;

/**
 * HttpURLConnection facade over {@link GeckoWebExecutor}. Redirects are not followed so the caller
 * keeps validating every hop. A Cookie request header is never forwarded: Gecko attaches its own,
 * current cookies, which is exactly what playback used. The body is streamed, never buffered.
 */
public final class GeckoHttpConnection extends HttpURLConnection {
  private final GeckoRuntime runtime;
  private final List<String> keys = new ArrayList<>(), values = new ArrayList<>();
  private InputStream body;
  private ByteArrayOutputStream requestBody;
  private volatile boolean closed;

  public GeckoHttpConnection(URL url, GeckoRuntime runtime) {
    super(url);
    this.runtime = runtime;
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (closed) throw new SocketException("Connection closed");
    if (connected) throw new ProtocolException("Cannot write a request body after connecting");
    doOutput = true;
    if (requestBody == null) requestBody = new ByteArrayOutputStream();
    return requestBody;
  }

  @Override
  public void connect() throws IOException {
    if (connected) return;
    if (closed) throw new SocketException("Connection closed");
    WebRequest.Builder request =
        new WebRequest.Builder(url.toString())
            .method(getRequestMethod())
            .cacheMode(WebRequest.CACHE_MODE_NO_STORE);
    for (Map.Entry<String, List<String>> header : getRequestProperties().entrySet()) {
      String name = header.getKey();
      if (name == null || header.getValue() == null || header.getValue().isEmpty()) continue;
      String lower = name.toLowerCase(Locale.ROOT);
      String value = String.join(", ", header.getValue());
      if (lower.equals("cookie")) continue;
      if (lower.equals("referer")) request.referrer(value);
      else request.header(name, value);
    }
    if (requestBody != null) {
      byte[] bytes = requestBody.toByteArray();
      if (bytes.length > 0) {
        // GeckoWebExecutor requires a direct buffer for the request body.
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        request.body(buffer);
      }
    }
    long wait =
        Math.max(1000, getConnectTimeout() > 0 ? getConnectTimeout() : 20_000)
            + Math.max(1000, getReadTimeout() > 0 ? getReadTimeout() : 45_000);
    WebResponse response;
    GeckoResult<WebResponse> pending =
        new GeckoWebExecutor(runtime)
            .fetch(request.build(), GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS);
    long deadline = System.currentTimeMillis() + wait;
    try {
      while (true) {
        try {
          response = pending.poll(250);
          break;
        } catch (TimeoutException slice) {
          // Pause/cancel disconnects from another thread; do not wait out the full timeout.
          if (closed) {
            pending.cancel();
            throw new SocketException("Connection closed");
          }
          if (System.currentTimeMillis() > deadline) {
            pending.cancel();
            throw new SocketTimeoutException("Gecko request timed out");
          }
        }
      }
    } catch (WebRequestError failure) {
      throw new SocketException("Gecko request failed: " + failure.category + "/" + failure.code);
    } catch (IOException io) {
      throw io;
    } catch (Throwable unexpected) {
      throw new IOException("Gecko request failed", unexpected);
    }
    if (response == null) throw new SocketException("Gecko returned no response");
    response.setReadTimeoutMillis(getReadTimeout() > 0 ? getReadTimeout() : 45_000);
    responseCode = response.statusCode;
    boolean decoded = false;
    for (Map.Entry<String, String> header : response.headers.entrySet())
      if ("content-encoding".equalsIgnoreCase(header.getKey())
          && !"identity".equalsIgnoreCase(header.getValue().trim())) decoded = true;
    for (Map.Entry<String, String> header : response.headers.entrySet()) {
      // Gecko already removed the content coding, so the wire length no longer describes the body.
      if (decoded
          && ("content-length".equalsIgnoreCase(header.getKey())
              || "content-encoding".equalsIgnoreCase(header.getKey()))) continue;
      keys.add(header.getKey());
      values.add(header.getValue());
    }
    body = response.body;
    connected = true;
    if (closed) disconnect();
  }

  @Override
  public int getResponseCode() throws IOException {
    connect();
    return responseCode;
  }

  @Override
  public String getHeaderField(String name) {
    try {
      connect();
    } catch (IOException unavailable) {
      return null;
    }
    if (name == null) return null;
    for (int i = keys.size() - 1; i >= 0; i--) if (name.equalsIgnoreCase(keys.get(i))) return values.get(i);
    return null;
  }

  @Override
  public String getHeaderFieldKey(int n) {
    try {
      connect();
    } catch (IOException unavailable) {
      return null;
    }
    return n > 0 && n <= keys.size() ? keys.get(n - 1) : null;
  }

  @Override
  public String getHeaderField(int n) {
    try {
      connect();
    } catch (IOException unavailable) {
      return null;
    }
    if (n == 0) return "HTTP/1.1 " + responseCode;
    return n > 0 && n <= values.size() ? values.get(n - 1) : null;
  }

  @Override
  public Map<String, List<String>> getHeaderFields() {
    try {
      connect();
    } catch (IOException unavailable) {
      return Collections.emptyMap();
    }
    Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < keys.size(); i++)
      out.computeIfAbsent(keys.get(i), k -> new ArrayList<>()).add(values.get(i));
    return Collections.unmodifiableMap(out);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    connect();
    if (responseCode >= 400) throw new FileNotFoundException(url.toString());
    return body == null ? new ByteArrayInputStream(new byte[0]) : body;
  }

  @Override
  public InputStream getErrorStream() {
    return connected && responseCode >= 400 ? body : null;
  }

  @Override
  public void disconnect() {
    closed = true;
    InputStream current = body;
    if (current != null)
      try {
        current.close();
      } catch (IOException ignored) {
      }
  }

  @Override
  public boolean usingProxy() {
    return false;
  }
}
