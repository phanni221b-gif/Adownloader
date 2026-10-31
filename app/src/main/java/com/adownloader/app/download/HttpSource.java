package com.adownloader.app.download;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded manifest/segment requests, checked byte counts, explicit safe redirects. */
public final class HttpSource {
  private final RequestContext context;
  private final String rootUrl;
  private volatile String responseDisposition = "";

  public String disposition() {
    return responseDisposition;
  }

  private final TransferControl control;

  public HttpSource(String source, Map<String, String> headers) {
    this(source, headers, new TransferControl());
  }

  public HttpSource(String source, Map<String, String> headers, TransferControl control) {
    rootUrl = source;
    context = new RequestContext(source, headers);
    this.control = control;
  }

  public void release(HttpURLConnection connection) {
    control.detach(connection);
    connection.disconnect();
  }

  public HttpURLConnection resume(String url, long offset, String validator) throws Exception {
    return request(url, offset, -1, validator, true);
  }

  public HttpURLConnection open(String url, long offset, long length) throws Exception {
    return request(url, offset, length, null, false);
  }

  private HttpURLConnection request(
      String url, long offset, long length, String validator, boolean resume) throws Exception {
    return request(url, offset, length, validator, resume, null);
  }

  private HttpURLConnection request(
      String url, long offset, long length, String validator, boolean resume, Document previous)
      throws Exception {
    control.check();
    String current = url;
    for (int hop = 0; hop < 8; hop++) {
      Map<String, String> headers = context.forUrl(current);
      HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
      control.attach(connection);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(20_000);
      connection.setReadTimeout(45_000);
      connection.setRequestProperty("Accept-Encoding", "identity");
      headers.forEach(connection::setRequestProperty);
      if (previous != null && current.equals(previous.url)) {
        if (!previous.etag.isEmpty()) connection.setRequestProperty("If-None-Match", previous.etag);
        else if (!previous.modified.isEmpty())
          connection.setRequestProperty("If-Modified-Since", previous.modified);
      }
      if (resume && offset > 0) {
        connection.setRequestProperty("Range", "bytes=" + offset + "-");
        if (validator != null && !validator.isEmpty())
          connection.setRequestProperty("If-Range", validator);
      }
      if (length > 0)
        connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
      int code;
      try {
        code = connection.getResponseCode();
      } catch (Exception error) {
        release(connection);
        control.check();
        throw error;
      }
      if (previous != null && code == 304 && current.equals(previous.url)) return connection;
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        if (location == null) throw new IOException("리다이렉트 주소 없음");
        String next = URI.create(current).resolve(location).toString();
        if (current.startsWith("https:") && !next.startsWith("https:"))
          throw new IOException("안전하지 않은 리다이렉트");
        current = next;
        continue;
      }
      if (code < 200
          || code > 299
          || (length > 0 && code != 206)
          || (!resume && length <= 0 && code == 206)) {
        connection.disconnect();
        throw DownloadError.http(code);
      }
      if (url.equals(rootUrl)) {
        String disposition = connection.getHeaderField("Content-Disposition");
        if (disposition != null && disposition.length() <= 4096) responseDisposition = disposition;
      }
      if (length > 0) {
        String range = connection.getHeaderField("Content-Range");
        if (range == null
            || !range.startsWith("bytes " + offset + "-" + (offset + length - 1) + "/")) {
          connection.disconnect();
          throw new IOException("세그먼트 범위 불일치");
        }
      }
      return connection;
    }
    throw new IOException("리다이렉트 횟수 초과");
  }

  public byte[] bytes(String url, long offset, long length, int limit) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      HttpURLConnection connection = null;
      try {
        connection = open(url, offset, length);
        long expected = connection.getContentLengthLong();
        if (expected > limit || length > limit) throw new IOException("세그먼트 크기 제한 초과");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
          byte[] buffer = new byte[64 * 1024];
          int count;
          while ((count = in.read(buffer)) != -1) {
            control.check();
            if (out.size() + (long) count > limit) throw new IOException("응답 크기 제한 초과");
            out.write(buffer, 0, count);
          }
        }
        byte[] data = out.toByteArray();
        if (data.length == 0
            || (expected >= 0 && data.length != expected)
            || (length > 0 && data.length != length)) throw new IOException("누락되거나 잘린 세그먼트");
        return data;
      } catch (Exception error) {
        control.check();
        if (error instanceof DownloadError) throw error;
        last = error;
        if (attempt < 2) Thread.sleep(300L * (attempt + 1));
      } finally {
        if (connection != null) connection.disconnect();
      }
    }
    throw last;
  }

  public static final class Document {
    public final String url, text, etag, modified, mime, disposition;
    public final long fetchedAt, length;

    Document(String url, String text, HttpURLConnection response, Document previous) {
      this.url = url;
      this.text = text;
      etag = header(response, "ETag", previous == null ? "" : previous.etag);
      modified = header(response, "Last-Modified", previous == null ? "" : previous.modified);
      mime = header(response, "Content-Type", previous == null ? "" : previous.mime);
      disposition =
          header(response, "Content-Disposition", previous == null ? "" : previous.disposition);
      fetchedAt = System.currentTimeMillis();
      length = response.getContentLengthLong();
    }

    private static String header(HttpURLConnection response, String name, String fallback) {
      String value = response.getHeaderField(name);
      return value == null ? fallback : value;
    }
  }

  public Document document(String url) throws Exception {
    return document(url, null);
  }

  public Document document(String url, Document previous) throws Exception {
    HttpURLConnection connection = request(url, 0, -1, null, false, previous);
    try {
      String effective = connection.getURL().toString();
      if (connection.getResponseCode() == 304 && previous != null)
        return new Document(effective, previous.text, connection, previous);
      long expected = connection.getContentLengthLong();
      if (expected > 8 * 1024 * 1024)
        throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
          control.check();
          if (out.size() + (long) n > 8 * 1024 * 1024)
            throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
          out.write(buffer, 0, n);
        }
      }
      if (out.size() == 0 || expected >= 0 && out.size() != expected)
        throw new DownloadError(DownloadError.Category.NETWORK);
      return new Document(
          effective, new String(out.toByteArray(), StandardCharsets.UTF_8), connection, null);
    } finally {
      release(connection);
    }
  }

  public void copy(String url, File file, DownloadEngine.Progress progress) throws Exception {
    HttpURLConnection connection = open(url, 0, -1);
    try (InputStream in = connection.getInputStream();
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
      long total = connection.getContentLengthLong(), done = 0;
      byte[] buffer = new byte[128 * 1024];
      int n, previous = -1;
      while ((n = in.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("다운로드 중단");
        out.write(buffer, 0, n);
        done += n;
        int percent = total > 0 ? (int) (done * 100 / total) : 0;
        if (percent != previous) {
          progress.update("원본 저장 중", percent);
          previous = percent;
        }
      }
      if (done == 0 || (total >= 0 && done != total)) throw new IOException("파일이 완전히 내려받아지지 않았습니다");
    } finally {
      connection.disconnect();
    }
  }
}
