package com.dautolock.app.core;

import static com.dautolock.app.core.ProximityEngine.Action.*;
import static org.junit.Assert.*;

import com.dautolock.app.core.BeaconCombine.Decision;
import org.junit.Test;

public class BeaconCombineTest {
  @Test
  public void vehicleBleOpensAsBefore() {
    assertEquals(
        Decision.UNLOCK_PRIMARY, BeaconCombine.decide(UNLOCK, NONE, false, false, false, 1000, -1, -1));
  }

  @Test
  public void beaconCanOpenWhenVehicleBleIsNotNear() {
    assertEquals(
        Decision.UNLOCK_BEACON, BeaconCombine.decide(NONE, UNLOCK, true, true, false, 1000, -1, -1));
  }

  @Test
  public void staleBeaconNeverDrivesAnUnlock() {
    assertEquals(Decision.NONE, BeaconCombine.decide(NONE, UNLOCK, false, false, false, 1000, -1, -1));
  }

  @Test
  public void beaconUnlockIsMutedBrieflyAfterALock() {
    long lock = 10000;
    assertEquals(
        Decision.NONE,
        BeaconCombine.decide(NONE, UNLOCK, true, true, false, lock + 1000, lock, -1)); // Cooldown.
    assertEquals(
        Decision.UNLOCK_BEACON,
        BeaconCombine.decide(
            NONE, UNLOCK, true, true, false, lock + BeaconCombine.UNLOCK_COOLDOWN_MS, lock, -1));
  }

  @Test
  public void locksWhenBothAreClear() {
    assertEquals(Decision.LOCK, BeaconCombine.decide(LOCK, LOCK, true, false, false, 1000, -1, -1));
  }

  @Test
  public void deadBeaconDoesNotBlockTheLock() {
    // Security: a not-receiving beacon must never keep the car unlocked.
    assertEquals(Decision.LOCK, BeaconCombine.decide(LOCK, NONE, false, false, false, 1000, -1, -1));
  }

  @Test
  public void holdsTheLockWhileTheBeaconStillSeesThePhone() {
    assertEquals(Decision.HOLD, BeaconCombine.decide(LOCK, NONE, true, true, false, 1000, -1, 500));
  }

  @Test
  public void aHeldLockProceedsAfterTheMaxHold() {
    long held = 1000;
    assertEquals(
        Decision.HOLD, BeaconCombine.decide(LOCK, NONE, true, true, false, held + 1000, -1, held));
    assertEquals(
        Decision.LOCK,
        BeaconCombine.decide(
            LOCK, NONE, true, true, false, held + BeaconCombine.MAX_LOCK_HOLD_MS, -1, held));
  }

  @Test
  public void nothingPendingIsNone() {
    assertEquals(Decision.NONE, BeaconCombine.decide(NONE, NONE, true, false, false, 1000, -1, -1));
  }

  @Test
  public void openTakesPriorityOverADanglingHold() {
    // Primary wants to open: never hold/lock in that case.
    assertEquals(
        Decision.UNLOCK_PRIMARY,
        BeaconCombine.decide(UNLOCK, NONE, true, true, false, 1000, -1, 500));
  }

  @Test
  public void beaconFarAloneLocksWhenTheVehicleBleIsNotNear() {
    // OR to lock: walked away on the side where the car's own BLE was never strong.
    assertEquals(
        Decision.LOCK_BEACON, BeaconCombine.decide(NONE, LOCK, true, false, false, 1000, -1, -1));
  }

  @Test
  public void beaconLockWaitsWithoutLimitWhileTheVehicleBleIsNear() {
    // Standing at the driver's door with the trunk beacon reading far: do not lock, ever.
    assertEquals(Decision.NONE, BeaconCombine.decide(NONE, LOCK, true, false, true, 1000, -1, -1));
    assertEquals(
        Decision.NONE,
        BeaconCombine.decide(
            NONE, LOCK, true, false, true, 10 * BeaconCombine.MAX_LOCK_HOLD_MS, -1, 0));
  }

  @Test
  public void aLostBeaconNeverLocksOnItsOwn() {
    // A flat beacon battery is not a departure.
    assertEquals(Decision.NONE, BeaconCombine.decide(NONE, LOCK, false, false, false, 1000, -1, -1));
  }

  @Test
  public void eitherNearOpensSoTheyNeverFightOverTheDoor() {
    // Vehicle BLE near and wanting to open beats a far beacon.
    assertEquals(
        Decision.UNLOCK_PRIMARY, BeaconCombine.decide(UNLOCK, LOCK, true, false, true, 1000, -1, -1));
    // Beacon near and opening beats a far vehicle BLE (outside the post-lock mute).
    assertEquals(
        Decision.UNLOCK_BEACON, BeaconCombine.decide(LOCK, UNLOCK, true, true, false, 1000, -1, -1));
  }
}
