package com.adownloader.app.jobs;

import android.app.*;
import android.content.*;
import android.net.Uri;
import com.adownloader.app.MainActivity;

public final class JobNotifications {
  public static final int SUMMARY = 6000;
  private static final String CHANNEL = "adownloader_jobs";

  public static void channel(Context context) {
    context
        .getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "Adownloader 작업", NotificationManager.IMPORTANCE_LOW));
  }

  private static PendingIntent action(Context context, long id, String action) {
    Intent intent =
        new Intent(context, JobActionReceiver.class)
            .setAction(action)
            .setData(Uri.parse("adownloader://job/" + id + "/" + action))
            .putExtra("job", id);
    return PendingIntent.getBroadcast(
        context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private static PendingIntent resume(Context context, long id) {
    return PendingIntent.getActivity(
        context,
        0,
        new Intent(context, MainActivity.class)
            .setAction("RESUME_JOB")
            .setData(Uri.parse("adownloader://resume/" + id))
            .putExtra("job", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public static Notification running(Context context, DownloadJobStore.Job job) {
    channel(context);
    Notification.Builder builder =
        new Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job == null ? "Adownloader" : job.name)
            .setContentText(job == null ? "작업 준비 중" : job.phase)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setGroup("downloads")
            .setGroupSummary(true);
    if (job != null) {
      builder
          .setProgress(100, job.percent, job.total < 0 && job.percent == 0)
          .addAction(
              new Notification.Action.Builder(null, "일시정지", action(context, job.id, "PAUSE"))
                  .build())
          .addAction(
              new Notification.Action.Builder(null, "취소", action(context, job.id, "CANCEL"))
                  .build());
    }
    return builder.build();
  }

  public static void status(Context context, DownloadJobStore.Job job) {
    channel(context);
    Notification.Builder builder =
        new Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(job.name)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setGroup("downloads");
    if (job.state == DownloadJobStore.State.COMPLETED) {
      builder.setContentText("다운로드 완료");
      builder.setContentIntent(
          PendingIntent.getActivity(
              context,
              0,
              new Intent(Intent.ACTION_VIEW)
                  .setDataAndType(
                      Uri.parse(job.uri), job.type.equals("AUDIO") ? "audio/*" : "video/*")
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
              PendingIntent.FLAG_IMMUTABLE));
    } else {
      builder
          .setContentText(message(job.error, job.state))
          .addAction(new Notification.Action.Builder(null, "재개", resume(context, job.id)).build())
          .addAction(
              new Notification.Action.Builder(null, "취소", action(context, job.id, "CANCEL"))
                  .build());
    }
    context.getSystemService(NotificationManager.class).notify("job:" + job.id, 0, builder.build());
  }

  public static String message(String error, DownloadJobStore.State state) {
    if (state == DownloadJobStore.State.PAUSED) return "일시정지됨 · 이어받을 수 있습니다.";
    if (state == DownloadJobStore.State.NEEDS_REFRESH) return "영상 주소가 만료되었습니다. 원래 페이지를 다시 열어 주세요.";
    switch (error) {
      case "STORAGE":
        return "저장 공간 또는 임시 파일을 확인해 주세요.";
      case "DRM":
        return "보호된 영상은 다운로드할 수 없습니다.";
      case "AUTH":
      case "EXPIRED_URL":
        return "영상에 접근할 수 없습니다. 원래 페이지를 다시 열어 주세요.";
      case "NETWORK":
        return "네트워크 연결을 확인한 뒤 다시 시도해 주세요.";
      case "SERVER":
        return "서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.";
      case "LEGACY_INTERRUPTED":
        return "이전 버전에서 중단된 작업입니다. 영상을 다시 선택해 주세요.";
      default:
        return "이 형식은 지원하지 않거나 파일이 완전하지 않습니다.";
    }
  }
}
