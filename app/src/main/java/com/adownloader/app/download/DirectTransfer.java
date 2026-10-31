package com.adownloader.app.download;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Validated append only. A durable checkpoint always describes fsynced bytes and their digest. */
public final class DirectTransfer {
  public static final class State {
    public final long bytes, total;
    public final String etag, modified, sha256;
    public final boolean complete, restarted;

    public State(
        long bytes,
        long total,
        String etag,
        String modified,
        String sha256,
        boolean complete,
        boolean restarted) {
      this.bytes = bytes;
      this.total = total;
      this.etag = etag == null ? "" : etag;
      this.modified = modified == null ? "" : modified;
      this.sha256 = sha256;
      this.complete = complete;
      this.restarted = restarted;
    }

    public static State empty() {
      return new State(0, -1, "", "", "", false, false);
    }
  }

  public interface Checkpoint {
    void save(State state) throws Exception;
  }

  public static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  private static MessageDigest digest(FileChannel channel, long length) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    channel.position(0);
    ByteBuffer buffer = ByteBuffer.allocate(128 * 1024);
    long done = 0;
    while (done < length) {
      buffer.clear();
      buffer.limit((int) Math.min(buffer.capacity(), length - done));
      int n = channel.read(buffer);
      if (n <= 0) throw new DownloadError(DownloadError.Category.STORAGE);
      digest.update(buffer.array(), 0, n);
      done += n;
    }
    return digest;
  }

  private static String snapshot(MessageDigest digest, FileChannel channel, long length)
      throws Exception {
    try {
      return hex(((MessageDigest) digest.clone()).digest());
    } catch (CloneNotSupportedException unsupported) {
      long position = channel.position();
      String hash = hex(digest(channel, length).digest());
      channel.position(position);
      return hash;
    }
  }

  public static void copy(
      HttpSource source,
      String url,
      FileChannel target,
      State prior,
      Checkpoint checkpoint,
      TransferControl control)
      throws Exception {
    control.check();
    long done = prior.bytes;
    boolean restarted = false;
    if (done < 0 || target.size() < done) throw new DownloadError(DownloadError.Category.STORAGE);
    MessageDigest digest = digest(target, done);
    if (done > 0 && !snapshot(digest, target, done).equals(prior.sha256))
      throw new DownloadError(DownloadError.Category.STORAGE);
    target.truncate(done);
    target.position(done);
    if (prior.complete && done > 0 && done == prior.total) {
      checkpoint.save(prior);
      return;
    }
    String validator =
        !prior.etag.isEmpty() && !prior.etag.startsWith("W/") ? prior.etag : prior.modified;
    HttpURLConnection connection = source.resume(url, done, validator);
    long total = -1;
    String etag = "", modified = "";
    try {
      int code = connection.getResponseCode();
      etag = connection.getHeaderField("ETag");
      modified = connection.getHeaderField("Last-Modified");
      if (code == 206) {
        Matcher range =
            Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)")
                .matcher(String.valueOf(connection.getHeaderField("Content-Range")));
        if (!range.matches()) throw new DownloadError(DownloadError.Category.NETWORK);
        long start = Long.parseLong(range.group(1)), end = Long.parseLong(range.group(2));
        total = Long.parseLong(range.group(3));
        boolean same =
            !validator.isEmpty() && (validator.equals(etag) || validator.equals(modified));
        if (start != done
            || end < start
            || end >= total
            || connection.getContentLengthLong() != end - start + 1
            || prior.total > 0 && total != prior.total
            || !same) throw new DownloadError(DownloadError.Category.NETWORK);
      } else {
        total = connection.getContentLengthLong();
        if (done > 0) {
          done = 0;
          restarted = true;
          target.truncate(0);
          target.position(0);
          digest = MessageDigest.getInstance("SHA-256");
        }
      }
      byte[] buffer = new byte[128 * 1024];
      long saved = done;
      boolean success = false;
      try (InputStream input = connection.getInputStream()) {
        int n;
        while ((n = input.read(buffer)) != -1) {
          control.check();
          ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
          while (bytes.hasRemaining()) target.write(bytes);
          digest.update(buffer, 0, n);
          done += n;
          if (done - saved >= 1024 * 1024) {
            target.force(false);
            checkpoint.save(
                new State(
                    done, total, etag, modified, snapshot(digest, target, done), false, restarted));
            saved = done;
          }
        }
        control.check();
        if (done == 0 || total >= 0 && done != total)
          throw new DownloadError(DownloadError.Category.NETWORK);
        success = true;
      } finally {
        target.force(false);
        checkpoint.save(
            new State(
                done, total, etag, modified, snapshot(digest, target, done), success, restarted));
      }
    } finally {
      source.release(connection);
    }
  }
}
