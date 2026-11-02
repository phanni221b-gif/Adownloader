package com.adownloader.app.ui;

import android.app.Activity;
import android.content.*;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import com.adownloader.app.R;
import com.adownloader.app.diagnostics.FailureJournal;

/** Files leave private cache only after this explicit user action. */
public final class FailureJournalUi {
  private FailureJournalUi() {}

  public static Intent intent(android.content.Context context, java.io.File file) {
    var uri = FileProvider.getUriForFile(context, context.getPackageName() + ".diagnostics", file);
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("실패 진단 보고서", uri));
    return intent;
  }

  public static void share(Activity activity, long job) {
    var journal = FailureJournal.get(activity);
    journal.execute(
        () -> {
          try {
            var file = journal.export(job);
            Intent shareIntent = file == null ? null : intent(activity, file);
            activity.runOnUiThread(
                () -> {
                  if (activity.isFinishing() || activity.isDestroyed()) return;
                  if (file == null) {
                    Toast.makeText(
                            activity, "저장된 실패 보고서가 없습니다. 새 실패부터 자동 기록됩니다.", Toast.LENGTH_LONG)
                        .show();
                    return;
                  }
                  try {
                    activity.startActivity(Intent.createChooser(shareIntent, "실패 진단 보고서 공유"));
                  } catch (Exception unavailable) {
                    Toast.makeText(activity, "보고서를 공유할 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
                  }
                });
          } catch (Exception failed) {
            activity.runOnUiThread(
                () ->
                    Toast.makeText(
                            activity, "진단 보고서를 준비하지 못했습니다. 저장 공간을 확인해 주세요.", Toast.LENGTH_LONG)
                        .show());
          }
        });
  }

  public static void clear(Activity activity) {
    UiSheets.action(
        activity,
        R.drawable.ic_settings,
        "진단 기록 모두 삭제",
        "실패 보고서와 내보낸 진단 파일만 삭제합니다. 다운로드 파일과 기록은 유지됩니다.",
        "취소",
        "삭제",
        () -> {
          var journal = FailureJournal.get(activity);
          var finished = journal.clear();
          journal.execute(
              () -> {
                try {
                  finished.get();
                } catch (Exception failed) {
                  activity.runOnUiThread(
                      () ->
                          Toast.makeText(activity, "진단 기록을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show());
                  return;
                }
                activity.runOnUiThread(
                    () -> Toast.makeText(activity, "진단 기록을 삭제했습니다.", Toast.LENGTH_SHORT).show());
              });
        },
        null,
        null);
  }
}
