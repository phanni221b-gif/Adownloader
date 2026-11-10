package com.adownloader.app.browser;

import android.content.Context;
import com.adownloader.app.media.MediaDetector;
import java.util.*;
import java.util.function.BiConsumer;
import org.json.*;
import org.mozilla.geckoview.*;

/** Bounded retained Gecko sessions; each tab owns navigation trust and media context. */
public final class BrowserSessionManager {
  public interface Listener {
    void onLocationChanged(String url);

    void onNavigationStateChanged(boolean back, boolean forward);

    void onPageProgress(int progress);

    void onPopupRequested(String url);

    void onBrowserCrashed();

    default void onPageFinished(String url, String title) {}

    default void onTabSelected(MediaDetector detector, String url) {}

    default void onContextLink(String url) {}

    default void onTabsChanged() {}
  }

  public static final class Tab {
    public final GeckoSession session = new GeckoSession();
    public final MediaDetector detector;
    private final NavigationPolicy policy = new NavigationPolicy();
    public String url = "about:blank", title = "";
    private String state;
    private boolean back, forward;
    private MediaSession playback;
    private int progress = 100;
    public android.graphics.Bitmap preview;
    private long previewGeneration;

    private Tab(MediaDetector.Listener listener) {
      detector = new MediaDetector(listener);
    }
  }

  private static volatile GeckoRuntime runtime;
  private final GeckoView view;
  private final Listener listener;
  private final List<Tab> tabs = new ArrayList<>();
  private Tab active;
  private boolean visible = true, constructing = true;
  private boolean previewPending;
  private boolean appForeground;
  private BiConsumer<GeckoSession, Boolean> observer;

  public BrowserSessionManager(Context context, GeckoView view, Listener listener) {
    this.view = view;
    this.listener = listener;
    runtime = GeckoRuntimes.get(context);
    newTab(null);
    constructing = false;
  }

  public List<Tab> tabs() {
    return Collections.unmodifiableList(new ArrayList<>(tabs));
  }

  public MediaDetector getDetector() {
    return active == null ? null : active.detector;
  }

  public MediaDetector detectorFor(GeckoSession session) {
    for (Tab tab : tabs) if (tab.session == session) return tab.detector;
    return null;
  }

  public void setSessionObserver(BiConsumer<GeckoSession, Boolean> value) {
    observer = value;
    if (value != null) for (Tab tab : tabs) value.accept(tab.session, true);
  }

