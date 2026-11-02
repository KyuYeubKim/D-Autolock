package com.dautolock.app.core;

import com.dautolock.app.core.ProximityEngine.Action;

/**
 * Combine the primary (vehicle) BLE decision with the auxiliary trunk beacon.
 *
 * <ul>
 *   <li><b>Open on EITHER</b> (OR): the vehicle BLE opens as before, and the beacon can also open —
 *       so approaching from the rear/trunk works even when the car's own BLE is weak there.
 *   <li><b>Lock on EITHER</b> (OR, since v0.3.41): the vehicle BLE locks as before, and the beacon
 *       can also lock once it really measures the phone far away — but never while the other
 *       signal still has the phone at the car. That rule is what stops the two from fighting
 *       (one says near and opens, the other says far and locks, over and over).
 * </ul>
 *
 * Safeguards (a car must not stay unlocked, and must not lock on someone at it):
 *
 * <ul>
 *   <li><b>Dead-beacon fallback:</b> a stale/not-receiving beacon never blocks a lock, and never
 *       locks on its own either — a flat battery is not a departure. The vehicle BLE alone decides.
 *   <li><b>Vehicle BLE near wins:</b> a beacon lock waits while the vehicle BLE's last reading is
 *       near (that reading persists through the car's Bluetooth dropping out at ignition off, so a
 *       phone inside the car is not locked in). There is no time limit on this one: the car's own
 *       BLE is the authority.
 *   <li><b>Max hold:</b> a vehicle-BLE lock held because the beacon stays near proceeds anyway after
 *       {@link #MAX_LOCK_HOLD_MS}, so a stuck-near beacon can never keep the car unlocked forever.
 *   <li><b>Flap guard:</b> a beacon-only unlock is muted for {@link #UNLOCK_COOLDOWN_MS} right after
 *       a lock, so a strong beacon cannot immediately re-open the car.
 * </ul>
 *
 * Pure function so it is fully unit tested; all timing and thresholds are decided by the two
 * {@link ProximityEngine}s that feed it.
 */
public final class BeaconCombine {
  public static final long UNLOCK_COOLDOWN_MS = 5000;
  public static final long MAX_LOCK_HOLD_MS = 30000;

  public enum Decision {
    NONE,
    UNLOCK_PRIMARY,
    UNLOCK_BEACON,
    LOCK,
    LOCK_BEACON,
    HOLD
  }

  private BeaconCombine() {}

  /**
   * @param primary the vehicle BLE's pending action
   * @param beacon the beacon's pending action
   * @param beaconFresh beacon is currently receiving (not stale/dead)
   * @param beaconNear beacon is fresh and its smoothed signal is at/above its near threshold
   * @param primaryNear the vehicle BLE's last smoothed reading is at/above its near threshold
   * @param now monotonic time
   * @param lastLockAt time of the last lock (-1 if none) — for the post-lock unlock mute
   * @param lockHoldSince time the current lock hold started (-1 if not holding) — for the max hold
   */
  public static Decision decide(
      Action primary,
      Action beacon,
      boolean beaconFresh,
      boolean beaconNear,
      boolean primaryNear,
      long now,
      long lastLockAt,
      long lockHoldSince) {
    // OR to open.
    if (primary == Action.UNLOCK) return Decision.UNLOCK_PRIMARY;
    if (beacon == Action.UNLOCK
        && beaconFresh
        && (lastLockAt < 0 || now - lastLockAt >= UNLOCK_COOLDOWN_MS)) return Decision.UNLOCK_BEACON;
    // OR to lock: the vehicle BLE first, as before …
    if (primary == Action.LOCK) {
      boolean beaconBlocks = beaconFresh && beaconNear; // Beacon still sees the phone at the car.
      if (!beaconBlocks) return Decision.LOCK; // Includes the dead-beacon fallback.
      if (lockHoldSince >= 0 && now - lockHoldSince >= MAX_LOCK_HOLD_MS) return Decision.LOCK;
      return Decision.HOLD;
    }
    // … then the beacon, only on a live far reading and only with the vehicle BLE not near.
    if (beacon == Action.LOCK && beaconFresh && !primaryNear) return Decision.LOCK_BEACON;
    return Decision.NONE;
  }
}
