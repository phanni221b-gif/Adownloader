package com.adownloader.app.jobs;

import android.app.job.*;
import android.content.*;
import android.os.*;
import com.adownloader.app.*;
import com.adownloader.app.download.*;
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
  private final java.util.Set<Long> deferredResume =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  public boolean isExecuting(long id) {
    return active.containsKey(id);
  }

  private final ExecutorService refreshWorkers = Executors.newFixedThreadPool(1);
  private final ConcurrentMap<Long, TransferControl> refreshing = new ConcurrentHashMap<>();
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
            String identity = candidate.getIdentityKey();
            id =
                store.enqueue(
                    identity,
                    candidate.getType().name(),
                    DownloadFileName.label(candidate.getMetadata(), candidate.getUrl()),
                    android.net.Uri.parse(candidate.getUrl()).getHost(),
                    new JSONObject()
                        .put("url", candidate.getUrl())
                        .put("page", candidate.getPageUrl())
                        .put("headers", headers)
                        .put("metadata", new JSONObject(candidate.getMetadata())));
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
    try {
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
    } catch (SecurityException | IllegalStateException restricted) {
      commands.execute(
          () -> {
            for (DownloadJobStore.Job job : store.list())
              if (job.state == DownloadJobStore.State.QUEUED) {
                store.state(job.id, DownloadJobStore.State.PAUSED, "OS_STOP");
                JobNotifications.status(context, store.get(job.id));
              }
          });
    }
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
        } catch (RuntimeException unexpected) {
          store.state(job.id, DownloadJobStore.State.FAILED, "UNKNOWN");
        } finally {
          commands
              .submit(
                  () -> {
                    active.remove(job.id);
                    if (deferredResume.remove(job.id)) {
                      DownloadJobStore.Job current = store.get(job.id);
                      if (current != null
                          && (current.state == DownloadJobStore.State.PAUSED
                              || current.state == DownloadJobStore.State.FAILED)) {
                        try {
                          prepareRetry(current);
                          store.state(job.id, DownloadJobStore.State.QUEUED, "");
                        } catch (Exception failure) {
                          store.state(job.id, DownloadJobStore.State.FAILED, "STORAGE");
                        }
                      }
                    }
                  })
              .get();
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

  public void offerRefresh(MediaCandidate candidate, java.util.function.LongConsumer callback) {
    commands.execute(
        () -> {
          DownloadJobStore.Job job = store.needsRefresh(candidate.getIdentityKey());
          if (job != null) main.post(() -> callback.accept(job.id));
        });
  }

  public void refresh(
      long id, MediaCandidate candidate, java.util.function.Consumer<String> callback) {
    TransferControl control = new TransferControl();
    if (refreshing.putIfAbsent(id, control) != null) return;
    commands.execute(
        () -> {
          DownloadJobStore.Job job = store.get(id);
          if (job == null
              || job.state != DownloadJobStore.State.NEEDS_REFRESH
              || !job.identity.equals(candidate.getIdentityKey())) {
            refreshing.remove(id);
            return;
          }
          JSONObject old;
          try {
            old = store.request(id);
          } catch (Exception unreadable) {
            old = new JSONObject();
          }
          JSONObject previous = old;
          refreshWorkers.execute(
              () -> {
                try {
                  control.check();
                  boolean segmented =
                      candidate.getType() == MediaCandidate.MediaType.HLS
                          || candidate.getType() == MediaCandidate.MediaType.DASH;
                  MediaProbeResult probe =
                      segmented
                          ? MediaProbeService.shared()
                              .probe(
                                  candidate.getUrl(),
                                  candidate.getType(),
                                  candidate.getHeaders(),
                                  control)
                          : null;
                  boolean compatible = previous.has("url");
                  if (segmented) {
                    List<ManifestParser.Track> fresh = probe.tracks();
                    List<ManifestParser.Track> oldPlan =
                        previous.has("plan")
                            ? PlanCodec.decode(previous.getJSONArray("plan"))
                            : null;
                    compatible = oldPlan != null && RefreshPlanMatcher.compatible(oldPlan, fresh);
                    if (oldPlan != null
                        && (!RefreshPlanMatcher.qualityNotLower(oldPlan, fresh)
                            || !compatible && !RefreshPlanMatcher.knownQuality(oldPlan)))
                      throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
                  }
                  boolean reuse = compatible;
                  commands.execute(
                      () -> {
                        try {
                          control.check();
                          DownloadJobStore.Job current = store.get(id);
                          if (current == null
                              || current.state != DownloadJobStore.State.NEEDS_REFRESH) return;
                          if (!reuse) {
                            if (!store.cleanup(id))
                              throw new DownloadError(DownloadError.Category.STORAGE);
                            previous.remove("plan");
                            previous.remove("directFiles");
                            previous.remove("disposition");
                          }
                          JSONObject headers = new JSONObject();
                          candidate
                              .getHeaders()
                              .forEach(
                                  (key, value) -> {
                                    if (Arrays.asList("cookie", "referer", "origin", "user-agent")
                                        .contains(key.toLowerCase(Locale.ROOT)))
                                      try {
                                        headers.put(key, value);
                                      } catch (JSONException ignored) {
                                      }
                                  });
                          previous
                              .put("url", candidate.getUrl())
                              .put("page", candidate.getPageUrl())
                              .put("headers", headers)
                              .put("metadata", new JSONObject(candidate.getMetadata()));
                          if (probe != null) {
                            previous.put("plan", PlanCodec.encode(probe.tracks()));
                            if (!probe.disposition.isEmpty())
                              previous.put("disposition", probe.disposition);
                          }
                          control.check();
                          if (store.refreshed(
                              id,
                              previous,
                              DownloadFileName.label(candidate.getMetadata(), candidate.getUrl()),
                              !reuse)) {
                            main.post(
                                () -> {
                                  schedule();
                                  if (callback != null)
                                    callback.accept(
                                        reuse
                                            ? "새 주소로 이어받습니다. 파일 응답도 다시 검증합니다."
                                            : "영상 구성이 달라 처음부터 다시 받습니다.");
                                });
                          }
                        } catch (Exception failure) {
                          refreshFailure(id, failure, callback);
                        } finally {
                          refreshing.remove(id, control);
                        }
                      });
                } catch (Exception failure) {
                  refreshing.remove(id, control);
                  commands.execute(() -> refreshFailure(id, failure, callback));
                }
              });
        });
  }

  private void refreshFailure(
      long id, Exception failure, java.util.function.Consumer<String> callback) {
    DownloadJobStore.Job job = store.get(id);
    if (job == null
        || job.state == DownloadJobStore.State.CANCELED
        || failure instanceof TransferControl.Stopped) return;
    String message = "새 주소를 확인하지 못했습니다. 페이지에서 영상을 다시 재생해 주세요.";
    if (failure instanceof DownloadError
        && ((DownloadError) failure).category == DownloadError.Category.STORAGE) {
      store.state(id, DownloadJobStore.State.FAILED, "STORAGE");
      message = "저장 공간 또는 임시 파일을 확인해 주세요.";
    }
    String result = message;
    if (callback != null) main.post(() -> callback.accept(result));
  }

  private void prepareRetry(DownloadJobStore.Job job) throws Exception {
    if (job.state == DownloadJobStore.State.FAILED && job.error.equals("STORAGE")) {
      JSONObject request = store.request(job.id);
      if (!store.cleanup(job.id))
        throw new com.adownloader.app.download.DownloadError(
            com.adownloader.app.download.DownloadError.Category.STORAGE);
      request.remove("plan");
      request.remove("directFiles");
      store.writeContext(job.id, request);
    }
  }

  public void action(long id, String action, Runnable done) {
    TransferControl refreshingControl = refreshing.get(id);
    if (refreshingControl != null && action.equals("CANCEL"))
      refreshingControl.userStop(TransferControl.Stop.CANCEL);
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
              if (active.containsKey(id)) {
                deferredResume.add(id);
                main.post(this::schedule);
              } else {
                prepareRetry(job);
                store.state(id, DownloadJobStore.State.QUEUED, "");
                main.post(this::schedule);
              }
            }
          } catch (Exception e) {
            if (e instanceof DownloadError
                && ((DownloadError) e).category == DownloadError.Category.STORAGE)
              store.state(id, DownloadJobStore.State.FAILED, "STORAGE");
            else store.state(id, DownloadJobStore.State.NEEDS_REFRESH, "AUTH");
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
