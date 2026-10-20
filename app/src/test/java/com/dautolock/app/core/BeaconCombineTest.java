package com.dautolock.app.core;

import static com.dautolock.app.core.ProximityEngine.Action.*;
import static org.junit.Assert.*;

import com.dautolock.app.core.BeaconCombine.Decision;
import org.junit.Test;

public class BeaconCombineTest {
  @Test
  public void vehicleBleOpensAsBefore() {
    assertEquals(Decision.UNLOCK_PRIMARY, BeaconCombine.decide(UNLOCK, NONE, false, false, 1000, -1, -1));
  }

  @Test
  public void beaconCanOpenWhenVehicleBleIsNotNear() {
    assertEquals(Decision.UNLOCK_BEACON, BeaconCombine.decide(NONE, UNLOCK, true, true, 1000, -1, -1));
  }

  @Test
  public void staleBeaconNeverDrivesAnUnlock() {
    assertEquals(Decision.NONE, BeaconCombine.decide(NONE, UNLOCK, false, false, 1000, -1, -1));
  }

  @Test
  public void beaconUnlockIsMutedBrieflyAfterALock() {
    long lock = 10000;
    assertEquals(
        Decision.NONE,
        BeaconCombine.decide(NONE, UNLOCK, true, true, lock + 1000, lock, -1)); // Within cooldown.
    assertEquals(
        Decision.UNLOCK_BEACON,
        BeaconCombine.decide(
            NONE, UNLOCK, true, true, lock + BeaconCombine.UNLOCK_COOLDOWN_MS, lock, -1));
  }

  @Test
  public void locksWhenBothAreClear() {
    assertEquals(Decision.LOCK, BeaconCombine.decide(LOCK, LOCK, true, false, 1000, -1, -1));
  }

  @Test
  public void deadBeaconDoesNotBlockTheLock() {
    // Security: a not-receiving beacon must never keep the car unlocked.
    assertEquals(Decision.LOCK, BeaconCombine.decide(LOCK, NONE, false, false, 1000, -1, -1));
  }

  @Test
  public void holdsTheLockWhileTheBeaconStillSeesThePhone() {
    assertEquals(Decision.HOLD, BeaconCombine.decide(LOCK, NONE, true, true, 1000, -1, 500));
  }

  @Test
  public void aHeldLockProceedsAfterTheMaxHold() {
    long held = 1000;
    assertEquals(Decision.HOLD, BeaconCombine.decide(LOCK, NONE, true, true, held + 1000, -1, held));
    assertEquals(
        Decision.LOCK,
        BeaconCombine.decide(
            LOCK, NONE, true, true, held + BeaconCombine.MAX_LOCK_HOLD_MS, -1, held));
  }

  @Test
  public void nothingPendingIsNone() {
    assertEquals(Decision.NONE, BeaconCombine.decide(NONE, NONE, true, false, 1000, -1, -1));
  }

  @Test
  public void openTakesPriorityOverADanglingHold() {
    // Primary wants to open: never hold/lock in that case.
    assertEquals(
        Decision.UNLOCK_PRIMARY, BeaconCombine.decide(UNLOCK, NONE, true, true, 1000, -1, 500));
  }
}
