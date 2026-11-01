package com.adownloader.app.jobs;

import com.adownloader.app.download.*;
import java.io.*;
import java.security.*;
import java.util.*;
import org.json.*;

public final class JobJournal implements EngineCheckpoint {
  private final DownloadJobStore store;
  private final long id;
  private List<ManifestParser.Track> cachedPlan;
  private boolean planLoaded;

  public JobJournal(DownloadJobStore store, long id) {
    this.store = store;
    this.id = id;
  }

  @Override
  public boolean previouslyAccepted() {
    var job = store.get(id);
    return job != null && job.bytes > 0;
  }

  @Override
  public long committedSegmentBytes() {
    return store.committedSegmentBytes(id);
  }

  @Override
  public DownloadEngine.Result loadDestinationFinal(FileDescriptor fd, TransferControl control)
      throws Exception {
    JSONObject finalMedia = store.finalizedMedia(id);
    if (finalMedia == null) return null;
    try (DescriptorChannel input = new DescriptorChannel(fd)) {
      if (input.size() != finalMedia.getLong("bytes")
          || !controlledHash(input, input.size(), control).equals(finalMedia.getString("hash")))
        throw new DownloadError(DownloadError.Category.STORAGE);
    }
    PostProcessor.inspect(
        fd,
        0,
        finalMedia.getString("mime").startsWith("video/"),
        finalMedia.getString("mime").startsWith("audio/"));
    purgeFinalizedInputs();
    return new DownloadEngine.Result(
        null, finalMedia.getString("mime"), finalMedia.getString("extension"));
  }

