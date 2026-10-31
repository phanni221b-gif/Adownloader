package com.adownloader.app;

import android.app.*;
import android.content.*;
import android.os.*;
import com.adownloader.app.jobs.*;
import java.util.*;
import org.json.*;

/** Android 11–13 foreground host; the durable queue, not this Service, owns downloads. */
public final class DownloadService extends Service implements DownloadJobs.Host {
  public static final String ACTION_DOWNLOAD = "com.adownloader.app.DOWNLOAD",
      EXTRA_URL = "url",
      EXTRA_TYPE = "type",
      EXTRA_HEADERS = "headers",
      EXTRA_FILENAME = "filename";
  private DownloadJobs jobs;
  private int generation;
  private boolean stopped;
  private PowerManager.WakeLock wake;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable renew =
      new Runnable() {
        public void run() {
          if (!stopped) {
            wake.acquire(10 * 60 * 1000L);
            handler.postDelayed(this, 8 * 60 * 1000L);
          }
        }
      };

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    startForeground(JobNotifications.SUMMARY, JobNotifications.running(this, null));
    jobs = DownloadJobs.get(this);
    jobs.attach(this);
    wake =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Adownloader:durable-transfer");
    wake.setReferenceCounted(false);
    renew.run();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_DOWNLOAD.equals(intent.getAction())) {
      try {
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject saved =
            new JSONObject(
                intent.getStringExtra(EXTRA_HEADERS) == null
                    ? "{}"
                    : intent.getStringExtra(EXTRA_HEADERS));
        Iterator<String> names = saved.keys();
        while (names.hasNext()) {
          String name = names.next();
          headers.put(name, saved.getString(name));
        }
        MediaCandidate candidate =
            new MediaCandidate(
                intent.getStringExtra(EXTRA_URL),
                MediaCandidate.MediaType.valueOf(intent.getStringExtra(EXTRA_TYPE)),
                "legacy",
                null,
                headers,
                System.currentTimeMillis());
        jobs.enqueue(candidate, id -> wake());
      } catch (Exception invalid) {
        stopSelf();
      }
    } else wake();
    return START_NOT_STICKY;
  }

  @Override
  public void wake() {
    if (stopped) return;
    int current = ++generation;
    jobs.drain(
        () -> {
          if (stopped) return;
          if (current != generation) {
            wake();
            return;
          }
          jobs.detach(this);
          stopForeground(STOP_FOREGROUND_REMOVE);
          stopSelf();
        });
  }

  @Override
  public void onTimeout(int startId, int type) {
    stopped = true;
    jobs.detach(this);
    jobs.stop();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    stopped = true;
    handler.removeCallbacksAndMessages(null);
    if (jobs != null) jobs.detach(this);
    if (wake != null && wake.isHeld()) wake.release();
    super.onDestroy();
  }
}
