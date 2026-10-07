package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class VehicleSnapshotTest {
  @Test
  public void partialLockPayloadDoesNotClaimUnlock() throws Exception {
    JSONObject j = new JSONObject().put("leftFrontDoorLock", 1);
    assertNull(new VehicleSnapshot(j, 1800000000000L).locked);
  }

  private final long now = 1800000000000L;

  private JSONObject parked() throws Exception {
    JSONObject j =
        new JSONObject().put("time", now).put("speed", 0).put("powerGear", 1).put("epb", 1);
    for (String p : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"}) {
      j.put(p + "Door", 0);
      j.put(p + "DoorLock", 2);
    }
    return j;
  }

  @Test
  public void completeParkedStatusAllowsDoors() throws Exception {
    assertNull(new VehicleSnapshot(parked(), now).automaticBlock(true, now));
  }

  @Test
  public void onVehicleCanLockOnlyWhenParkBrakeIsSet() throws Exception {
    assertNull(new VehicleSnapshot(parked().put("powerGear", 3), now).automaticBlock(true, now));
    assertNotNull(
        new VehicleSnapshot(parked().put("powerGear", 3).put("epb", 0), now)
            .automaticBlock(true, now));
  }

  @Test
  public void windowReadbackDoesNotGuessMissingFields() throws Exception {
    JSONObject d = parked();
    assertNull(new VehicleSnapshot(d, now).windowsClosed);
    for (String k :
        new String[] {"leftFrontWindow", "rightFrontWindow", "leftRearWindow", "rightRearWindow"})
      d.put(k, 1);
    assertTrue(new VehicleSnapshot(d, now).windowsClosed);
    d.put("leftFrontWindow", 0);
    assertNull(new VehicleSnapshot(d, now).windowsClosed);
  }

  @Test
  public void missingSpeedDoesNotBecomeZero() throws Exception {
    JSONObject j = parked();
    j.remove("speed");
    VehicleSnapshot s = new VehicleSnapshot(j, now);
    assertNull(s.speed);
    assertNotNull(s.automaticBlock(false, now));
    assertNotNull(s.manualBlock(true, now));
  }

  @Test
  public void onIsNotAutomaticDoorPermission() throws Exception {
    assertNotNull(
        new VehicleSnapshot(parked().put("powerGear", 3), now).automaticBlock(false, now));
  }

  @Test
  public void movingVehicleBlocksEveryManualCommand() throws Exception {
    assertNotNull(new VehicleSnapshot(parked().put("speed", 1), now).manualBlock(false, now));
  }

  @Test
  public void openOrUnknownDoorBlocksLock() throws Exception {
    assertNotNull(
        new VehicleSnapshot(parked().put("leftFrontDoor", 1), now).automaticBlock(true, now));
    JSONObject j = parked();
    j.remove("leftFrontDoor");
    assertNotNull(new VehicleSnapshot(j, now).automaticBlock(true, now));
    assertNotNull(
        new VehicleSnapshot(parked().put("leftFrontDoor", 2), now).automaticBlock(true, now));
  }

  @Test
  public void staleAndFutureTimeFailClosed() throws Exception {
    assertFalse(new VehicleSnapshot(parked().put("time", now - 31000), now).fresh(now));
    assertFalse(new VehicleSnapshot(parked().put("time", now + 6000), now).fresh(now));
    JSONObject j = parked();
    j.remove("time");
    assertFalse(new VehicleSnapshot(j, now).fresh(now));
  }

  @Test
  public void stopRequiresParkingBrake() throws Exception {
    assertNotNull(new VehicleSnapshot(parked().put("epb", 0), now).manualBlock(true, now));
  }

  @Test
  public void invalidAndBooleanSpeedFailClosed() throws Exception {
    assertNull(new VehicleSnapshot(parked().put("speed", false), now).speed);
    assertNull(new VehicleSnapshot(parked().put("speed", "NaN"), now).speed);
    assertNull(new VehicleSnapshot(parked().put("speed", -1), now).speed);
  }

  @Test
  public void secondsAndMillisAreUnderstood() {
    assertEquals(now, VehicleSnapshot.timestamp(now / 1000));
    assertEquals(now, VehicleSnapshot.timestamp(now));
  }

  @Test
  public void emptyPayloadIsUnknown() {
    VehicleSnapshot s = new VehicleSnapshot(new JSONObject(), now);
    assertNull(s.locked);
    assertNull(s.power);
    assertNull(s.doorsClosed);
    assertNotNull(s.automaticBlock(false, now));
  }
}
