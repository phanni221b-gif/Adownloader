package com.adownloader.app.download;

import java.util.*;
import org.json.*;

/** This representation must only be stored inside JobSecrets, since it contains signed URLs. */
public final class PlanCodec {
  public static JSONArray encode(List<ManifestParser.Track> tracks) throws JSONException {
    JSONArray result = new JSONArray();
    for (ManifestParser.Track track : tracks) {
      JSONObject row = new JSONObject().put("duration", track.duration).put("audio", track.audio);
      JSONArray parts = new JSONArray();
      for (ManifestParser.Part p : track.parts)
        parts.put(
            new JSONObject()
                .put("url", p.url)
                .put("offset", p.offset)
                .put("length", p.length)
                .put("sequence", p.sequence)
                .put("key", p.keyUrl)
                .put("iv", p.iv));
      result.put(row.put("parts", parts));
    }
    return result;
  }

  public static List<ManifestParser.Track> decode(JSONArray rows) throws JSONException {
    List<ManifestParser.Track> result = new ArrayList<>();
    for (int t = 0; t < rows.length(); t++) {
      JSONObject row = rows.getJSONObject(t);
      ManifestParser.Track track = new ManifestParser.Track();
      track.audio = row.getBoolean("audio");
      track.duration = row.getDouble("duration");
      JSONArray parts = row.getJSONArray("parts");
      for (int i = 0; i < parts.length(); i++) {
        JSONObject p = parts.getJSONObject(i);
        track.parts.add(
            new ManifestParser.Part(
                p.getString("url"),
                p.getLong("offset"),
                p.getLong("length"),
                p.getLong("sequence"),
                p.has("key") ? p.getString("key") : null,
                p.has("iv") ? p.getString("iv") : null));
      }
      result.add(track);
    }
    return result;
  }
}
