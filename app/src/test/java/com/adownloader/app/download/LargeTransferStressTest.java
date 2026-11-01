package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class LargeTransferStressTest {
  @Test(timeout = 240000)
  public void actualTwoGiBHttpTransferResumesAboveIntegerBoundaryAndMatchesHash() throws Exception {
    long total = (1L << 31) + 2 * 1024 * 1024;
    byte[] block = new byte[128 * 1024];
    new Random(960).nextBytes(block);
    AtomicLong range = new AtomicLong(-1);
    AtomicReference<Throwable> serverError = new AtomicReference<>();
    File file = Files.createTempFile("real-2g-transfer-", ".bin").toFile();
    try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
      Thread worker =
          new Thread(
              () -> {
                while (!server.isClosed())
                  try (Socket socket = server.accept()) {
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    reader.readLine();
                    String line;
                    long start = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty())
                      if (line.startsWith("Range: ")) {
                        start = Long.parseLong(line.substring(13).replace("-", ""));
                        range.set(start);
                      }
                    var output = socket.getOutputStream();
                    output.write(
                        ("HTTP/1.1 "
                                + (start == 0 ? "200 OK" : "206 Partial Content")
                                + "\r\nETag: \"large-fixed\"\r\nContent-Length: "
                                + (total - start)
                                + "\r\n"
                                + (start > 0
                                    ? "Content-Range: bytes "
                                        + start
                                        + "-"
                                        + (total - 1)
                                        + "/"
                                        + total
                                        + "\r\n"
                                    : "")
                                + "Connection: close\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    for (long position = start; position < total; ) {
                      int offset = (int) (position % block.length),
                          count = (int) Math.min(block.length - offset, total - position);
                      output.write(block, offset, count);
                      position += count;
                    }
                  } catch (SocketException disconnected) {
                  } catch (Throwable error) {
                    serverError.set(error);
                  }
              });
      worker.setDaemon(true);
      worker.start();
      String url = "http://127.0.0.1:" + server.getLocalPort() + "/synthetic";
      AtomicReference<DirectTransfer.State> saved =
          new AtomicReference<>(DirectTransfer.State.empty());
      TransferControl first = new TransferControl();
      try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
        try {
          DirectTransfer.copy(
              new HttpSource(url, Collections.emptyMap(), first),
              url,
              target.getChannel(),
              saved.get(),
              state -> {
                saved.set(state);
                if (state.bytes >= (1L << 31)) first.stop(TransferControl.Stop.PAUSE);
              },
              first);
          fail("pause expected");
        } catch (TransferControl.Stopped expected) {
        }
        assertTrue(saved.get().bytes >= (1L << 31));
        assertFalse(saved.get().complete);
        TransferControl resumed = new TransferControl();
        DirectTransfer.copy(
            new HttpSource(url, Collections.emptyMap(), resumed),
            url,
            target.getChannel(),
            saved.get(),
            saved::set,
            resumed);
      }
      assertEquals(total, file.length());
      assertTrue(saved.get().complete);
      assertTrue(range.get() >= (1L << 31));
      MessageDigest expected = MessageDigest.getInstance("SHA-256");
      for (long position = 0; position < total; position += block.length)
        expected.update(block, 0, (int) Math.min(block.length, total - position));
      assertEquals(DirectTransfer.hex(expected.digest()), saved.get().sha256);
      assertNull(serverError.get());
      server.close();
      worker.join(2000);
    } finally {
      assertTrue(file.delete());
    }
  }
}
