package com.adownloader.app.browser;

import static org.junit.Assert.*;

import org.junit.Test;

public class NavigationPolicyTest {
  @Test
  public void redirectsFollowUserChainUntilCommit() {
    NavigationPolicy policy = new NavigationPolicy();
    policy.beginUserNavigation();
    assertTrue(policy.allow("https://a.test", "https://auth.test", false, false, true));
    assertTrue(policy.allow("https://auth.test", "https://callback.test", false, false, true));
    policy.committed();
    assertFalse(policy.allow("https://callback.test", "https://ads.test", false, false, false));
    assertFalse(policy.allow("https://callback.test", "https://ads.test", false, false, true));
  }

  @Test
  public void scriptNavigationCannotBorrowPendingTrust() {
    NavigationPolicy policy = new NavigationPolicy();
    policy.beginUserNavigation();
    assertTrue(policy.allow("https://a.test", "https://a.test/redirect", false, false, false));
    assertFalse(policy.allow("https://a.test", "https://ads.test", false, false, true));
  }

  @Test
  public void originIncludesPortAndScheme() {
    NavigationPolicy policy = new NavigationPolicy();
    assertFalse(policy.allow("https://a.test", "http://a.test", false, false, false));
    assertFalse(policy.allow("https://a.test", "https://a.test:444", false, false, false));
    assertTrue(policy.allow("https://a.test", "https://a.test:443/path", false, false, false));
    assertTrue(policy.allow("https://a.test", "https://b.test", true, false, false));
    policy.stopped();
    assertFalse(policy.allow("https://a.test", "https://b.test", false, false, true));
  }
}
