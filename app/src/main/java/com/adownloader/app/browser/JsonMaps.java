package com.adownloader.app.browser;

import java.util.*;
import org.json.*;

/** Converts extension messages to plain maps for the platform-independent diagnostics code. */
final class JsonMaps {
  private JsonMaps() {}

  static Map<String, Object> toMap(JSONObject json) {
    return toMap(json, 0);
  }

  private static Map<String, Object> toMap(JSONObject json, int depth) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (json == null || depth > 6) return out;
    Iterator<String> keys = json.keys();
    while (keys.hasNext() && out.size() < 4096) {
      String key = keys.next();
      out.put(key, value(json.opt(key), depth + 1));
    }
    return out;
  }

  private static Object value(Object v, int depth) {
    if (v instanceof JSONObject o) return toMap(o, depth);
    if (v instanceof JSONArray a) {
      List<Object> list = new ArrayList<>();
      for (int i = 0; i < a.length() && i < 4096 && depth <= 6; i++) list.add(value(a.opt(i), depth + 1));
      return list;
    }
    return v == JSONObject.NULL ? null : v;
  }

  static JSONObject toJson(Map<String, Object> map) {
    JSONObject out = new JSONObject();
    for (var e : map.entrySet())
      try {
        out.put(e.getKey(), e.getValue());
      } catch (JSONException ignored) {
      }
    return out;
  }
}
