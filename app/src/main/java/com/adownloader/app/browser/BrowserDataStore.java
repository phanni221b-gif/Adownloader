package com.adownloader.app.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.adownloader.app.jobs.JobSecrets;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** Encrypted browser history/bookmarks/session. Disk and Keystore work stay off the UI thread. */
public final class BrowserDataStore implements AutoCloseable {
  private static final long AAD = -600;
  private final Context context;
  private final AtomicFile file;
  // Application-owned serialization also orders old-Activity saves before a new Activity reads.
  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private JSONObject data;
  private volatile boolean closed;

  public BrowserDataStore(Context context) {
    this.context = context.getApplicationContext();
    file = new AtomicFile(new File(this.context.getFilesDir(), "browser-state.enc"));
  }

  private JSONObject read() throws Exception {
    if (data != null) return data;
    if (file.getBaseFile().exists()) {
      try (InputStream in = file.openRead()) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int n;
        while ((n = in.read(buffer)) >= 0) {
          if (bytes.size() + n > 9 * 1024 * 1024) throw new IOException("BROWSER_STATE_SIZE");
          bytes.write(buffer, 0, n);
        }
        data = new JSONObject(new JobSecrets().open(AAD, bytes.toByteArray()));
      }
    } else data = new JSONObject();
    var old = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    if (old.contains("last_url") || old.contains("session")) {
      if (!data.has("url")) {
        String url = old.getString("last_url", "");
        if (SafeSessionState.safeUrl(url)) data.put("url", url);
        String session = SafeSessionState.sanitizeLegacy(old.getString("session", null));
        if (session != null) data.put("session", session);
      }
      write();
      if (!old.edit().remove("last_url").remove("session").commit())
        throw new IOException("BROWSER_MIGRATION");
    }
    return data;
  }

  private void write() throws Exception {
    byte[] encrypted = new JobSecrets().seal(AAD, data.toString());
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(encrypted);
      file.finishWrite(out);
    } catch (Exception e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  public void load(Consumer<JSONObject> callback) {
    worker.execute(
        () -> {
          JSONObject result;
          try {
            result = new JSONObject(read().toString());
          } catch (Exception ignored) {
            result = new JSONObject();
          }
          JSONObject snapshot = result;
          main.post(
              () -> {
                if (!closed) callback.accept(snapshot);
              });
        });
  }

  public void save(String url, String session) {
    worker.execute(
        () -> {
          try {
            read();
            if (SafeSessionState.safeUrl(url)) data.put("url", url);
            else data.remove("url");
            String safe = SafeSessionState.sanitizeLegacy(session);
            if (safe != null) data.put("session", safe);
            else data.remove("session");
            write();
          } catch (Exception ignored) {
            /* Preserve the last atomic snapshot on write failure. */
          }
        });
  }

  public void saveTabs(String snapshot) {
    if (snapshot == null) return;
    worker.execute(
        () -> {
          try {
            read();
            data.put("tabs", new JSONObject(snapshot));
            data.remove("session");
            data.remove("url");
            write();
          } catch (Exception ignored) {
          }
        });
  }

  public void remember(String url, String title, boolean bookmark) {
    if (!SafeSessionState.safeUrl(url)) return;
    worker.execute(
        () -> {
          try {
            read();
            String key = bookmark ? "bookmarks" : "history";
            JSONArray old = data.optJSONArray(key), next = new JSONArray();
            next.put(
                new JSONObject()
                    .put("url", url)
                    .put(
                        "title",
                        title == null ? "" : title.substring(0, Math.min(300, title.length()))));
            if (old != null)
              for (int i = 0; i < old.length() && next.length() < 100; i++)
                if (!url.equals(old.getJSONObject(i).optString("url")))
                  next.put(old.getJSONObject(i));
            data.put(key, next);
            write();
          } catch (Exception ignored) {
          }
        });
  }

  public void clearHistory() {
    worker.execute(
        () -> {
          try {
            read();
            data.remove("history");
            data.remove("tabs");
            data.remove("session");
            data.remove("url");
            write();
          } catch (Exception ignored) {
          }
        });
  }

  public void remove(String url, boolean bookmark) {
    worker.execute(
        () -> {
          try {
            read();
            String key = bookmark ? "bookmarks" : "history";
            JSONArray old = data.optJSONArray(key), next = new JSONArray();
            if (old != null)
              for (int i = 0; i < old.length(); i++)
                if (!url.equals(old.getJSONObject(i).optString("url")))
                  next.put(old.getJSONObject(i));
            data.put(key, next);
            write();
          } catch (Exception ignored) {
          }
        });
  }

  @Override
  public void close() {
    closed = true;
    main.removeCallbacksAndMessages(null);
  }
}
