package com.dautolock.app.core;

import com.dautolock.app.core.ProximityEngine.Action;

/**
 * Phase 2: combine the primary (vehicle) BLE decision with the auxiliary trunk beacon.
 *
 * <ul>
 *   <li><b>Open on EITHER</b> (OR): the vehicle BLE opens as before, and the beacon can also open —
 *       so approaching from the rear/trunk works even when the car's own BLE is weak there.
 *   <li><b>Lock only when BOTH are clear</b> (AND): the vehicle BLE must want to lock and the beacon
 *       must no longer see the phone at the car.
 * </ul>
 *
 * Conservative safeguards (security first — a car must not stay unlocked):
 *
 * <ul>
 *   <li><b>Dead-beacon fallback:</b> a stale/not-receiving beacon never blocks a lock; the lock
 *       falls back to the vehicle BLE alone.
 *   <li><b>Max hold:</b> a lock held because the beacon stays near proceeds anyway after
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
    HOLD
  }

  private BeaconCombine() {}

  /**
   * @param primary the vehicle BLE's pending action
   * @param beacon the beacon's pending action
   * @param beaconFresh beacon is currently receiving (not stale/dead)
   * @param beaconNear beacon is fresh and its smoothed signal is at/above its near threshold
   * @param now monotonic time
   * @param lastLockAt time of the last lock (-1 if none) — for the post-lock unlock mute
   * @param lockHoldSince time the current lock hold started (-1 if not holding) — for the max hold
   */
  public static Decision decide(
      Action primary,
      Action beacon,
      boolean beaconFresh,
      boolean beaconNear,
      long now,
      long lastLockAt,
      long lockHoldSince) {
    // OR to open.
    if (primary == Action.UNLOCK) return Decision.UNLOCK_PRIMARY;
    if (beacon == Action.UNLOCK
        && beaconFresh
        && (lastLockAt < 0 || now - lastLockAt >= UNLOCK_COOLDOWN_MS)) return Decision.UNLOCK_BEACON;
    // AND to lock.
    if (primary == Action.LOCK) {
      boolean beaconBlocks = beaconFresh && beaconNear; // Beacon still sees the phone at the car.
      if (!beaconBlocks) return Decision.LOCK; // Includes the dead-beacon fallback.
      if (lockHoldSince >= 0 && now - lockHoldSince >= MAX_LOCK_HOLD_MS) return Decision.LOCK;
      return Decision.HOLD;
    }
    return Decision.NONE;
  }
}
