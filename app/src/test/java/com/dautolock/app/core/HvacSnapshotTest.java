package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class HvacSnapshotTest {
  private final long now = 1800000000000L;

  @Test
  public void serverOnWithoutTimestampIsExplicitlyUnverifiedInTime() throws Exception {
    HvacSnapshot s = new HvacSnapshot(new JSONObject().put("status", 1), now);
    assertEquals("공조 서버 응답 ON · 측정 시각 미제공", s.label(now));
    assertTrue(s.diagnostic(now).contains("measuredAt=0"));
  }

  @Test
  public void staleOffAndUnknownStatusCannotClaimCurrentOn() throws Exception {
    HvacSnapshot s =
        new HvacSnapshot(new JSONObject().put("status", 2).put("time", now - 60000), now);
    assertTrue(s.label(now).contains("OFF"));
    assertTrue(s.label(now).contains("오래된"));
    for (Object value : new Object[] {true, "--", -1, 3, JSONObject.NULL})
      assertTrue(
          new HvacSnapshot(new JSONObject().put("status", value).put("acSwitch", 1), now)
              .label(now)
              .contains("미확인"));
  }

  @Test
  public void diagnosticsNeverIncludeOtherServerFields() throws Exception {
    HvacSnapshot s =
        new HvacSnapshot(
            new JSONObject()
                .put("status", 1)
                .put("time", now)
                .put("vin", "private-vin")
                .put("userId", "private-user")
                .put("token", "private-token"),
            now);
    assertTrue(s.label(now).contains("최근 측정"));
    assertFalse(s.diagnostic(now).contains("private"));
  }
}
