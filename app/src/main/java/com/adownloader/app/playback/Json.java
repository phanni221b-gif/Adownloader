package com.adownloader.app.playback;

import java.util.*;

/** Minimal JSON writer for the report (Map / List / String / Number / Boolean / null). */
public final class Json {
  private Json() {}

  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, 0);
    return out.append('\n').toString();
  }

  private static void write(StringBuilder out, Object v, int depth) {
    if (v == null) out.append("null");
    else if (v instanceof Boolean) out.append(v);
    else if (v instanceof Double d) out.append(Double.isFinite(d) ? trim(d) : "null");
    else if (v instanceof Float f) out.append(Float.isFinite(f) ? trim(f) : "null");
    else if (v instanceof Number) out.append(v);
    else if (v instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      int i = 0;
      for (var e : map.entrySet()) {
        indent(out, depth + 1);
        string(out, String.valueOf(e.getKey()));
        out.append(": ");
        write(out, e.getValue(), depth + 1);
        if (++i < map.size()) out.append(',');
        out.append('\n');
      }
      indent(out, depth);
      out.append('}');
    } else if (v instanceof Collection<?> list) {
      if (list.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      int i = 0;
      for (Object item : list) {
        indent(out, depth + 1);
        write(out, item, depth + 1);
        if (++i < list.size()) out.append(',');
        out.append('\n');
      }
      indent(out, depth);
      out.append(']');
    } else string(out, String.valueOf(v));
  }

  private static String trim(double d) {
    return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
  }

  private static void indent(StringBuilder out, int depth) {
    for (int i = 0; i < depth; i++) out.append("  ");
  }

  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }
}
