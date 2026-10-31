package com.adownloader.app.jobs;

import android.app.job.*;
import android.content.*;
import android.os.*;
import com.adownloader.app.*;
import com.adownloader.app.download.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Commands, OS scheduling and worker capacity are deliberately separate. */
public final class DownloadJobs {
  public interface Host {
    void wake();
  }

  private static DownloadJobs instance;

  public static synchronized DownloadJobs get(Context context) {
    if (instance == null) instance = new DownloadJobs(context);
    return instance;
  }

  private final Context context;
  public final DownloadJobStore store;
  private final ExecutorService commands = Executors.newSingleThreadExecutor(),
      workers = Executors.newFixedThreadPool(1);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ConcurrentMap<Long, TransferControl> active = new ConcurrentHashMap<>();
  private Host host;
  private boolean draining, wakeRequested;
  private volatile boolean permitted;
  private Runnable onIdle;

  private DownloadJobs(Context context) {
    this.context = context.getApplicationContext();
    store = new DownloadJobStore(context);
    commands.execute(
        () -> {
          store.migrate();
          store.reconcile();
        });
  }

  public void enqueue(MediaCandidate candidate, java.util.function.LongConsumer completed) {
    commands.execute(
        () -> {
          long id = -1;
          try {
            JSONObject headers = new JSONObject();
            candidate
                .getHeaders()
                .forEach(
                    (name, value) -> {
                      if (Arrays.asList("cookie", "referer", "origin", "user-agent")
                          .contains(name.toLowerCase(Locale.ROOT)))
                        try {
                          headers.put(name, value);
                        } catch (JSONException ignored) {
                        }
                    });
            String identity =
                DirectTransfer.hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            (candidate.getType() + "|" + candidate.getUrl())
                                .getBytes(StandardCharsets.UTF_8)));
            id =
                store.enqueue(
                    identity,
                    candidate.getType().name(),
                    "동영상 다운로드",
                    android.net.Uri.parse(candidate.getUrl()).getHost(),
                    new JSONObject()
                        .put("url", candidate.getUrl())
                        .put("page", candidate.getPageUrl())
                        .put("headers", headers));
          } catch (Exception failed) {
          }
          long result = id;
          main.post(
              () -> {
                if (result > 0) schedule();
                if (completed != null) completed.accept(result);
              });
        });
  }

  public void attach(Host host) {
    this.host = host;
    permitted = true;
  }

  public void detach(Host owner) {
    if (host == owner) {
      host = null;
      permitted = false;
    }
  }

  public void schedule() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post(this::schedule);
      return;
    }
    if (host != null) {
      host.wake();
      return;
    }
    if (Build.VERSION.SDK_INT >= 34) {
      JobScheduler scheduler = context.getSystemService(JobScheduler.class);
      if (scheduler.getPendingJob(6001) != null) return;
      JobInfo info =
          new JobInfo.Builder(6001, new ComponentName(context, TransferJobService.class))
              .setUserInitiated(true)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setPersisted(true)
              .setRequiresStorageNotLow(true)
              .build();
      if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS)
        commands.execute(
            () -> {
              for (DownloadJobStore.Job job : store.list())
                if (job.state == DownloadJobStore.State.QUEUED) {
                  store.state(job.id, DownloadJobStore.State.PAUSED, "");
                  JobNotifications.status(context, store.get(job.id));
                }
            });
    } else context.startForegroundService(new Intent(context, DownloadService.class));
  }

  public synchronized void drain(Runnable finished) {
    onIdle = finished;
    wakeRequested = true;
    if (draining) return;
    draining = true;
    workers.execute(this::pump);
  }

  private Runnable idleLocked() {
    draining = false;
    Runnable callback = onIdle;
    onIdle = null;
    return callback;
  }

  private void postIdle(Runnable callback) {
    if (callback != null) main.post(callback);
  }

  private void pump() {
    try {
      while (permitted) {
        DownloadJobStore.Job job = store.claim();
        if (job == null) {
          Runnable callback;
          synchronized (this) {
            if (wakeRequested) {
              wakeRequested = false;
              continue;
            }
            callback = idleLocked();
          }
          postIdle(callback);
          return;
        }
        TransferControl control = new TransferControl();
        active.put(job.id, control);
        try {
          new JobRunner(context, store).run(job, control);
        } finally {
          active.remove(job.id);
        }
      }
    } catch (Exception unavailable) {
      /* DB remains authoritative; never report unfinished jobs as complete. */
    }
    Runnable callback;
    synchronized (this) {
      callback = idleLocked();
    }
    postIdle(callback);
  }

  public void stop() {
    active.forEach((id, control) -> control.stop(TransferControl.Stop.PAUSE));
  }

  public void whenReady(Runnable callback) {
    commands.execute(() -> main.post(callback));
  }

  public void openPage(long id, java.util.function.Consumer<String> callback) {
    commands.execute(
        () -> {
          String page = null;
          try {
            page = store.request(id).optString("page");
            if (!(page.startsWith("http://") || page.startsWith("https://"))) page = null;
          } catch (Exception ignored) {
          }
          String result = page;
          main.post(() -> callback.accept(result));
        });
  }

  public void clearHistory(DownloadJobStore.State state) {
    commands.execute(() -> store.clearTerminal(state));
  }

  public void action(long id, String action, Runnable done) {
    TransferControl control = active.get(id);
    if (control != null && (action.equals("PAUSE") || action.equals("CANCEL")))
      control.userStop(
          action.equals("CANCEL") ? TransferControl.Stop.CANCEL : TransferControl.Stop.PAUSE);
    commands.execute(
        () -> {
          try {
            DownloadJobStore.Job job = store.get(id);
            if (job == null) return;
            if (action.equals("PRIORITIZE")) {
              store.prioritize(id);
              main.post(this::schedule);
            } else if (action.equals("CANCEL")) {
              store.state(id, DownloadJobStore.State.CANCELED, "");
              context
                  .getSystemService(android.app.NotificationManager.class)
                  .cancel("job:" + id, 0);
              if (!active.containsKey(id)) store.cleanup(id);
            } else if (action.equals("PAUSE")) {
              store.state(id, DownloadJobStore.State.PAUSED, "");
              JobNotifications.status(context, store.get(id));
            } else if (action.equals("RESUME") || action.equals("RETRY")) {
              if (job.state == DownloadJobStore.State.NEEDS_REFRESH) return;
              if (job.state == DownloadJobStore.State.FAILED && job.error.equals("STORAGE")) {
                JSONObject request = store.request(id);
                store.cleanup(id);
                request.remove("plan");
                request.remove("directFiles");
                store.writeContext(id, request);
              }
              if (!active.containsKey(id)) {
                store.state(id, DownloadJobStore.State.QUEUED, "");
                main.post(this::schedule);
              }
            }
          } catch (Exception e) {
            store.state(id, DownloadJobStore.State.NEEDS_REFRESH, "AUTH");
          } finally {
            TransferControl latest = active.get(id);
            if (latest != null && (action.equals("PAUSE") || action.equals("CANCEL")))
              latest.userStop(
                  action.equals("CANCEL")
                      ? TransferControl.Stop.CANCEL
                      : TransferControl.Stop.PAUSE);
            if (done != null) done.run();
          }
        });
  }
}
