package com.adownloader.app.ui;

import android.content.*;
import java.util.*;
import org.json.JSONObject;

/** UI-only job telemetry. Never persists a media URL, request header, cookie or page URL. */
public final class DownloadHistory {
  private DownloadHistory() {}

  public static String userError(String error) {
    String text = error == null ? "" : error;
    if (text.contains("DRM") || text.contains("SAMPLE-AES") || text.contains("보호된 영상"))
      return "보호된 영상은 다운로드할 수 없습니다.";
    if (text.contains("HTTP 401") || text.contains("HTTP 403") || text.contains("영상에 접근"))
      return "이 영상에 접근할 수 없습니다. 사이트에서 재생 가능한지 확인하세요.";
    if (text.contains("공간") || text.contains("ENOSPC")) return "저장 공간이 부족합니다.";
    if (text.contains("HTTP")
        || text.contains("네트워크")
        || text.contains("timeout")
        || text.contains("Host")
        || text.contains("연결")) return "연결이 끊겼습니다. 네트워크 상태를 확인해 주세요.";
    if (text.contains("중단")) return "다운로드가 중단되었습니다. 다시 시작해 주세요.";
    return "이 영상 형식은 아직 지원하지 않거나 파일이 완전하지 않습니다.";
  }

  public static List<JSONObject> read(Context context) {
    List<JSONObject> rows = new ArrayList<>();
    int queue = 0;
    for (com.adownloader.app.jobs.DownloadJobStore.Job job :
        com.adownloader.app.jobs.DownloadJobs.get(context).store.list())
      try {
        boolean recovering =
            (job.state == com.adownloader.app.jobs.DownloadJobStore.State.PREPARING
                    || job.state == com.adownloader.app.jobs.DownloadJobStore.State.DOWNLOADING
                    || job.state == com.adownloader.app.jobs.DownloadJobStore.State.FINALIZING)
                && !com.adownloader.app.jobs.DownloadJobStore.ownedHere(job);
        if (recovering) {
          job.state = com.adownloader.app.jobs.DownloadJobStore.State.PAUSED;
          job.phase = "복구 정보 확인 중";
        }
        JSONObject row =
            new JSONObject()
                .put("job", job.id)
                .put("recovering", recovering)
                .put("jobState", job.state.name())
                .put(
                    "state",
                    job.state == com.adownloader.app.jobs.DownloadJobStore.State.NEEDS_REFRESH
                        ? com.adownloader.app.download.AccessPresentation.label(job.error)
                        : label(job.state.name()))
                .put("name", job.name)
                .put("host", job.host)
                .put("type", job.type)
                .put("created", job.created)
                .put("time", job.updated)
                .put("bytes", job.bytes)
                .put("total", job.total)
                .put("phase", job.phase)
                .put("segments", job.segments)
                .put("segment", job.segment)
                .put("error", job.error)
                .put("uri", job.uri)
                .put("canOpenPage", job.hasPage);
        if (job.total > 0
            || job.segments > 0
            || job.state == com.adownloader.app.jobs.DownloadJobStore.State.FINALIZING)
          row.put("percent", job.percent);
        if (job.state == com.adownloader.app.jobs.DownloadJobStore.State.QUEUED)
          row.put("queue", ++queue);
        rows.add(row);
      } catch (Exception ignored) {
      }
    return rows;
  }

  public static String label(String state) {
    switch (state) {
      case "QUEUED":
        return "대기";
      case "PREPARING":
        return "준비 중";
      case "DOWNLOADING":
        return "다운로드 중";
      case "PAUSED":
        return "일시정지";
      case "FINALIZING":
        return "검증·저장 중";
      case "COMPLETED":
        return "완료";
      case "NEEDS_REFRESH":
        return "접근 확인 필요";
      case "CANCELED":
        return "취소됨";
      default:
        return "실패";
    }
  }

  public static boolean active(JSONObject row) {
    String state = row.optString("jobState");
    return state.equals("QUEUED")
        || state.equals("PREPARING")
        || state.equals("DOWNLOADING")
        || state.equals("FINALIZING");
  }

  public static boolean done(JSONObject row) {
    return row.optString("jobState").equals("COMPLETED") || row.optString("state").equals("완료");
  }

  public static String size(Context context, long bytes) {
    return bytes > 0 ? android.text.format.Formatter.formatShortFileSize(context, bytes) : "";
  }

  public static String duration(long millis) {
    long seconds = millis / 1000;
    return seconds >= 3600
        ? String.format(
            Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }
}
