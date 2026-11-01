package com.adownloader.app.browser;

import java.net.URI;

/** Trust belongs to one user-initiated document load, never to an elapsed-time window. */
public final class NavigationPolicy {
  private boolean trusted;

  public void beginUserNavigation() {
    trusted = true;
  }

  public void committed() {
    trusted = false;
  }

  public void stopped() {
    trusted = false;
  }

  public boolean allow(
      String current, String target, boolean gesture, boolean direct, boolean redirect) {
    if (gesture || direct) {
      trusted = true;
      return true;
    }
    if (redirect) return trusted || sameOrigin(current, target);
    // A script-initiated load cannot borrow trust from another pending navigation.
    if (current == null || sameOrigin(current, target)) {
      trusted = false;
      return true;
    }
    return false;
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
