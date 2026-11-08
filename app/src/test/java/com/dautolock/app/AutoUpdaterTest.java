package com.dautolock.app;

import static org.junit.Assert.*;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The in-app updater's signature/version gate. These exercise the v1 (signatures) fallback path —
 * the one that fires on devices where getPackageArchiveInfo does not populate signingInfo for the
 * downloaded archive — which was reporting a false "서명 불일치" for our (then v2-only) APKs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AutoUpdaterTest {

  private static final Signature KEY_A = new Signature(new byte[] {1, 2, 3, 4});
  private static final Signature KEY_A_COPY = new Signature(new byte[] {1, 2, 3, 4});
  private static final Signature KEY_B = new Signature(new byte[] {9, 8, 7, 6});

  private PackageInfo pkg(String name, String version, int code, Signature... sigs) {
    PackageInfo pi = new PackageInfo();
    pi.packageName = name;
    pi.versionName = version;
    pi.versionCode = code;
    pi.signatures = sigs.length == 0 ? null : sigs; // v1 field; signingInfo left null = archive path
    return pi;
  }

  @Test
  public void sameKeyHigherVersionIsCompatibleViaV1Fallback() {
    PackageInfo current = pkg("com.dautolock.app", "0.3.56", 68, KEY_A);
    PackageInfo candidate = pkg("com.dautolock.app", "0.3.57", 69, KEY_A_COPY);
    assertTrue(AutoUpdater.compatible(current, candidate, "0.3.57"));
  }

  @Test
  public void differentKeyIsRejected() {
    PackageInfo current = pkg("com.dautolock.app", "0.3.56", 68, KEY_A);
    PackageInfo candidate = pkg("com.dautolock.app", "0.3.57", 69, KEY_B);
    assertFalse(AutoUpdater.compatible(current, candidate, "0.3.57"));
  }

  @Test
  public void sameOrLowerVersionCodeIsRejected() {
    PackageInfo current = pkg("com.dautolock.app", "0.3.57", 69, KEY_A);
    PackageInfo candidate = pkg("com.dautolock.app", "0.3.57", 69, KEY_A_COPY);
    assertFalse(AutoUpdater.compatible(current, candidate, "0.3.57"));
  }

  @Test
  public void mismatchedVersionNameIsRejected() {
    PackageInfo current = pkg("com.dautolock.app", "0.3.56", 68, KEY_A);
    PackageInfo candidate = pkg("com.dautolock.app", "0.3.57", 69, KEY_A_COPY);
    assertFalse(AutoUpdater.compatible(current, candidate, "v0.3.57"));
  }

  @Test
  public void missingInstalledSignatureIsRejected() {
    PackageInfo current = pkg("com.dautolock.app", "0.3.56", 68); // no readable signer
    PackageInfo candidate = pkg("com.dautolock.app", "0.3.57", 69, KEY_A);
    assertFalse(AutoUpdater.compatible(current, candidate, "0.3.57"));
  }

  @Test
  public void nullCandidateIsRejected() {
    PackageInfo current = pkg("com.dautolock.app", "0.3.56", 68, KEY_A);
    assertFalse(AutoUpdater.compatible(current, null, "0.3.57"));
  }
}
