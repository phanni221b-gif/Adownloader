package com.adownloader.app.jobs;

import android.app.job.*;
import android.os.*;
import androidx.annotation.RequiresApi;

@RequiresApi(34)
public final class TransferJobService extends JobService implements DownloadJobs.Host {
  private JobParameters parameters;
  private int generation;
  private boolean stopped;
  private DownloadJobs jobs;

  @Override
  public boolean onStartJob(JobParameters parameters) {
    this.parameters = parameters;
    stopped = false;
    jobs = DownloadJobs.get(this);
    jobs.attach(this);
    setNotification(
        parameters,
        JobNotifications.SUMMARY,
        JobNotifications.running(this, null),
        JOB_END_NOTIFICATION_POLICY_REMOVE);
    wake();
    return true;
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
          jobFinished(parameters, false);
        });
  }

  @Override
  public boolean onStopJob(JobParameters parameters) {
    stopped = true;
    generation++;
    if (jobs != null) {
      jobs.detach(this);
      jobs.stop();
    }
    return true;
  }

  @Override
  public void onDestroy() {
    if (jobs != null) jobs.detach(this);
    super.onDestroy();
  }
}
