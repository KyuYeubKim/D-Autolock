package com.dautolock.app.core;

import com.dautolock.link.LinkProtocol;

/**
 * Decides when to auto-unlock the doors because the driver just parked. Fed every authenticated
 * Bridge gear sample; returns {@code true} exactly once per park event, and only for a real park
 * after driving — never for merely connecting to an already-parked car.
 *
 * <p>Safety, mirroring the pitfalls the BYD relay developers hit:
 *
 * <ul>
 *   <li>Only a valid live gear (quality 0) of P counts. The real gear can never flicker to P on an
 *       auto-hold traffic stop the way a beacon bit can (their FB1098: a false P unlocked a moving
 *       car), so reading the actual gear is the defence.
 *   <li>Requires a non-P gear seen first (a real drive). Connecting while already in P does nothing.
 *   <li>Requires P to hold for {@link #STABLE_MS} before firing, so a momentary shift through P does
 *       not unlock.
 *   <li>A sample gap longer than {@link #GAP_MS} (the phone left Bluetooth range) clears the "drove"
 *       state, so walking back to a parked car does not unlock it (their FB1892: a phantom night-time
 *       drive opened the doors with nobody near). The Bridge link only delivers samples while the
 *       phone is within ~10 m of the car, so a live sample is itself the "phone near car" evidence.
 *   <li>Dedupes within {@link #DEDUP_MS} so two parks in quick succession fire at most once.
 * </ul>
 */
public final class ParkUnlockTrigger {
  public static final long STABLE_MS = 2000, DEDUP_MS = 10000, GAP_MS = 60000;

  private boolean droveSincePark, firedThisPark;
  private long parkStableSince, lastSampleAt = Long.MIN_VALUE, lastFiredAt = Long.MIN_VALUE;

  /**
   * @param gear LinkProtocol gear constant from the sample
   * @param quality 0 means the gear read is trustworthy
   * @param now monotonic time (SystemClock.elapsedRealtime)
   * @return true exactly once when a stable P after driving is confirmed
   */
  public boolean onSample(int gear, int quality, long now) {
    if (lastSampleAt != Long.MIN_VALUE && now - lastSampleAt > GAP_MS) {
      // The link was gone long enough that the phone left the car; start a fresh session.
      droveSincePark = false;
      firedThisPark = false;
      parkStableSince = 0;
    }
    lastSampleAt = now;
    if (quality != 0 || gear == LinkProtocol.UNKNOWN) return false; // Untrusted read: hold state.
    if (gear != LinkProtocol.P) {
      droveSincePark = true; // R/N/D = a real drive; arm the next park.
      firedThisPark = false;
      parkStableSince = 0;
      return false;
    }
    if (parkStableSince == 0) parkStableSince = now;
    if (firedThisPark || !droveSincePark) return false;
    if (now - parkStableSince < STABLE_MS) return false;
    firedThisPark = true; // Latch regardless, so we check at most once per park.
    if (lastFiredAt != Long.MIN_VALUE && now - lastFiredAt < DEDUP_MS) return false;
    lastFiredAt = now;
    return true;
  }
}
