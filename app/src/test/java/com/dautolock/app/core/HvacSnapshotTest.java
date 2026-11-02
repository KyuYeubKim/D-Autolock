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

  @Test
  public void climateScaleOffsetComesFromTheCarsRangeLikePyByd() throws Exception {
    HvacSnapshot usual =
        new HvacSnapshot(
            new JSONObject()
                .put("vdeTempRangeMin", 15)
                .put("vdeTempRangeMax", 31)
                .put("mainSettingTemp", 9)
                .put("mainSettingTempNew", 23.0),
            now);
    assertEquals(14.0, usual.tempOffset(), 0);
    assertEquals(23.0, usual.setTemp, 0);
    assertTrue(usual.diagnostic(now).contains("setTemp=23.0"));
    HvacSnapshot han =
        new HvacSnapshot(
            new JSONObject().put("vdeTempRangeMin", "17").put("vdeTempRangeMax", "33"), now);
    assertEquals(16.0, han.tempOffset(), 0);
    assertNull(new HvacSnapshot(new JSONObject(), now).tempOffset());
    assertNull( // A range wider than the 1–17 scale cannot be mapped.
        new HvacSnapshot(
                new JSONObject().put("vdeTempRangeMin", 10).put("vdeTempRangeMax", 40), now)
            .tempOffset());
    assertNull(new HvacSnapshot(new JSONObject().put("mainSettingTempNew", -129), now).setTemp);
  }

  @Test
  public void fieldsNestedUnderStatusNowAreRead() throws Exception {
    HvacSnapshot s =
        new HvacSnapshot(
            new JSONObject()
                .put(
                    "statusNow",
                    new JSONObject()
                        .put("status", 1)
                        .put("acSwitch", 1)
                        .put("vdeTempRangeMin", 15)
                        .put("vdeTempRangeMax", 31)
                        .put("mainSettingTempNew", 23.0)
                        .put("time", now - 1000)),
            now);
    assertEquals(Integer.valueOf(1), s.status);
    assertEquals(14.0, s.tempOffset(), 0);
    assertEquals(23.0, s.setTemp, 0);
    assertTrue(s.label(now).contains("ON"));
  }
}
