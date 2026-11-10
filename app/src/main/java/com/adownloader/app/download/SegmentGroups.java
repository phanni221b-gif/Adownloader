package com.adownloader.app.download;

import java.util.*;

/**
 * Splits one track's ordered parts at #EXT-X-DISCONTINUITY boundaries. Each group starts with the
 * track's initialization part (fMP4) when one exists, so every group is independently parseable.
 */
public final class SegmentGroups {
  private SegmentGroups() {}

  public static <T> List<List<T>> split(List<ManifestParser.Part> plan, List<T> items) {
    if (plan.size() != items.size()) throw new IllegalArgumentException("Plan/item mismatch");
    List<List<T>> groups = new ArrayList<>();
    T initialization = null;
    int current = Integer.MIN_VALUE;
    for (int i = 0; i < plan.size(); i++) {
      ManifestParser.Part part = plan.get(i);
      T item = items.get(i);
      if (part.initialization) {
        initialization = item;
        continue;
      }
      if (groups.isEmpty() || part.discontinuity != current) {
        current = part.discontinuity;
        List<T> group = new ArrayList<>();
        if (initialization != null) group.add(initialization);
        groups.add(group);
      }
      groups.get(groups.size() - 1).add(item);
    }
    if (groups.isEmpty() && initialization != null) groups.add(new ArrayList<>(List.of(initialization)));
    return groups;
  }
}
