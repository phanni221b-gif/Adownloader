package com.adownloader.app.browser;

import android.content.Context;

import org.mozilla.geckoview.GeckoResult;
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
    private String latestState;
    private String location;
    private long intentionalNavigationUntil;

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
            public void onLocationChange(GeckoSession geckoSession,
                                         String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                location = url;
                if (listener != null && url != null) listener.onLocationChanged(url);
            }

            @Override
            public void onCanGoBack(GeckoSession geckoSession, boolean value) {
                canGoBack = value;
                emitNavigationState();
            }

            @Override
            public void onCanGoForward(GeckoSession geckoSession, boolean value) {
                canGoForward = value;
                emitNavigationState();
            }

            @Override
            public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(
                    GeckoSession geckoSession,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                if (request.hasUserGesture || request.isDirectNavigation) intentionalNavigationUntil = android.os.SystemClock.elapsedRealtime() + 10_000;
                String scheme = android.net.Uri.parse(request.uri).getScheme();
                if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme))) {
                    if (listener != null) listener.onPopupRequested(request.uri);
                    return GeckoResult.deny();
                }
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (listener != null) listener.onPopupRequested(request.uri);
                    return GeckoResult.deny();
                }
                // Permit normal link/login redirect chains. Gate unsolicited cross-site page takeovers.
                if (!request.hasUserGesture && !request.isDirectNavigation && location != null &&
                        android.os.SystemClock.elapsedRealtime() > intentionalNavigationUntil &&
                        !sameHost(location, request.uri)) {
                    if (listener != null) listener.onPopupRequested(request.uri);
                    return GeckoResult.deny();
                }
                return null;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession geckoSession, String uri) {
                // Fallback for popups that do not expose TARGET_WINDOW_NEW first.
                if (listener != null) listener.onPopupRequested(uri);
                return null;
            }
        });

        newSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onSessionStateChange(GeckoSession source, GeckoSession.SessionState state) { latestState = state.toString(); }
            @Override
            public void onProgressChange(GeckoSession geckoSession, int progress) {
                if (listener != null) listener.onPageProgress(progress);
            }
        });

        newSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession geckoSession) {
                if (listener != null) listener.onBrowserCrashed();
            }

            @Override
            public void onKill(GeckoSession geckoSession) {
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
        intentionalNavigationUntil = android.os.SystemClock.elapsedRealtime() + 10_000;
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

    public void setActive(boolean active) { if (session != null) session.setActive(active); }

    public void save(java.util.function.Consumer<String> consumer) {
        if (latestState != null) consumer.accept(latestState);
    }

    public boolean restore(String state) {
        if (state == null) return false;
        try { session.restoreState(GeckoSession.SessionState.fromString(state)); return true; }
        catch (Exception ignored) { return false; }
    }

    private void emitNavigationState() {
        if (listener != null) listener.onNavigationStateChanged(canGoBack, canGoForward);
    }
    private boolean sameHost(String first, String second) {
        String a = android.net.Uri.parse(first).getHost(), b = android.net.Uri.parse(second).getHost();
        return a != null && a.equalsIgnoreCase(b);
    }
}
