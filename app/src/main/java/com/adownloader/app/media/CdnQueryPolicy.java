package com.adownloader.app.media;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Identity/auth evidence only. Never returns a replacement network URL. */
public final class CdnQueryPolicy {
  private CdnQueryPolicy() {}

  private static final Set<String> AWS =
      Set.of(
          "x-amz-algorithm",
          "x-amz-credential",
          "x-amz-date",
          "x-amz-expires",
          "x-amz-signedheaders",
          "x-amz-signature",
          "x-amz-security-token");
  private static final Set<String> ORIGINAL =
      Set.of("token", "signature", "expires", "timestamp", "_");

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (Exception invalid) {
      return value;
    }
  }

  private static Map<String, String> values(URI uri) {
    Map<String, String> out = new HashMap<>();
    if (uri.getRawQuery() != null)
      for (String part : uri.getRawQuery().split("&")) {
        String[] kv = part.split("=", 2);
        String key = decode(kv[0]).toLowerCase(Locale.ROOT);
        if (out.putIfAbsent(key, kv.length == 2 ? decode(kv[1]) : "") != null)
          return Collections.emptyMap();
      }
    return out;
  }

  private static boolean aws(Map<String, String> values) {
    return "AWS4-HMAC-SHA256".equals(values.get("x-amz-algorithm"))
        && values.containsKey("x-amz-signature");
  }

  private static boolean cloudFront(Map<String, String> values) {
    return values.containsKey("signature")
        && values.containsKey("key-pair-id")
        && (values.containsKey("expires") || values.containsKey("policy"));
  }

  private static boolean paired(Map<String, String> values) {
    return (values.containsKey("sig")
            || values.containsKey("signature")
            || values.containsKey("hmac"))
        && List.of("e", "exp", "expires").stream()
            .anyMatch(k -> epoch(values.get(k)) != Long.MAX_VALUE);
  }

  private static long epoch(String text) {
    try {
      long value = Long.parseLong(text);
      return value >= 1500000000L && value <= 4102444800L
          ? Math.multiplyExact(value, 1000)
          : Long.MAX_VALUE;
    } catch (Exception invalid) {
      return Long.MAX_VALUE;
    }
  }

  private static String structuredToken(String value) {
    if (value == null) return null;
    // Preserve ACL/resource/quality and all unknown fields; remove only explicit Akamai auth
    // fields.
    if (value.contains("~hmac=") && value.matches(".*(?:^|~)exp=[0-9]{10}(?:~.*)?")) {
      List<String> keep = new ArrayList<>();
      for (String part : value.split("~")) {
        String key = part.split("=", 2)[0];
        if (!Set.of("hmac", "exp", "st").contains(key)) keep.add(part);
      }
      return String.join("~", keep);
    }
    // A JWT payload is NOT discarded: changed video/representation claims stay distinct.
    if (value.matches("[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+"))
      return value.substring(0, value.lastIndexOf('.'));
    return null;
  }

  private static boolean policyMatches(String value, URI uri) {
    if (value == null) return false;
    try {
      String json =
          new String(
              Base64.getDecoder()
                  .decode(value.replace('-', '+').replace('_', '=').replace('~', '/')),
              StandardCharsets.UTF_8);
      Matcher resources =
          Pattern.compile("\\\"Resource\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
      if (!resources.find()) return false;
      String resource = resources.group(1).replace("\\/", "/");
      if (resources.find()) return false;
      String exact = MediaIdentity.origin(uri.toString()) + uri.getRawPath();
      return resource.equals(exact) || resource.equals(exact + "*");
    } catch (Exception invalid) {
      return false;
    }
  }

  public static String identityQuery(URI uri, boolean stableResource) {
    String query = uri.getRawQuery();
    if (query == null) return "";
    Map<String, String> v = values(uri);
    if (v.isEmpty()) return query;
    boolean aws = aws(v), cf = cloudFront(v), pair = paired(v);
    if (!stableResource && !aws) return query;
    List<String> keep = new ArrayList<>();
    for (String part : query.split("&")) {
      String[] kv = part.split("=", 2);
      String key = decode(kv[0]).toLowerCase(Locale.ROOT),
          value = kv.length == 2 ? decode(kv[1]) : "";
      if (Set.of("token", "hdntl", "hdnts", "auth", "jwt", "auth_token", "access_token")
          .contains(key)) {
        String stable = structuredToken(value);
        if (stable != null) {
          keep.add(key + "=" + encode(stable));
          continue;
        }
      }
      if (ORIGINAL.contains(key)
          || aws && AWS.contains(key)
          || cf && key.equals("key-pair-id")
          || cf && key.equals("policy") && policyMatches(value, uri)
          || pair && Set.of("sig", "hmac", "e", "exp").contains(key)) continue;
      keep.add(part);
    }
    return String.join("&", keep);
  }

  public static boolean signed(String url) {
    try {
      Map<String, String> v = values(URI.create(url));
      return aws(v)
          || cloudFront(v)
          || paired(v)
          || v.containsKey("token")
          || v.containsKey("signature")
          || List.of("hdntl", "hdnts", "auth", "jwt", "auth_token", "access_token").stream()
              .anyMatch(k -> structuredToken(v.get(k)) != null);
    } catch (Exception invalid) {
      return false;
    }
  }

  public static long expiresAt(String url) {
    try {
      Map<String, String> v = values(URI.create(url));
      long end = Long.MAX_VALUE;
      if (aws(v) && v.containsKey("x-amz-date")) {
        long start =
            LocalDateTime.parse(
                    v.get("x-amz-date"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
        long ttl = Long.parseLong(v.getOrDefault("x-amz-expires", "0"));
        if (ttl > 0 && ttl <= 604800) end = Math.addExact(start, Math.multiplyExact(ttl, 1000));
      }
      if (signed(url))
        for (String key : List.of("expires", "exp", "e")) end = Math.min(end, epoch(v.get(key)));
      for (String key : List.of("hdntl", "hdnts", "auth")) {
        String value = v.get(key);
        if (structuredToken(value) != null && value.contains("~hmac=")) {
          Matcher exp = Pattern.compile("(?:^|~)exp=([0-9]+)(?:~|$)").matcher(value);
          if (exp.find()) end = Math.min(end, epoch(exp.group(1)));
        }
      }
      return end;
    } catch (Exception invalid) {
      return Long.MAX_VALUE;
    }
  }
}
