package com.adownloader.app.browser;

import android.app.Activity;
import android.app.AlertDialog;

import java.net.URI;

/** 18+-style popup policy: block first, then let the user explicitly open it. */
public final class PopupController {
    public interface Opener {
        void open(String url);
    }

    private final Activity activity;
    private final Opener opener;

    public PopupController(Activity activity, Opener opener) {
        this.activity = activity;
        this.opener = opener;
    }

    public void onPopupRequested(String url) {
        if (url == null || url.isBlank() || activity.isFinishing()) return;
        String host = url;
        try {
            String parsed = URI.create(url).getHost();
            if (parsed != null && !parsed.isBlank()) host = parsed;
        } catch (Throwable ignored) {
        }

        new AlertDialog.Builder(activity)
                .setTitle("새 창을 차단했습니다")
                .setMessage(host + "\n\n이 페이지를 현재 탭에서 열까요?")
                .setNegativeButton("차단", null)
                .setPositiveButton("한 번 열기", (dialog, which) -> opener.open(url))
                .show();
    }
}
