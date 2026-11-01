package com.adownloader.app.download;

import android.media.MediaDataSource;
import java.io.*;
import java.util.List;

/** Android extractor adapter; the portable reader owns at most one segment descriptor. */
public final class JoinedMediaDataSource extends MediaDataSource {
  private final JoinedInput input;

  public JoinedMediaDataSource(List<File> parts) throws IOException {
    input = new JoinedInput(parts);
  }

  @Override
  public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
    return input.readAt(position, buffer, offset, size);
  }

  @Override
  public long getSize() {
    return input.size();
  }

  @Override
  public void close() throws IOException {
    input.close();
  }
}
