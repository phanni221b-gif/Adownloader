package com.adownloader.app.download;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;

/** The minimal seekable/durable operations required by validated HTTP resume. */
public interface DurableChannel extends SeekableByteChannel {
  void force(boolean metadata) throws IOException;

  default void reserve(long remaining) throws IOException {}

  static DurableChannel wrap(FileChannel file) {
    return new DurableChannel() {
      public int read(ByteBuffer bytes) throws IOException {
        return file.read(bytes);
      }

      public int write(ByteBuffer bytes) throws IOException {
        return file.write(bytes);
      }

      public long size() throws IOException {
        return file.size();
      }

      public long position() throws IOException {
        return file.position();
      }

      public DurableChannel position(long position) throws IOException {
        file.position(position);
        return this;
      }

      public DurableChannel truncate(long length) throws IOException {
        file.truncate(length);
        return this;
      }

      public void force(boolean metadata) throws IOException {
        file.force(metadata);
      }

      public boolean isOpen() {
        return file.isOpen();
      }

      public void close() throws IOException {
        file.close();
      }
    };
  }
}
