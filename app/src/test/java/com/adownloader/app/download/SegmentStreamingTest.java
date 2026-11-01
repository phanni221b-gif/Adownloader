package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.Test;

public class SegmentStreamingTest {
  private interface Body {
    void write(OutputStream output, int attempt) throws Exception;
  }

  private static final class Fixture implements AutoCloseable {
    final ServerSocket server;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final AtomicInteger attempts = new AtomicInteger();

    Fixture(long length, Body body) throws Exception {
      server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
      worker.execute(
          () -> {
            while (!server.isClosed())
              try (Socket client = server.accept()) {
                BufferedReader in =
                    new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {}
                OutputStream out = client.getOutputStream();
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Length: "
                            + length
                            + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                body.write(out, attempts.getAndIncrement());
              } catch (Exception ignored) {
              }
          });
    }

    String url() {
      return "http://127.0.0.1:" + server.getLocalPort() + "/segment";
    }

    public void close() throws Exception {
      server.close();
      worker.shutdownNow();
    }
  }

  @Test
  public void largeSegmentHasNoSixtyFourMiBArrayLimit() throws Exception {
    long size = 65L * 1024 * 1024 + 3;
    File target = File.createTempFile("segment-large", ".part");
    try (Fixture server =
        new Fixture(
            size,
            (out, attempt) -> {
              byte[] buffer = new byte[128 * 1024];
              Arrays.fill(buffer, (byte) 37);
              for (long left = size; left > 0; left -= Math.min(left, buffer.length))
                out.write(buffer, 0, (int) Math.min(left, buffer.length));
            })) {
      new HttpSource(server.url(), Map.of()).stream(server.url(), 0, -1, target, null);
      assertEquals(size, target.length());
      try (RandomAccessFile file = new RandomAccessFile(target, "r")) {
        file.seek(size - 1);
        assertEquals(37, file.read());
      }
    } finally {
      target.delete();
    }
  }

  @Test
  public void truncatedAttemptIsReplacedAndAesDecryptsWhileStreaming() throws Exception {
    byte[] plain = new byte[1024 * 1024 + 11], key = new byte[16], iv = new byte[16];
    new Random(42).nextBytes(plain);
    Cipher encrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");
    encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    byte[] encrypted = encrypt.doFinal(plain);
    File target = File.createTempFile("segment-aes", ".part");
    try (Fixture server =
        new Fixture(
            encrypted.length,
            (out, attempt) ->
                out.write(encrypted, 0, attempt == 0 ? encrypted.length / 2 : encrypted.length))) {
      new HttpSource(server.url(), Map.of())
          .stream(
              server.url(),
              0,
              -1,
              target,
              input -> {
                Cipher decrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");
                decrypt.init(
                    Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                return new CipherInputStream(input, decrypt);
              });
      assertEquals(2, server.attempts.get());
      assertArrayEquals(plain, Files.readAllBytes(target.toPath()));
    } finally {
      target.delete();
    }
  }
}
