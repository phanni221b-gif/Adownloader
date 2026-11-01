package com.adownloader.app.browser;

import static org.junit.Assert.*;

import org.junit.Test;

public class NavigationPolicyTest {
  @Test
  public void initialSyntheticBlankDoesNotCommitTheRequestedDocument() {
    NavigationPolicy policy = new NavigationPolicy();
    policy.beginUserNavigation("https://a.test");
    assertTrue(policy.allow(null, "https://a.test", false, true, false));
    policy.started("about:blank");
    policy.committed("about:blank");
    policy.stopped();
    policy.started("https://a.test/");
    assertTrue(policy.allow(null, "https://auth.test", false, false, true));
    assertTrue(policy.allow(null, "https://callback.test", false, false, true));
    policy.committed("https://callback.test");
    assertFalse(policy.allow("https://callback.test", "https://ads.test", false, false, false));
  }

  @Test
  public void explicitAddressNavigationDoesNotRequireGeckoGesture() {
    NavigationPolicy policy = new NavigationPolicy();
    policy.beginUserNavigation("https://a.test/watch?token=one");
    assertFalse(policy.allow("https://old.test", "https://ads.test", false, false, false));
    assertTrue(policy.allow(null, "https://a.test/watch?token=one", false, false, false));
    assertTrue(policy.allow(null, "https://auth.test", false, false, true));
    assertTrue(policy.allow(null, "https://callback.test", false, false, true));
    policy.committed();
    assertFalse(policy.allow("https://callback.test", "https://ads.test", false, false, false));
  }

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
    policy.started("https://b.test");
    policy.stopped();
    assertFalse(policy.allow("https://a.test", "https://b.test", false, false, true));
  }
}
