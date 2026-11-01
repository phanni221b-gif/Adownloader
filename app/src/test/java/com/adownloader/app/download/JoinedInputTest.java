package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class JoinedInputTest {
  @Test
  public void randomReadsCrossBoundariesWithoutAggregateCopy() throws Exception {
    File root = Files.createTempDirectory("joined-media").toFile();
    List<File> parts = new ArrayList<>();
    try {
      for (int i = 0; i < 1000; i++) {
        File file = new File(root, "part-" + i);
        Files.write(file.toPath(), new byte[] {(byte) (i >> 8), (byte) i});
        parts.add(file);
      }
      try (JoinedInput input = new JoinedInput(parts)) {
        assertEquals(2000, input.size());
        byte[] bytes = new byte[4];
        assertEquals(4, input.readAt(1023, bytes, 0, 4));
        assertArrayEquals(new byte[] {(byte) 255, 2, 0, 2}, bytes);
        assertEquals(1, input.readAt(1999, bytes, 0, 4));
        assertEquals(-1, input.readAt(2000, bytes, 0, 4));
        assertEquals(0, input.readAt(2000, bytes, 0, 0));
      }
      assertEquals(1000, root.listFiles().length);
    } finally {
      for (File file : parts) file.delete();
      root.delete();
    }
  }

  @Test
  public void offsetsRemainLongBeyondTwoGiB() throws Exception {
    File root = Files.createTempDirectory("joined-large").toFile(),
        first = new File(root, "large"),
        last = new File(root, "last");
    try {
      long length = (1L << 31) + 19;
      try (RandomAccessFile file = new RandomAccessFile(first, "rw")) {
        file.setLength(length);
        file.seek(length - 1);
        file.write(7);
      }
      Files.write(last.toPath(), new byte[] {8, 9});
      try (JoinedInput input = new JoinedInput(Arrays.asList(first, last))) {
        byte[] bytes = new byte[3];
        assertEquals(length + 2, input.size());
        assertEquals(3, input.readAt(length - 1, bytes, 0, 3));
        assertArrayEquals(new byte[] {7, 8, 9}, bytes);
      }
    } finally {
      first.delete();
      last.delete();
      root.delete();
    }
  }
}
