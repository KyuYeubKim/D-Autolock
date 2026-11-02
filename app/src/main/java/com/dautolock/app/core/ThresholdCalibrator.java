package com.dautolock.app.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns RSSI measured where the user wants the doors to open and where they want them to lock
 * into near/far thresholds for the vehicle BLE and the beacon, for the OR/OR combine
 * ({@link BeaconCombine}): a spot opens when either signal is at/above its near; it locks when
 * neither is near and either is at/below its far (a lost vehicle BLE also locks, after its loss
 * wait; a lost beacon never does).
 *
 * <p>Each open spot is given to the signal that hears it most clearly above that signal's own
 * strongest lock-spot reading (its margin) — and also to the other signal when that one still
 * clears its lock spots by {@link #SHARE_DB}, so a dead beacon leaves the car BLE able to open
 * there. Per signal, near then goes halfway between its strongest lock-spot reading and the
 * weakest open spot it was given (a weak spot another signal hears far better does not drag it
 * down). Far sits just above the strongest lock-spot reading, kept the minimum gap below near.
 * A signal given no spot only opens when stronger than anywhere it was measured.
 */
public final class ThresholdCalibrator {
  public static final int MIN_NEAR = -95, MAX_NEAR = -40, MIN_FAR = -100;
  static final int SHARE_DB = 15;
  private static final int GAP = ProximityEngine.MIN_GAP_DB;
  /** Lock spots read a little stronger now and then; far sits this much above the strongest. */
  private static final int LOCK_MARGIN = 2;

  /** One measured position: smoothed RSSI of each signal, NaN when it was not received there. */
  public static final class Spot {
    public final double primary, beacon;

    public Spot(double primary, double beacon) {
      this.primary = primary;
      this.beacon = beacon;
    }
  }

  public static final class Pair {
    public final int near, far;

    public Pair(int near, int far) {
      this.near = near;
      this.far = far;
    }
  }

  public static final class Result {
    /** The thresholds to save; a signal with no readings keeps the current pair passed in. */
    public final Pair primary, beacon;

    public final boolean primaryMeasured, beaconMeasured;
    /** How many open spots would open, and how many lock spots would lock, with these values. */
    public final int openCovered, lockCovered;

    Result(
        Pair primary,
        Pair beacon,
        boolean primaryMeasured,
        boolean beaconMeasured,
        int openCovered,
        int lockCovered) {
      this.primary = primary;
      this.beacon = beacon;
      this.primaryMeasured = primaryMeasured;
      this.beaconMeasured = beaconMeasured;
      this.openCovered = openCovered;
      this.lockCovered = lockCovered;
    }
  }

  private ThresholdCalibrator() {}

  /**
   * @param beacon current beacon thresholds, or null when no beacon is configured (then only the
   *     vehicle BLE is calibrated and checked)
   */
  public static Result calibrate(List<Spot> open, List<Spot> lock, Pair primary, Pair beacon) {
    List<Integer> lockP = values(lock, true), lockB = beacon == null ? new ArrayList<>() : values(lock, false);
    // Margin of an open spot over a signal's strongest lock spot; never heard at lock spots = wide.
    List<Integer> givenP = new ArrayList<>(), givenB = new ArrayList<>();
    for (Spot s : open) {
      int mp = margin(s.primary, lockP), mb = beacon == null ? Integer.MIN_VALUE : margin(s.beacon, lockB);
      boolean toP = mp >= GAP && (mp >= mb || mp >= SHARE_DB);
      boolean toB = mb >= GAP && (mb > mp || mb >= SHARE_DB);
      if (toP) givenP.add((int) Math.round(s.primary));
      if (toB) givenB.add((int) Math.round(s.beacon));
    }
    Pair p = pair(givenP, values(open, true), lockP);
    Pair b = beacon == null ? null : pair(givenB, values(open, false), lockB);
    Pair primaryUsed = p == null ? primary : p, beaconUsed = beacon == null ? null : b == null ? beacon : b;
    int opened = 0, locked = 0;
    for (Spot s : open) if (opens(s, primaryUsed, beaconUsed)) opened++;
    for (Spot s : lock) if (locks(s, primaryUsed, beaconUsed)) locked++;
    return new Result(primaryUsed, beaconUsed, p != null, b != null, opened, locked);
  }

  /** Margin of a reading over the strongest lock reading; MIN_VALUE when not heard here. */
  private static int margin(double value, List<Integer> locks) {
    if (Double.isNaN(value)) return Integer.MIN_VALUE;
    return locks.isEmpty() ? 100 : (int) Math.round(value) - max(locks);
  }

  /**
   * @param given the open-spot readings this signal is responsible for
   * @param heard every open-spot reading of this signal
   * @param locks every lock-spot reading of this signal
   */
  static Pair pair(List<Integer> given, List<Integer> heard, List<Integer> locks) {
    if (heard.isEmpty() && locks.isEmpty()) return null;
    int near, far;
    if (locks.isEmpty()) {
      // Never heard where it should lock (it drops out there): open just under the weakest open.
      near = (given.isEmpty() ? max(heard) : min(given)) - 2;
      far = near - GAP;
    } else {
      int strongestLock = max(locks);
      List<Integer> clear = new ArrayList<>();
      for (int v : given) if (v >= strongestLock + GAP) clear.add(v);
      if (clear.isEmpty()) {
        // Opens nowhere it was measured: only when stronger than every spot, never at a lock spot.
        near = Math.max(strongestLock + GAP, heard.isEmpty() ? Integer.MIN_VALUE : max(heard) + 1);
      } else {
        int weakestOpen = min(clear);
        near = Math.max(strongestLock + GAP, Math.min(weakestOpen, (weakestOpen + strongestLock) / 2));
      }
      far = Math.min(strongestLock + LOCK_MARGIN, near - GAP);
    }
    near = Math.max(MIN_NEAR, Math.min(MAX_NEAR, near));
    far = Math.max(MIN_FAR, Math.min(near - GAP, far));
    return new Pair(near, far);
  }

  static boolean opens(Spot s, Pair primary, Pair beacon) {
    return near(s.primary, primary) || near(s.beacon, beacon);
  }

  static boolean locks(Spot s, Pair primary, Pair beacon) {
    if (opens(s, primary, beacon)) return false; // Either still near: no lock (OR/OR guard).
    boolean primaryFar = primary != null && (Double.isNaN(s.primary) || s.primary <= primary.far);
    boolean beaconFar = beacon != null && !Double.isNaN(s.beacon) && s.beacon <= beacon.far;
    return primaryFar || beaconFar;
  }

  private static boolean near(double value, Pair pair) {
    return pair != null && !Double.isNaN(value) && value >= pair.near;
  }

  private static List<Integer> values(List<Spot> spots, boolean primary) {
    List<Integer> out = new ArrayList<>();
    for (Spot s : spots) {
      double v = primary ? s.primary : s.beacon;
      if (!Double.isNaN(v)) out.add((int) Math.round(v));
    }
    return out;
  }

  private static int min(List<Integer> values) {
    int m = Integer.MAX_VALUE;
    for (int v : values) m = Math.min(m, v);
    return m;
  }

  private static int max(List<Integer> values) {
    int m = Integer.MIN_VALUE;
    for (int v : values) m = Math.max(m, v);
    return m;
  }
}
