package com.adownloader.app.browser;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.List;

/** Owns GeckoRuntime/GeckoSession lifecycle and exposes only browser actions to the Activity. */
public final class BrowserSessionManager {
    public interface Listener {
        void onLocationChanged(String url);
        void onNavigationStateChanged(boolean canGoBack, boolean canGoForward);
        void onPageProgress(int progress);
        void onPopupRequested(String url);
        void onBrowserCrashed();
    }

    private static volatile GeckoRuntime runtime;

    private final GeckoView geckoView;
    private final Listener listener;
    private GeckoSession session;
    private boolean canGoBack;
    private boolean canGoForward;

    public BrowserSessionManager(Context context, GeckoView geckoView, Listener listener) {
        this.geckoView = geckoView;
        this.listener = listener;
        if (runtime == null) {
            synchronized (BrowserSessionManager.class) {
                if (runtime == null) runtime = GeckoRuntime.create(context.getApplicationContext());
            }
        }
        createSession();
    }

    private void createSession() {
        GeckoSession newSession = new GeckoSession();
        newSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(@NonNull GeckoSession geckoSession,
                                         @Nullable String url,
                                         @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         @NonNull Boolean hasUserGesture) {
                if (listener != null && url != null) listener.onLocationChanged(url);
            }

            @Override
            public void onCanGoBack(@NonNull GeckoSession geckoSession, boolean value) {
                canGoBack = value;
                emitNavigationState();
            }

            @Override
            public void onCanGoForward(@NonNull GeckoSession geckoSession, boolean value) {
                canGoForward = value;
                emitNavigationState();
            }

            @Nullable
            @Override
            public org.mozilla.geckoview.GeckoResult<GeckoSession> onNewSession(
                    @NonNull GeckoSession geckoSession,
                    @NonNull String uri) {
                // Block first. The UI can explicitly reopen the URL in the current tab.
                if (listener != null) listener.onPopupRequested(uri);
                return null;
            }
        });

        newSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onProgressChange(@NonNull GeckoSession geckoSession, int progress) {
                if (listener != null) listener.onPageProgress(progress);
            }
        });

        newSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(@NonNull GeckoSession geckoSession) {
                if (listener != null) listener.onBrowserCrashed();
            }

            @Override
            public void onKill(@NonNull GeckoSession geckoSession) {
                if (listener != null) listener.onBrowserCrashed();
            }
        });

        newSession.open(runtime);
        session = newSession;
        geckoView.setSession(newSession);
    }

    public GeckoRuntime getRuntime() {
        return runtime;
    }

    public GeckoSession getSession() {
        return session;
    }

    public void load(String url) {
        if (session != null && url != null && !url.isBlank()) session.loadUri(url);
    }

    public void reload() {
        if (session != null) session.reload();
    }

    public void goBack() {
        if (session != null && canGoBack) session.goBack();
    }

    public void goForward() {
        if (session != null && canGoForward) session.goForward();
    }

    public boolean canGoBack() {
        return canGoBack;
    }

    public void recover(String url) {
        try {
            GeckoSession old = geckoView.releaseSession();
            if (old != null) old.close();
        } catch (Throwable ignored) {
        }
        canGoBack = false;
        canGoForward = false;
        createSession();
        if (url != null && !url.isBlank()) load(url);
    }

    public void close() {
        try {
            GeckoSession old = geckoView.releaseSession();
            if (old != null) old.close();
        } catch (Throwable ignored) {
        }
        session = null;
    }

    private void emitNavigationState() {
        if (listener != null) listener.onNavigationStateChanged(canGoBack, canGoForward);
    }
}
