package com.adownloader.app.jobs;

import android.content.*;

public final class JobActionReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    PendingResult result = goAsync();
    DownloadJobs.get(context)
        .action(intent.getLongExtra("job", -1), intent.getAction(), result::finish);
  }
}
