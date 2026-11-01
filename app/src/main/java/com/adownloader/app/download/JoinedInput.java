package com.adownloader.app.download;

import java.io.*;
import java.util.*;

/** Seekable virtual concatenation: long offsets, one open file, no aggregate media copy. */
public final class JoinedInput implements AutoCloseable {
  private final List<File> files;
  private final long[] ends;
  private RandomAccessFile opened;
  private int openedIndex = -1;
  private boolean closed;

  public JoinedInput(List<File> files) throws IOException {
    this.files = new ArrayList<>(files);
    ends = new long[files.size()];
    long size = 0;
    for (int i = 0; i < files.size(); i++) {
      File file = files.get(i);
      if (!file.isFile() || file.length() <= 0) throw new IOException("SEGMENT_MISSING");
      try {
        size = Math.addExact(size, file.length());
      } catch (ArithmeticException invalid) {
        throw new IOException("MEDIA_SIZE_INVALID");
      }
      ends[i] = size;
    }
  }

  public long size() {
    return ends.length == 0 ? 0 : ends[ends.length - 1];
  }

  public synchronized int readAt(long position, byte[] buffer, int offset, int length)
      throws IOException {
    if (closed) throw new IOException("SOURCE_CLOSED");
    if (position < 0 || offset < 0 || length < 0 || offset > buffer.length - length)
      throw new IOException("SOURCE_RANGE");
    if (length == 0) return 0;
    if (position >= size()) return -1;
    int written = 0;
    while (written < length && position < size()) {
      int low = 0, high = ends.length - 1;
      while (low < high) {
        int middle = (low + high) >>> 1;
        if (position < ends[middle]) high = middle;
        else low = middle + 1;
      }
      int index = low;
      long start = index == 0 ? 0 : ends[index - 1];
      if (openedIndex != index) {
        if (opened != null) opened.close();
        opened = new RandomAccessFile(files.get(index), "r");
        openedIndex = index;
      }
      opened.seek(position - start);
      int needed = (int) Math.min(length - written, ends[index] - position);
      int count = opened.read(buffer, offset + written, needed);
      if (count <= 0) throw new IOException("SEGMENT_TRUNCATED");
      written += count;
      position += count;
    }
    return written;
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    if (opened != null) opened.close();
    opened = null;
  }
}
