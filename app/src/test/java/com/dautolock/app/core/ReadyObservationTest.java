package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ReadyObservationTest {
  private final long sent = 1800000000000L;

  private VehicleSnapshot sample(long at, int power, Object ok) throws Exception {
    return new VehicleSnapshot(
        new JSONObject().put("time", at).put("powerGear", power).put("okLight", ok), at);
  }

  @Test
  public void powerOnNeverSubstitutesForMissingReady() throws Exception {
    ReadyObservation watch = new ReadyObservation(sent);
    watch.accept(sample(sent + 1000, 3, JSONObject.NULL), sent + 1000);
    assertTrue(watch.summary().contains("READY 유지 미확인"));
    assertTrue(watch.diagnostic().contains("okOnCount=0"));
  }

  @Test
  public void distinguishesOnObservationsAndSubsequentOffWithoutClaimingContinuity()
      throws Exception {
    ReadyObservation watch = new ReadyObservation(sent);
    watch.accept(sample(sent + 1000, 3, 1), sent + 1000);
    watch.accept(sample(sent + 15000, 3, 1), sent + 15000);
    assertTrue(watch.summary().contains("관측 2회"));
    watch.accept(sample(sent + 30000, 1, 0), sent + 30000);
    assertTrue(watch.summary().contains("1 → 0"));
    assertTrue(watch.diagnostic().contains("powerOffCount=1"));
    assertTrue(watch.diagnostic().contains("continuousReadyVerified=false"));
  }

  @Test
  public void ignoresStalePreCommandDuplicateAndConflictingValues() throws Exception {
    ReadyObservation watch = new ReadyObservation(sent);
    watch.accept(sample(sent - 1000, 3, 1), sent);
    watch.accept(sample(sent + 1000, 3, 1), sent + 40000);
    watch.accept(sample(sent + 50000, 1, 1), sent + 50000);
    watch.accept(sample(sent + 60000, 3, 1), sent + 60000);
    watch.accept(sample(sent + 60000, 3, 0), sent + 60000);
    assertTrue(watch.diagnostic().contains("okOnCount=1"));
    assertTrue(watch.diagnostic().contains("okDropped=false"));
    assertTrue(watch.diagnostic().contains("unknownCount=4"));
  }

  @Test
  public void malformedOrUnrecognizedReadyDoesNotMeanOn() throws Exception {
    for (Object value : new Object[] {true, "--", -1, 2, JSONObject.NULL}) {
      ReadyObservation watch = new ReadyObservation(sent);
      VehicleSnapshot s = sample(sent + 1000, 3, value);
      watch.accept(s, sent + 1000);
      assertTrue(watch.summary().contains("READY 유지 미확인"));
      assertTrue(s.readyLabel(sent + 1000).contains("미확인"));
    }
  }
}
