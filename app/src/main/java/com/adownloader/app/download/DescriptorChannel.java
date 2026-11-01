package com.adownloader.app.download;

import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

/**
 * Owns a duplicate of a seekable provider FD; positional I/O never changes the provider's offset.
 */
public final class DescriptorChannel implements DurableChannel {
  private final ParcelFileDescriptor owned;
  private long position;
  private boolean open = true;

  public DescriptorChannel(FileDescriptor descriptor) throws IOException {
    owned = ParcelFileDescriptor.dup(descriptor);
  }

  private void check() throws ClosedChannelException {
    if (!open) throw new ClosedChannelException();
  }

  private IOException storage() {
    return new DownloadError(DownloadError.Category.STORAGE);
  }

  @Override
  public int read(ByteBuffer target) throws IOException {
    check();
    if (!target.hasRemaining()) return 0;
    int count = Math.min(target.remaining(), 128 * 1024), start = target.position();
    byte[] bytes = target.hasArray() ? target.array() : new byte[count];
    int offset = target.hasArray() ? target.arrayOffset() + start : 0;
    try {
      int read = Os.pread(owned.getFileDescriptor(), bytes, offset, count, position);
      if (read == 0) return -1;
      if (target.hasArray()) target.position(start + read);
      else target.put(bytes, 0, read);
      position += read;
      return read;
    } catch (ErrnoException error) {
      throw new DownloadError(
          error.errno == OsConstants.ENOSPC
              ? DownloadError.Category.STORAGE_FULL
              : DownloadError.Category.STORAGE);
    }
  }

  @Override
  public int write(ByteBuffer source) throws IOException {
    check();
    if (!source.hasRemaining()) return 0;
    int count = Math.min(source.remaining(), 128 * 1024), start = source.position();
    byte[] bytes = source.hasArray() ? source.array() : new byte[count];
    int offset = source.hasArray() ? source.arrayOffset() + start : 0;
    if (!source.hasArray()) source.duplicate().get(bytes);
    try {
      int written = Os.pwrite(owned.getFileDescriptor(), bytes, offset, count, position);
      if (written <= 0) throw storage();
      source.position(start + written);
      position += written;
      return written;
    } catch (ErrnoException error) {
      throw DownloadError.storage(error);
    }
  }

  @Override
  public long size() throws IOException {
    check();
    try {
      return Os.fstat(owned.getFileDescriptor()).st_size;
    } catch (ErrnoException error) {
      throw DownloadError.storage(error);
    }
  }

  @Override
  public long position() throws IOException {
    check();
    return position;
  }

  @Override
  public DescriptorChannel position(long value) throws IOException {
    check();
    if (value < 0) throw new IllegalArgumentException("NEGATIVE_POSITION");
    position = value;
    return this;
  }

  @Override
  public DescriptorChannel truncate(long value) throws IOException {
    check();
    if (value < 0) throw new IllegalArgumentException("NEGATIVE_SIZE");
    try {
      if (value < size()) Os.ftruncate(owned.getFileDescriptor(), value);
      position = Math.min(position, value);
      return this;
    } catch (ErrnoException error) {
      throw DownloadError.storage(error);
    }
  }

  @Override
  public void force(boolean metadata) throws IOException {
    check();
    try {
      Os.fsync(owned.getFileDescriptor());
    } catch (ErrnoException error) {
      throw DownloadError.storage(error);
    }
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void reserve(long remaining) throws IOException {
    check();
    if (remaining <= 0) return;
    try {
      StructStatVfs fs = Os.fstatvfs(owned.getFileDescriptor());
      long available;
      try {
        available = Math.multiplyExact(fs.f_bavail, fs.f_frsize);
      } catch (ArithmeticException huge) {
        available = Long.MAX_VALUE;
      }
      if (remaining > available - 8L * 1024 * 1024)
        throw new DownloadError(DownloadError.Category.STORAGE_FULL);
    } catch (ErrnoException error) {
      throw DownloadError.storage(error);
    }
  }

  @Override
  public void close() throws IOException {
    if (open) {
      open = false;
      owned.close();
    }
  }
}
