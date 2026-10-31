package com.adownloader.app.jobs;

import com.adownloader.app.download.*;
import java.io.*;
import java.security.*;
import java.util.*;
import org.json.*;

public final class JobJournal implements EngineCheckpoint {
  private final DownloadJobStore store;
  private final long id;

  public JobJournal(DownloadJobStore store, long id) {
    this.store = store;
    this.id = id;
  }

  @Override
  public List<ManifestParser.Track> loadPlan() throws Exception {
    JSONObject request = store.request(id);
    return request.has("plan") ? PlanCodec.decode(request.getJSONArray("plan")) : null;
  }

  @Override
  public void savePlan(List<ManifestParser.Track> tracks) throws Exception {
    JSONObject request = store.request(id);
    request.put("plan", PlanCodec.encode(tracks));
    store.writeContext(id, request);
  }

  public void verifyAll() throws Exception {
    JSONObject request = store.request(id), files = request.optJSONObject("directFiles");
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

  @Override
  public boolean verified(File file, int track, int part) throws Exception {
    String expected = store.partHash(id, track, part, file.length());
    if (expected == null) return false;
    if (!file.isFile() || !hash(file).equals(expected))
      throw new DownloadError(DownloadError.Category.STORAGE);
    return true;
  }

  @Override
  public void saved(File file, int track, int part) throws Exception {
    store.part(id, track, part, file.length(), hash(file));
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
                checkpoint.restarted ? "파일 변경 · 처음부터 다시 받는 중" : "원본 저장 중",
                checkpoint.total > 0 ? (int) (checkpoint.bytes * 100 / checkpoint.total) : 0,
                checkpoint.bytes,
                checkpoint.total,
                0,
                0);
          },
          control);
    }
  }
}
