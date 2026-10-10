package com.dautolock.bridge;

import static org.junit.Assert.*;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 33})
public class BridgeUpdateTest {
  @Test
  public void versionOrderHandlesMultiDigitAndRejectsGarbage() {
    assertTrue(BridgeUpdate.order("0.3.2") > BridgeUpdate.order("0.3.1"));
    assertTrue(BridgeUpdate.order("0.3.10") > BridgeUpdate.order("0.3.9"));
    assertEquals(-1, BridgeUpdate.order("v0.3.2"));
    assertEquals(-1, BridgeUpdate.order(null));
  }

  @Test
  public void compatibleRequiresSamePackageHigherCodeAndSameSignature() {
    PackageInfo cur = new PackageInfo();
    cur.packageName = "com.dautolock.bridge";
    cur.versionCode = 6;
    cur.versionName = "0.3.2";
    cur.signatures = new Signature[] {new Signature("aa")};
    PackageInfo cand = new PackageInfo();
    cand.packageName = "com.dautolock.bridge";
    cand.versionCode = 7;
    cand.versionName = "0.3.3";
    cand.signatures = new Signature[] {new Signature("aa")};
    assertTrue(BridgeUpdate.compatible(cur, cand, "0.3.3"));
    cand.signatures = new Signature[] {new Signature("bb")}; // Different signer.
    assertFalse(BridgeUpdate.compatible(cur, cand, "0.3.3"));
    cand.signatures = cur.signatures;
    cand.versionCode = 6; // Not higher.
    assertFalse(BridgeUpdate.compatible(cur, cand, "0.3.3"));
    cand.versionCode = 7;
    cand.packageName = "com.dautolock.app"; // Wrong package (the phone APK).
    assertFalse(BridgeUpdate.compatible(cur, cand, "0.3.3"));
    assertFalse(BridgeUpdate.compatible(cur, null, "0.3.3"));
  }
}