  public boolean newTab(String url) {
    if (tabs.size() >= 5) return false;
    final Tab[] owner = new Tab[1];
    Tab tab =
        new Tab(
            (candidate, count) -> {
              if (active == owner[0] && listener instanceof MediaDetector.Listener)
                ((MediaDetector.Listener) listener).onCandidateAdded(candidate, count);
            });
    owner[0] = tab;
    tabs.add(tab);
    tab.session.setMediaSessionDelegate(
        new MediaSession.Delegate() {
          @Override
          public void onActivated(GeckoSession session, MediaSession media) {
            tab.playback = media;
            if (!appForeground) media.pause();
          }

          @Override
          public void onPlay(GeckoSession session, MediaSession media) {
            if (!appForeground) media.pause();
          }

          @Override
          public void onDeactivated(GeckoSession session, MediaSession media) {
            if (tab.playback == media) tab.playback = null;
          }
        });
    tab.session.setNavigationDelegate(
        new GeckoSession.NavigationDelegate() {
          @Override
          public void onLocationChange(
              GeckoSession source,
              String value,
              List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
              Boolean gesture) {
            tab.url = value == null ? "about:blank" : value;
            tab.preview = null;
            tab.previewGeneration++;
            tab.detector.pageChanged(tab.url);
            tab.state = SafeSessionState.visit(tab.state, tab.url, tab.title);
            tab.policy.committed(tab.url);
            if (active == tab && listener != null) listener.onLocationChanged(tab.url);
          }

          @Override
          public void onCanGoBack(GeckoSession source, boolean value) {
            tab.back = value;
            if (active == tab) emitNavigation();
          }

          @Override
          public void onCanGoForward(GeckoSession source, boolean value) {
            tab.forward = value;
            if (active == tab) emitNavigation();
          }

          @Override
          public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession source, LoadRequest request) {
            String scheme = android.net.Uri.parse(request.uri).getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if ((!web && !"about".equalsIgnoreCase(scheme))
                || request.target == TARGET_WINDOW_NEW
                || !tab.policy.allow(
                    "about:blank".equals(tab.url) ? null : tab.url,
                    request.uri,
                    request.hasUserGesture,
                    request.isDirectNavigation,
                    request.isRedirect)) {
              if (active == tab && listener != null) listener.onPopupRequested(request.uri);
              return GeckoResult.deny();
            }
            return null;
          }

          @Override
          public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
            if (active == tab && listener != null) listener.onPopupRequested(uri);
            return null;
          }

          @Override
          public GeckoResult<String> onLoadError(
              GeckoSession source, String uri, WebRequestError error) {
            tab.policy.failed(uri);
            return null;
          }
        });
    tab.session.setProgressDelegate(
        new GeckoSession.ProgressDelegate() {
          @Override
          public void onPageStart(GeckoSession source, String uri) {
            tab.policy.started(uri);
          }

          @Override
          public void onPageStop(GeckoSession source, boolean success) {
            tab.policy.stopped();
            if (success && listener != null) listener.onPageFinished(tab.url, tab.title);
          }

          @Override
          public void onSessionStateChange(GeckoSession source, GeckoSession.SessionState state) {
            applyHistory(tab, state);
          }

          @Override
          public void onProgressChange(GeckoSession source, int progress) {
            tab.progress = progress;
            if (active == tab && listener != null) listener.onPageProgress(progress);
          }
        });
    tab.session.setContentDelegate(
        new GeckoSession.ContentDelegate() {
          @Override
          public void onContextMenu(GeckoSession source, int x, int y, ContextElement element) {
            String target = element.linkUri != null ? element.linkUri : element.srcUri;
            if (active == tab && listener != null && SafeSessionState.safeUrl(target))
              listener.onContextLink(target);
          }

          @Override
          public void onTitleChange(GeckoSession source, String title) {
            tab.title = title == null ? "" : title.substring(0, Math.min(300, title.length()));
            tab.state = SafeSessionState.visit(tab.state, tab.url, tab.title);
            if (listener != null && !constructing) listener.onTabsChanged();
          }

          @Override
          public void onCrash(GeckoSession source) {
            if (active == tab && listener != null) listener.onBrowserCrashed();
          }

          @Override
          public void onKill(GeckoSession source) {
            if (active == tab && listener != null) listener.onBrowserCrashed();
          }
        });
    tab.session.setHistoryDelegate(
        new GeckoSession.HistoryDelegate() {
          @Override
          public void onHistoryStateChange(GeckoSession source, HistoryList history) {
            applyHistory(tab, history);
          }
        });
    tab.session.open(runtime);
    if (observer != null) observer.accept(tab.session, true);
    selectTab(tabs.size() - 1);
    if (url != null) load(url);
    if (listener != null && !constructing) listener.onTabsChanged();
    return true;
  }

  /** Small memory-only thumbnails. Never persist browsing screenshots or block tab switching. */
  public void capturePreview(Runnable changed) {
    Tab owner = active;
    if (owner == null
        || previewPending
        || !visible
        || view.getWidth() <= 0
        || view.getHeight() <= 0) return;
    long generation = ++owner.previewGeneration;
    previewPending = true;
    try {
      view.capturePixels()
          .accept(
              bitmap -> {
                previewPending = false;
                if (bitmap == null) return;
                if (!tabs.contains(owner) || generation != owner.previewGeneration) {
                  bitmap.recycle();
                  return;
                }
                float scale =
                    Math.min(1f, Math.min(240f / bitmap.getWidth(), 160f / bitmap.getHeight()));
                android.graphics.Bitmap small =
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)),
                        true);
                owner.preview = small;
                if (small != bitmap) bitmap.recycle();
                if (changed != null) changed.run();
              },
              unavailable -> {
                previewPending = false;
              });
    } catch (IllegalStateException unavailable) {
      previewPending = false;
      /* A display may be detaching. */
    }
  }

  public void selectTab(int index) {
    if (index < 0 || index >= tabs.size()) return;
    Tab next = tabs.get(index);
    if (active == next) return;
    if (active != null) {
      capturePreview(null);
      active.session.setActive(false);
      runtime.getWebExtensionController().setTabActive(active.session, false);
      view.releaseSession();
    }
    active = next;
    view.setSession(next.session);
    next.session.setActive(visible);
    runtime.getWebExtensionController().setTabActive(next.session, true);
    if (listener != null && !constructing) {
      listener.onTabSelected(next.detector, next.url);
      listener.onLocationChanged(next.url);
      listener.onPageProgress(next.progress);
    }
    emitNavigation();
    if (listener != null && !constructing) listener.onTabsChanged();
  }

  public void closeTab(int index) {
    if (index < 0 || index >= tabs.size()) return;
    Tab tab = tabs.get(index);
    boolean wasActive = active == tab;
    if (wasActive) {
      view.releaseSession();
      active = null;
    }
    tabs.remove(index);
    if (observer != null) observer.accept(tab.session, false);
    tab.session.close();
    tab.detector.reset();
    tab.previewGeneration++;
    tab.preview = null;
    if (tabs.isEmpty()) newTab(null);
    else if (wasActive) selectTab(Math.min(index, tabs.size() - 1));
    if (listener != null && !constructing) listener.onTabsChanged();
  }

  public GeckoRuntime getRuntime() {
    return runtime;
  }

  public GeckoSession getSession() {
    return active == null ? null : active.session;
  }

  public String getTitle() {
    return active == null ? "" : active.title;
  }

  public void load(String url) {
    if (active != null && url != null && !url.isBlank()) {
      active.policy.beginUserNavigation(url);
      active.session.loadUri(url);
    }
  }

  public void reload() {
    if (active != null) {
      active.policy.beginUserNavigation(active.url);
      active.session.reload();
    }
  }

  public void goBack() {
    if (active != null && active.back) {
      active.state = SafeSessionState.shift(active.state, -1);
      active.policy.beginUserNavigation(SafeSessionState.currentUrl(active.state));
      active.session.goBack();
    }
  }

  public void goForward() {
    if (active != null && active.forward) {
      active.state = SafeSessionState.shift(active.state, 1);
      active.policy.beginUserNavigation(SafeSessionState.currentUrl(active.state));
      active.session.goForward();
    }
  }

  public boolean canGoBack() {
    return active != null && active.back;
  }

  public boolean isDesktop() {
    return active != null
        && active.session.getSettings().getUserAgentMode()
            == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP;
  }

  public void setDesktop(boolean desktop) {
    if (active == null) return;
    var settings = active.session.getSettings();
    settings.setUserAgentMode(
        desktop
            ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
    settings.setViewportMode(
        desktop
            ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
    reload();
  }

  public void recover(String url) {
    if (active == null) {
      newTab(url);
      return;
    }
    int index = tabs.indexOf(active);
    Tab old = active;
    view.releaseSession();
    active = null;
    tabs.remove(old);
    if (observer != null) observer.accept(old.session, false);
    old.session.close();
    newTab(url);
    Tab replacement = active;
    tabs.remove(replacement);
    tabs.add(Math.min(index, tabs.size()), replacement);
  }

  public void close() {
    view.releaseSession();
    active = null;
    for (Tab tab : tabs) {
      if (observer != null) observer.accept(tab.session, false);
      tab.session.setNavigationDelegate(null);
      tab.session.setProgressDelegate(null);
      tab.session.setContentDelegate(null);
      tab.session.setHistoryDelegate(null);
      tab.detector.clearPrivateState();
      tab.session.close();
    }
    tabs.clear();
  }

  public void setAppForeground(boolean value) {
    appForeground = value;
    if (!value) {
      for (Tab tab : tabs) if (tab.playback != null) tab.playback.pause();
    }
  }

  public void setActive(boolean value) {
    visible = value;
    if (active != null) active.session.setActive(value);
  }

  public void save(java.util.function.Consumer<String> callback) {
    if (active != null) callback.accept(active.state);
  }

  public String snapshotTabs() {
    return snapshotTabs(false);
  }

  public String snapshotTabs(boolean reload) {
    try {
      JSONArray saved = new JSONArray();
      for (Tab tab : tabs) {
        saved.put(
            new JSONObject()
                .put("url", SafeSessionState.safeUrl(tab.url) ? tab.url : "about:blank")
                .put("title", tab.title.substring(0, Math.min(300, tab.title.length())))
                .put("session", reload ? JSONObject.NULL : tab.state)
                .put(
                    "desktop",
                    tab.session.getSettings().getUserAgentMode()
                        == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP));
      }
      return new JSONObject()
          .put("tabs", saved)
          .put("active", tabs.indexOf(active))
          .put("reload", reload)
          .toString();
    } catch (Exception ignored) {
      return null;
    }
  }

  public boolean restoreTabs(JSONObject snapshot) {
    if (snapshot == null) return false;
    JSONArray saved = snapshot.optJSONArray("tabs");
    if (saved == null || saved.length() == 0) return false;
    for (int i = 0; i < Math.min(5, saved.length()); i++) {
      JSONObject item = saved.optJSONObject(i);
      if (item == null) continue;
      if (i > 0) newTab(null);
      boolean desktop = item.optBoolean("desktop");
      active
          .session
          .getSettings()
          .setUserAgentMode(
              desktop
                  ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                  : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
      active
          .session
          .getSettings()
          .setViewportMode(
              desktop
                  ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                  : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
      if (snapshot.optBoolean("reload") || !restore(item.optString("session", null))) {
        String target = item.optString("url");
        if (SafeSessionState.safeUrl(target)) load(target);
      }
    }
    selectTab(Math.max(0, Math.min(tabs.size() - 1, snapshot.optInt("active", 0))));
    return true;
  }

  public boolean restore(String state) {
    String safe = SafeSessionState.sanitizeLegacy(state);
    if (safe == null || active == null) return false;
    try {
      JSONObject history = new JSONObject(safe).getJSONObject("history");
      String target =
          history
              .getJSONArray("entries")
              .getJSONObject(history.getInt("index") - 1)
              .getString("url");
      active.policy.beginUserNavigation(target);
      active.state = safe;
      active.session.restoreState(GeckoSession.SessionState.fromString(safe));
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private void emitNavigation() {
    if (listener != null)
      listener.onNavigationStateChanged(
          active != null && active.back, active != null && active.forward);
  }

  private void applyHistory(Tab tab, GeckoSession.HistoryDelegate.HistoryList history) {
    String safe = SafeSessionState.capture(history);
    if (safe != null && tab.url.equals(SafeSessionState.currentUrl(safe))) {
      if (tab.back && history.getCurrentIndex() == 0) return;
      if (tab.forward && history.getCurrentIndex() == history.size() - 1) return;
      tab.state = safe;
    }
  }
}
