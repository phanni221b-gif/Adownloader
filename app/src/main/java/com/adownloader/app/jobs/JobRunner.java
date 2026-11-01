package com.adownloader.app.jobs;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import com.adownloader.app.*;
import com.adownloader.app.download.*;
import com.adownloader.app.ui.DownloadHistory;
import java.io.*;
import java.util.*;
import org.json.*;

/** A bounded worker performs one durable job. It has no Activity or browser reference. */
public final class JobRunner {
  private final Context context;
  private final DownloadJobStore store;

  public JobRunner(Context context, DownloadJobStore store) {
    this.context = context.getApplicationContext();
    this.store = store;
  }

  public void run(DownloadJobStore.Job job, TransferControl control) {
    DownloadEngine engine = null;
    int legacy = (int) job.id;
    try {
      JSONObject request = store.request(job.id);
      Map<String, String> headers = new LinkedHashMap<>();
      JSONObject saved = request.optJSONObject("headers");
      if (saved != null) {
        Iterator<String> keys = saved.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          if (Arrays.asList("cookie", "referer", "origin", "user-agent")
              .contains(key.toLowerCase(Locale.ROOT))) headers.put(key, saved.getString(key));
        }
      }
      control.check();
      if (!store.transition(
          job.id, DownloadJobStore.State.PREPARING, DownloadJobStore.State.DOWNLOADING)) return;
      DownloadHistory.begin(context, legacy, job.host, job.type);
      engine =
          new DownloadEngine(
              store.directory(job.id),
              (phase, percent) -> {
                DownloadJobStore.Job current = store.get(job.id);
                if (current == null) return;
                store.progress(
                    job.id,
                    phase,
                    percent,
                    current.bytes,
                    current.total,
                    current.segment,
                    current.segments);
                DownloadHistory.progress(context, legacy, phase, percent);
                context
                    .getSystemService(android.app.NotificationManager.class)
                    .notify(
                        JobNotifications.SUMMARY,
                        JobNotifications.running(context, store.get(job.id)));
              },
              new JobJournal(store, job.id),
              control);
      DownloadEngine.Result result =
          engine.download(
              request.getString("url"), MediaCandidate.MediaType.valueOf(job.type), headers);
      control.check();
      if (!store.transition(
          job.id, DownloadJobStore.State.DOWNLOADING, DownloadJobStore.State.FINALIZING)) return;
      JSONObject naming = store.request(job.id), metadata = naming.optJSONObject("metadata");
      String disposition =
          naming.optString(
              "disposition", metadata == null ? "" : metadata.optString("disposition"));
      String name =
          DownloadFileName.choose(
              disposition,
              metadata == null ? "" : metadata.optString("title"),
              naming.getString("url"),
              metadata == null ? "" : metadata.optString("mediaTitle"),
              result.extension,
              System.currentTimeMillis());
      Uri uri = publish(job.id, result, name, control);
      control.check();
      store.complete(job.id, uri.toString(), displayName(uri, name));
      DownloadJobStore.Job completed = store.get(job.id);
      if (completed.state != DownloadJobStore.State.COMPLETED) {
        store.cleanup(job.id);
        return;
      }
      DownloadHistory.state(context, legacy, "완료", uri.toString());
      store.cleanup(job.id);
      JobNotifications.status(context, completed);
    } catch (Exception error) {
      try {
        control.check();
      } catch (TransferControl.Stopped stopped) {
        error = stopped;
      }
      DownloadJobStore.Job current = store.get(job.id);
      if (current == null) return;
      if (current.state == DownloadJobStore.State.CANCELED) {
        store.cleanup(job.id);
        return;
      }
      if (error instanceof TransferControl.Stopped) {
        if (((TransferControl.Stopped) error).reason == TransferControl.Stop.CANCEL) {
          store.state(job.id, DownloadJobStore.State.CANCELED, "");
          store.cleanup(job.id);
          return;
        }
        if (current.state != DownloadJobStore.State.PAUSED)
          store.state(
              job.id, DownloadJobStore.State.PAUSED, control.userRequested() ? "" : "OS_STOP");
        DownloadHistory.state(context, legacy, "일시정지", null);
      } else if (current.state != DownloadJobStore.State.PAUSED) {
        DownloadError.Category category =
            error instanceof DownloadError
                ? ((DownloadError) error).category
                : error instanceof java.security.GeneralSecurityException
                    ? DownloadError.Category.AUTH
                    : error instanceof java.net.SocketException
                            || error instanceof java.net.SocketTimeoutException
                            || error instanceof java.net.UnknownHostException
                        ? DownloadError.Category.NETWORK
                        : DownloadError.Category.UNKNOWN;
        store.state(
            job.id,
            category == DownloadError.Category.AUTH
                    || category == DownloadError.Category.EXPIRED_URL
                ? DownloadJobStore.State.NEEDS_REFRESH
                : DownloadJobStore.State.FAILED,
            category.name());
        DownloadHistory.state(
            context,
            legacy,
            "실패 · " + JobNotifications.message(category.name(), DownloadJobStore.State.FAILED),
            null);
      }
      JobNotifications.status(context, store.get(job.id));
    }
  }

  private String displayName(Uri uri, String fallback) {
    try (android.database.Cursor c =
        context
            .getContentResolver()
            .query(uri, new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
      return c != null && c.moveToFirst() ? c.getString(0) : fallback;
    }
  }

  private Uri publish(long id, DownloadEngine.Result result, String name, TransferControl control)
      throws Exception {
    DownloadJobStore.Job job = store.get(id);
    Uri uri = job.pending.isEmpty() ? null : Uri.parse(job.pending);
    if (uri == null) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      values.put(MediaStore.MediaColumns.MIME_TYPE, result.mime);
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          (result.mime.startsWith("audio/")
                  ? Environment.DIRECTORY_MUSIC
                  : Environment.DIRECTORY_MOVIES)
              + "/Adownloader");
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      uri =
          context
              .getContentResolver()
              .insert(
                  result.mime.startsWith("audio/")
                      ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                      : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                  values);
      if (uri == null) throw new DownloadError(DownloadError.Category.STORAGE);
      store.pending(id, uri.toString());
    }
    if (new StatFs(context.getFilesDir().getPath()).getAvailableBytes()
        < result.file.length() + 8 * 1024 * 1024)
      throw new DownloadError(DownloadError.Category.STORAGE);
    try (InputStream in = new FileInputStream(result.file);
        OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
      if (out == null) throw new DownloadError(DownloadError.Category.STORAGE);
      byte[] buffer = new byte[128 * 1024];
      int n;
      while ((n = in.read(buffer)) != -1) {
        control.check();
        out.write(buffer, 0, n);
      }
    }
    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
      if (fd == null) throw new DownloadError(DownloadError.Category.STORAGE);
      PostProcessor.inspect(
          fd.getFileDescriptor(),
          0,
          result.mime.startsWith("video/"),
          result.mime.startsWith("audio/"));
    }
    control.check();
    ContentValues published = new ContentValues();
    published.put(MediaStore.MediaColumns.IS_PENDING, 0);
    context.getContentResolver().update(uri, published, null, null);
    return uri;
  }
}
