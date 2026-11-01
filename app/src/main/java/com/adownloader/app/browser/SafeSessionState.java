package com.adownloader.app.browser;

import java.net.URI;
import org.json.*;
import org.mozilla.geckoview.GeckoSession;

/** Reads public history items only. Never serialize Gecko's formdata/storage/state payloads. */
public final class SafeSessionState {
  private SafeSessionState() {}

  public static String capture(GeckoSession.HistoryDelegate.HistoryList state) {
    try {
      JSONArray entries = new JSONArray();
      int current = state.getCurrentIndex(), selected = 0;
      int start = Math.max(0, current - 80), end = Math.min(state.size(), start + 100);
      for (int i = start; i < end; i++) {
        var item = state.get(i);
        if (!safeUrl(item.getUri())) continue;
        entries.put(
            new JSONObject().put("url", item.getUri()).put("title", title(item.getTitle())));
        if (i <= current) selected = entries.length();
      }
      return history(entries, selected);
    } catch (Exception ignored) {
      return null;
    }
  }

  /** Immediate minimal fallback while Gecko's periodic session-store update is pending. */
  public static String visit(String value, String url, String name) {
    if (!safeUrl(url)) return value;
    try {
      JSONObject old =
          value == null ? new JSONObject() : new JSONObject(value).optJSONObject("history");
      JSONArray entries = old == null ? null : old.optJSONArray("entries"), next = new JSONArray();
      int selected = old == null ? 0 : old.optInt("index", 1);
      if (entries != null
          && selected > 0
          && selected <= entries.length()
          && url.equals(entries.getJSONObject(selected - 1).optString("url"))) {
        entries.getJSONObject(selected - 1).put("title", title(name));
        return history(entries, selected);
      }
      if (entries != null)
        for (int i = Math.max(0, selected - 99); i < Math.min(selected, entries.length()); i++)
          next.put(entries.getJSONObject(i));
      next.put(new JSONObject().put("url", url).put("title", title(name)));
      return history(next, next.length());
    } catch (Exception ignored) {
      return value;
    }
  }

  public static String shift(String value, int delta) {
    try {
      JSONObject root = new JSONObject(value), history = root.getJSONObject("history");
      history.put(
          "index",
          Math.max(
              1,
              Math.min(history.getJSONArray("entries").length(), history.getInt("index") + delta)));
      return root.toString();
    } catch (Exception ignored) {
      return value;
    }
  }

  public static String currentUrl(String value) {
    try {
      JSONObject history = new JSONObject(value).getJSONObject("history");
      return history
          .getJSONArray("entries")
          .getJSONObject(history.getInt("index") - 1)
          .getString("url");
    } catch (Exception ignored) {
      return null;
    }
  }

  public static String sanitizeLegacy(String value) {
    try {
      JSONObject old = new JSONObject(value).getJSONObject("history");
      JSONArray source = old.getJSONArray("entries"), entries = new JSONArray();
      int current = old.optInt("index", 1), selected = 0;
      int start = Math.max(0, current - 81), end = Math.min(source.length(), start + 100);
      for (int i = start; i < end; i++) {
        JSONObject item = source.getJSONObject(i);
        String url = item.optString("url");
        if (!safeUrl(url)) continue;
        entries.put(new JSONObject().put("url", url).put("title", title(item.optString("title"))));
        if (i < current) selected = entries.length();
      }
      return history(entries, selected);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String history(JSONArray entries, int selected) throws JSONException {
    if (entries.length() == 0) return null;
    return new JSONObject()
        .put(
            "history", new JSONObject().put("entries", entries).put("index", Math.max(1, selected)))
        .toString();
  }

  public static boolean safeUrl(String value) {
    try {
      if (value == null || value.length() > 16384) return false;
      URI uri = URI.create(value);
      return uri.getUserInfo() == null
          && uri.getHost() != null
          && ("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()));
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static String title(String value) {
    if (value == null) return "";
    return value.substring(0, Math.min(300, value.length())).replaceAll("[\\p{Cntrl}]", "");
  }
}
