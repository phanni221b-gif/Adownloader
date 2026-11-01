package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.*;

public class DirectTransferTest {
  private byte[] body;
  private String etag;
  private boolean ranges, badRange;
  private ServerSocket server;
  private Thread thread;
  private volatile String requestedRange, ifRange;

  @Before
  public void start() throws Exception {
    body = new byte[32768];
    new Random(42).nextBytes(body);
    etag = "\"v1\"";
    ranges = true;
    server = new ServerSocket(0);
    thread =
        new Thread(
            () -> {
              while (!server.isClosed())
                try (Socket socket = server.accept()) {
                  BufferedReader in =
                      new BufferedReader(new InputStreamReader(socket.getInputStream()));
                  in.readLine();
                  String line;
                  requestedRange = null;
                  ifRange = null;
                  while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("range:"))
                      requestedRange = line.substring(6).trim();
                    if (line.toLowerCase(Locale.ROOT).startsWith("if-range:"))
                      ifRange = line.substring(9).trim();
                  }
                  int offset = 0;
                  if (ranges && requestedRange != null && etag.equals(ifRange))
                    offset = Integer.parseInt(requestedRange.substring(6).replace("-", ""));
                  String headers =
                      "HTTP/1.1 "
                          + (offset > 0 ? "206 Partial Content" : "200 OK")
                          + "\r\nETag: "
                          + etag
                          + "\r\nContent-Length: "
                          + (body.length - offset)
                          + "\r\n"
                          + (offset > 0
                              ? "Content-Range: bytes "
                                  + (badRange ? offset + 1 : offset)
                                  + "-"
                                  + (body.length - 1)
                                  + "/"
                                  + body.length
                                  + "\r\n"
                              : "")
                          + "Connection: close\r\n\r\n";
                  socket.getOutputStream().write(headers.getBytes());
                  socket.getOutputStream().write(body, offset, body.length - offset);
                } catch (Exception error) {
                  if (!server.isClosed()) throw new RuntimeException(error);
                }
            });
    thread.start();
  }

  @After
  public void close() throws Exception {
    server.close();
    thread.join(2000);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getLocalPort() + "/file";
  }

  private String hash(byte[] data) throws Exception {
    return DirectTransfer.hex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  private void run(boolean changed, boolean noRange) throws Exception {
    File file = File.createTempFile("resume-test", ".part");
    try {
      byte[] prefix = Arrays.copyOf(body, 4096);
      Files.write(file.toPath(), prefix);
      DirectTransfer.State prior =
          new DirectTransfer.State(4096, body.length, "\"v1\"", "", hash(prefix), false, false);
      if (changed) {
        etag = "\"v2\"";
        body[100] = 99;
      }
      if (noRange) ranges = false;
      DirectTransfer.State[] saved = {null};
      TransferControl control = new TransferControl();
      try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
        DirectTransfer.copy(
            new HttpSource(url(), Collections.emptyMap(), control),
            url(),
            target.getChannel(),
            prior,
            state -> saved[0] = state,
            control);
      }
      assertArrayEquals(body, Files.readAllBytes(file.toPath()));
      assertTrue(saved[0].complete);
      assertEquals(hash(body), saved[0].sha256);
      assertEquals(changed || noRange, saved[0].restarted);
      assertEquals("bytes=4096-", requestedRange);
      assertEquals("\"v1\"", ifRange);
    } finally {
      file.delete();
    }
  }

  @Test
  public void validatedRangeResumesBitExactly() throws Exception {
    run(false, false);
  }

  @Test
  public void changedFileRestartsWithoutAppending() throws Exception {
    run(true, false);
  }

  @Test
  public void ignoredRangeRestartsWithoutAppending() throws Exception {
    run(false, true);
  }

  @Test
  public void corruptCheckpointCannotBeAppended() throws Exception {
    File file = File.createTempFile("corrupt", ".part");
    try {
      Files.write(file.toPath(), new byte[4096]);
      TransferControl control = new TransferControl();
      try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
        try {
          DirectTransfer.copy(
              new HttpSource(url(), Collections.emptyMap(), control),
              url(),
              target.getChannel(),
              new DirectTransfer.State(
                  4096, body.length, etag, "", hash(Arrays.copyOf(body, 4096)), false, false),
              s -> {},
              control);
          fail();
        } catch (DownloadError expected) {
          assertEquals(DownloadError.Category.STORAGE, expected.category);
        }
      }
    } finally {
      file.delete();
    }
  }

  @Test
  public void malformedRangeCannotCorruptPrefix() throws Exception {
    badRange = true;
    try {
      run(false, false);
      fail();
    } catch (DownloadError expected) {
      assertEquals(DownloadError.Category.NETWORK, expected.category);
    }
  }

  @Test
  public void completedUnknownLengthSurvivesPublicationInterruption() throws Exception {
    File file = File.createTempFile("unknown-complete", ".part");
    try {
      Files.write(file.toPath(), body);
      DirectTransfer.State prior =
          new DirectTransfer.State(body.length, -1, "", "", hash(body), true, false);
      requestedRange = "NOT_REQUESTED";
      try (java.io.RandomAccessFile target = new java.io.RandomAccessFile(file, "rw")) {
        DirectTransfer.copy(
            new HttpSource(url(), Collections.emptyMap()),
            url(),
            target.getChannel(),
            prior,
            state -> assertTrue(state.complete),
            new TransferControl());
      }
      assertEquals("NOT_REQUESTED", requestedRange);
      assertArrayEquals(body, Files.readAllBytes(file.toPath()));
    } finally {
      file.delete();
    }
  }

  @Test
  public void noValidatorRestartsSafelyInsteadOfAppending() throws Exception {
    File file = File.createTempFile("unvalidated-resume", ".part");
    try {
      byte[] prefix = Arrays.copyOf(body, 4096);
      Files.write(file.toPath(), prefix);
      DirectTransfer.State prior =
          new DirectTransfer.State(prefix.length, body.length, "", "", hash(prefix), false, false);
      DirectTransfer.State[] saved = {null};
      try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
        DirectTransfer.copy(
            new HttpSource(url(), Collections.emptyMap()),
            url(),
            target.getChannel(),
            prior,
            state -> saved[0] = state,
            new TransferControl());
      }
      assertTrue(saved[0].restarted);
      assertNull(requestedRange);
      assertArrayEquals(body, Files.readAllBytes(file.toPath()));
    } finally {
      file.delete();
    }
  }
}
