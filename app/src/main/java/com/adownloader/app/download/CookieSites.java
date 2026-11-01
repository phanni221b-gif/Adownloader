package com.adownloader.app.download;

import java.io.*;
import java.net.*;
import java.util.*;

/** Domain boundaries include ICANN and private suffixes. Vendored Mozilla PSL, MPL-2.0. */
public final class CookieSites {
  private static final Set<String> rules = new HashSet<>(), exceptions = new HashSet<>();

  static {
    try (InputStream stream = CookieSites.class.getResourceAsStream("/public_suffix_list.dat")) {
      if (stream == null) throw new IOException();
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
      String line;
      while ((line = in.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("//")) continue;
        if (line.startsWith("!")) exceptions.add(normal(line.substring(1)));
        else rules.add(line.startsWith("*.") ? "*." + normal(line.substring(2)) : normal(line));
      }
    } catch (IOException e) {
      throw new ExceptionInInitializerError("COOKIE_SITE_RULES_UNAVAILABLE");
    }
  }

  static String normal(String host) {
    try {
      return IDN.toASCII(host.toLowerCase(Locale.ROOT)).toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      return "";
    }
  }

  static int suffixLabels(String host) {
    String[] a = host.split("[.]");
    int n = 1;
    for (int i = 0; i < a.length; i++) {
      String tail = String.join(".", Arrays.copyOfRange(a, i, a.length));
      if (exceptions.contains(tail)) return a.length - i - 1;
      if (rules.contains(tail)) n = Math.max(n, a.length - i);
      if (i > 0 && rules.contains("*." + tail)) n = Math.max(n, a.length - i + 1);
    }
    return n;
  }

  static boolean publicSuffix(String host) {
    return host.split("[.]").length <= suffixLabels(host);
  }

  public static String site(URI uri) {
    String host = normal(uri.getHost() == null ? "" : uri.getHost());
    String[] a = host.split("[.]");
    int count = suffixLabels(host);
    return uri.getScheme()
        + "://"
        + String.join(".", Arrays.copyOfRange(a, Math.max(0, a.length - count - 1), a.length));
  }
}