  private static String controlledHash(
      DescriptorChannel input, long length, TransferControl control) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(128 * 1024);
    input.position(0);
    while (length > 0) {
      control.check();
      buffer.clear();
      buffer.limit((int) Math.min(length, buffer.capacity()));
      int count = input.read(buffer);
      if (count <= 0) throw new DownloadError(DownloadError.Category.STORAGE);
      digest.update(buffer.array(), 0, count);
      length -= count;
    }
    return DirectTransfer.hex(digest.digest());
  }

  @Override
  public void saveDestinationFinal(
      FileDescriptor fd, String mime, String extension, TransferControl control) throws Exception {
    fd.sync();
    try (DescriptorChannel input = new DescriptorChannel(fd)) {
      String hash = controlledHash(input, input.size(), control);
      control.check();
      store.finalizedMedia(id, input.size(), hash, mime, extension);
    }
    // SQLite FULL commit is durable BEFORE any original source file is removed.
    purgeFinalizedInputs();
  }

  private void purgeFinalizedInputs() throws Exception {
    if (store.finalizedMedia(id) == null) return;
    File[] files = store.directory(id).listFiles();
    if (files != null)
      for (File file : files)
        if (file.getName().matches("part-[0-9]+-[0-9]+(?:[.]partial)?|output-[0-9]+[.]media")) {
          if (file.exists() && !file.delete())
            throw new DownloadError(DownloadError.Category.STORAGE);
        }
  }

  @Override
  public void sourceMetadata(String disposition) throws Exception {
    if (disposition == null || disposition.isBlank()) return;
    JSONObject request = store.request(id);
    request.put("disposition", disposition);
    store.writeContext(id, request);
  }

  @Override
  public List<ManifestParser.Track> loadPlan() throws Exception {
    if (!planLoaded) {
      JSONObject request = store.request(id);
      cachedPlan = request.has("plan") ? PlanCodec.decode(request.getJSONArray("plan")) : null;
      planLoaded = true;
    }
    return cachedPlan;
  }

  @Override
  public void savePlan(List<ManifestParser.Track> tracks) throws Exception {
    JSONObject request = store.request(id);
    request.put("plan", PlanCodec.encode(tracks));
    store.writeContext(id, request);
    cachedPlan = tracks;
    planLoaded = true;
  }

  public void verifyAll() throws Exception {
    JSONObject request = store.request(id), files = request.optJSONObject("directFiles");
    if (loadFinal() != null) return;
    if (store.finalizedMedia(id) != null) {
      try (var fd = store.openPending(id, "r")) {
        loadDestinationFinal(fd.getFileDescriptor(), new TransferControl());
        return;
      }
    }
    JSONObject direct = request.optJSONObject("directMedia");
    if (direct != null && direct.optLong("bytes") > 0) {
      try (var fd = store.openPending(id, "r");
          var input = new DescriptorChannel(fd.getFileDescriptor())) {
        if (input.size() < direct.getLong("bytes")
            || !prefixHash(input, direct.getLong("bytes")).equals(direct.getString("hash")))
          throw new DownloadError(DownloadError.Category.STORAGE);
      }
    }
    if (files != null) {
      Iterator<String> names = files.keys();
      while (names.hasNext()) {
        String name = names.next();
        if (!name.matches("output-[0-9]+[.](media|mp4)"))
          throw new DownloadError(DownloadError.Category.STORAGE);
        JSONObject saved = files.getJSONObject(name);
        long length = saved.getLong("bytes");
        File file = new File(store.directory(id), name);
        if (length > 0) {
          if (!file.isFile() || file.length() < length)
            throw new DownloadError(DownloadError.Category.STORAGE);
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            long remaining = length;
            while (remaining > 0) {
              int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
              if (n < 0) throw new DownloadError(DownloadError.Category.STORAGE);
              digest.update(buffer, 0, n);
              remaining -= n;
            }
          }
          if (!DirectTransfer.hex(digest.digest()).equals(saved.getString("hash")))
            throw new DownloadError(DownloadError.Category.STORAGE);
        }
      }
    }
    try (android.database.Cursor c =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT track,part,bytes,hash FROM parts WHERE job=?", new String[] {"" + id})) {
      while (c.moveToNext()) {
        File part = new File(store.directory(id), "part-" + c.getInt(0) + "-" + c.getInt(1));
        if (!part.isFile() || part.length() != c.getLong(2) || !hash(part).equals(c.getString(3)))
          throw new DownloadError(DownloadError.Category.STORAGE);
      }
    }
  }

  public static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[128 * 1024];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return DirectTransfer.hex(digest.digest());
  }

  private static String prefixHash(java.nio.channels.SeekableByteChannel input, long length)
      throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(128 * 1024);
    input.position(0);
    long remaining = length;
    while (remaining > 0) {
      buffer.clear();
      buffer.limit((int) Math.min(buffer.capacity(), remaining));
      int read = input.read(buffer);
      if (read <= 0) throw new DownloadError(DownloadError.Category.STORAGE);
      hash.update(buffer.array(), 0, read);
      remaining -= read;
    }
    return DirectTransfer.hex(hash.digest());
  }

  @Override
  public DownloadEngine.Result loadFinal() throws Exception {
    JSONObject value = store.request(id).optJSONObject("finalOutput");
    if (value == null) return null;
    String name = value.getString("name");
    if (!name.matches("output-[0-9]+[.]mp4"))
      throw new DownloadError(DownloadError.Category.STORAGE);
    File file = new File(store.directory(id), name);
    if (!file.isFile() || !hash(file).equals(value.getString("hash")))
      throw new DownloadError(DownloadError.Category.STORAGE);
    return new DownloadEngine.Result(file, value.getString("mime"), value.getString("extension"));
  }

  @Override
  public void saveFinal(File file, String mime, String extension) throws Exception {
    JSONObject request = store.request(id);
    request.put(
        "finalOutput",
        new JSONObject()
            .put("name", file.getName())
            .put("hash", hash(file))
            .put("mime", mime)
            .put("extension", extension));
    store.writeContext(id, request);
  }

  @Override
  public void copy(
      HttpSource source, String url, FileDescriptor descriptor, TransferControl control)
      throws Exception {
    JSONObject prior = store.request(id).optJSONObject("directMedia");
    DirectTransfer.State state =
        prior == null
            ? DirectTransfer.State.empty()
            : new DirectTransfer.State(
                prior.getLong("bytes"),
                prior.optLong("total", -1),
                prior.optString("etag"),
                prior.optString("modified"),
                prior.optString("hash"),
                prior.optBoolean("complete"),
                false);
    try (DescriptorChannel target = new DescriptorChannel(descriptor)) {
      DirectTransfer.copy(
          source,
          url,
          target,
          state,
          checkpoint -> {
            JSONObject request = store.request(id);
            request.put(
                "directMedia",
                new JSONObject()
                    .put("bytes", checkpoint.bytes)
                    .put("total", checkpoint.total)
                    .put("etag", checkpoint.etag)
                    .put("modified", checkpoint.modified)
                    .put("hash", checkpoint.sha256)
                    .put("complete", checkpoint.complete));
            store.writeContext(id, request);
            store.progress(
                id,
                checkpoint.restarted ? "이어받기 불가 · 처음부터 다시 받는 중" : "원본 저장 중",
                checkpoint.total > 0 ? (int) (checkpoint.bytes * 100 / checkpoint.total) : 0,
                checkpoint.bytes,
                checkpoint.total,
                0,
                0);
          },
          control);
    } finally {
      sourceMetadata(source.disposition());
    }
  }

  @Override
  public boolean verified(File file, int track, int part) throws Exception {
    try (android.database.Cursor cursor =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT bytes,hash FROM parts WHERE job=? AND track=? AND part=?",
                new String[] {"" + id, "" + track, "" + part})) {
      if (!cursor.moveToFirst()) return false;
      if (!file.isFile()
          || file.length() != cursor.getLong(0)
          || !hash(file).equals(cursor.getString(1)))
        throw new DownloadError(DownloadError.Category.STORAGE);
      return true;
    }
  }

  @Override
  public void saved(File file, int track, int part) throws Exception {
    store.part(id, track, part, file.length(), hash(file));
    int total = 0;
    List<ManifestParser.Track> plan = loadPlan();
    if (plan != null) for (ManifestParser.Track item : plan) total += item.parts.size();
    try (android.database.Cursor cursor =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT COALESCE(SUM(bytes),0),COUNT(*) FROM parts WHERE job=?",
                new String[] {"" + id})) {
      if (cursor.moveToFirst()) {
        DownloadJobStore.Job job = store.get(id);
        store.progress(
            id,
            job.phase,
            total > 0 ? cursor.getInt(1) * 100 / total : 0,
            cursor.getLong(0),
            -1,
            cursor.getInt(1),
            total);
      }
    }
  }

  @Override
  public void copy(HttpSource source, String url, File file, TransferControl control)
      throws Exception {
    JSONObject request = store.request(id), all = request.optJSONObject("directFiles");
    JSONObject prior = all == null ? null : all.optJSONObject(file.getName());
    DirectTransfer.State state =
        prior == null
            ? DirectTransfer.State.empty()
            : new DirectTransfer.State(
                prior.getLong("bytes"),
                prior.getLong("total"),
                prior.optString("etag"),
                prior.optString("modified"),
                prior.optString("hash"),
                prior.optBoolean("complete"),
                false);
    try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
      DirectTransfer.copy(
          source,
          url,
          target.getChannel(),
          state,
          checkpoint -> {
            JSONObject current = store.request(id), files = current.optJSONObject("directFiles");
            if (files == null) files = new JSONObject();
            files.put(
                file.getName(),
                new JSONObject()
                    .put("bytes", checkpoint.bytes)
                    .put("total", checkpoint.total)
                    .put("etag", checkpoint.etag)
                    .put("modified", checkpoint.modified)
                    .put("hash", checkpoint.sha256)
                    .put("complete", checkpoint.complete));
            current.put("directFiles", files);
            store.writeContext(id, current);
            store.progress(
                id,
                checkpoint.restarted ? "이어받기 불가 · 처음부터 다시 받는 중" : "원본 저장 중",
                checkpoint.total > 0 ? (int) (checkpoint.bytes * 100 / checkpoint.total) : 0,
                checkpoint.bytes,
                checkpoint.total,
                0,
                0);
          },
          control);
    } finally {
      sourceMetadata(source.disposition());
    }
  }
}
