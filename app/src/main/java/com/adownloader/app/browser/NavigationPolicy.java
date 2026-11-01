package com.adownloader.app.browser;

import java.net.URI;

/** Trust belongs to one user-initiated document load, never to an elapsed-time window. */
public final class NavigationPolicy {
  private boolean trusted;
  private String expected;

  public void beginUserNavigation() {
    trusted = true;
    expected = null;
  }

  public void beginUserNavigation(String target) {
    trusted = true;
    expected = target;
  }

  public void committed() {
    trusted = false;
    expected = null;
  }

  public void committed(String target) {
    if (expected != null && !sameTarget(expected, target)) return;
    trusted = false;
    expected = null;
  }

  public void started(String target) {
    if (expected != null && sameTarget(expected, target)) {
      expected = null;
      trusted = true;
    }
  }

  public void stopped() {
    if (expected == null) trusted = false;
  }

  public void failed(String target) {
    if (expected == null
        || java.util.Objects.equals(expected, target)
        || sameTarget(expected, target)) committed();
  }

  public boolean allow(
      String current, String target, boolean gesture, boolean direct, boolean redirect) {
    if (gesture || direct) {
      trusted = true;
      if (!redirect) expected = target;
      return true;
    }
    if (redirect) return trusted || sameOrigin(current, target);
    if (expected != null && sameTarget(expected, target)) {
      trusted = true;
      return true;
    }
    // A script-initiated load cannot borrow trust from another pending navigation.
    if (current == null || sameOrigin(current, target)) {
      trusted = false;
      expected = null;
      return true;
    }
    return false;
  }

  private static boolean sameTarget(String a, String b) {
    try {
      URI first = URI.create(a), second = URI.create(b);
      return sameOrigin(a, b)
          && (first.getRawPath().isEmpty() ? "/" : first.getRawPath())
              .equals(second.getRawPath().isEmpty() ? "/" : second.getRawPath())
          && java.util.Objects.equals(first.getRawQuery(), second.getRawQuery())
          && java.util.Objects.equals(first.getRawUserInfo(), second.getRawUserInfo());
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean sameOrigin(String a, String b) {
    try {
      URI first = URI.create(a), second = URI.create(b);
      return first.getScheme().equalsIgnoreCase(second.getScheme())
          && first.getHost() != null
          && first.getHost().equalsIgnoreCase(second.getHost())
          && port(first) == port(second);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
