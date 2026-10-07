package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class UnlockPreflightCacheTest {
  private final long wall = 1_790_000_000_000L;

  private JSONObject parked() throws Exception {
    JSONObject data = new JSONObject().put("time", wall - 2000).put("speed", 0).put("powerGear", 1);
    for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"})
      data.put(side + "DoorLock", 2).put(side + "Door", 0);
    return data;
  }

  @Test
  public void onlySameVehicleAndSessionCanConsumeOnce() throws Exception {
    UnlockPreflightCache c = new UnlockPreflightCache();
    assertTrue(c.offer(c.revision(), 3, "car", new VehicleSnapshot(parked(), wall), 100, wall));
    assertFalse(c.available(4, "car", 100, wall));
    assertFalse(c.available(3, "other-car", 100, wall));
    assertNotNull(c.take(3, "car", 100, wall));
    assertNull(c.take(3, "car", 100, wall));
  }

  @Test
  public void oldMeasurementsCannotBeMadeFreshByReceivingThemNow() throws Exception {
    UnlockPreflightCache c = new UnlockPreflightCache();
    for (long timestamp : new long[] {wall - 8001, wall + 1, 0})
      assertFalse(
          c.offer(
              c.revision(),
              3,
              "car",
              new VehicleSnapshot(parked().put("time", timestamp), wall),
              100,
              wall));
  }

  @Test
  public void monotonicAndMeasurementExpiryAreBothEnforcedIncludingAtDispatch() throws Exception {
    UnlockPreflightCache c = new UnlockPreflightCache();
    c.offer(c.revision(), 3, "car", new VehicleSnapshot(parked(), wall), 100, wall);
    UnlockPreflightCache.Entry e = c.take(3, "car", 100, wall);
    assertTrue(e.usable(5100, wall + 5000));
    assertFalse(e.usable(5101, wall + 5000)); // Wall clock stalled/backwards, elapsed time wins.
    assertFalse(e.usable(99, wall));
    assertFalse(e.usable(100, wall - 1));
    c.offer(
        c.revision(),
        3,
        "car",
        new VehicleSnapshot(parked().put("time", wall - 7000), wall),
        100,
        wall);
    assertNull(c.take(3, "car", 1200, wall + 1100));
  }

  @Test
  public void invalidationRejectsLateInFlightResultsAndNewObservationsReplaceCache()
      throws Exception {
    UnlockPreflightCache c = new UnlockPreflightCache();
    long revision = c.revision();
    c.invalidate();
    assertFalse(c.offer(revision, 3, "car", new VehicleSnapshot(parked(), wall), 100, wall));
    assertTrue(c.offer(c.revision(), 3, "car", new VehicleSnapshot(parked(), wall), 100, wall));
    c.observed();
    assertNull(c.take(3, "car", 100, wall));
  }

  @Test
  public void movingPoweredOpenOrUnknownStatesAreNeverReusable() throws Exception {
    UnlockPreflightCache c = new UnlockPreflightCache();
    for (String field : new String[] {"speed", "powerGear", "leftFrontDoor", "leftFrontDoorLock"}) {
      Object value = field.equals("powerGear") ? 3 : 1;
      assertFalse(
          c.offer(
              c.revision(),
              3,
              "car",
              new VehicleSnapshot(parked().put(field, value), wall),
              100,
              wall));
      assertFalse(
          c.offer(
              c.revision(),
              3,
              "car",
              new VehicleSnapshot(parked().put(field, JSONObject.NULL), wall),
              100,
              wall));
    }
  }
}
