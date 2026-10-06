package com.dautolock.app.core;

/** Pure decision logic. Signal absence never means departure. Uses elapsed time. */
public final class ProximityEngine {
  public enum Action {
    NONE,
    UNLOCK,
    LOCK
  }

  private enum Zone {
    UNKNOWN,
    FAR,
    NEAR
  }

  private final int near, far;
  private double smoothed = Double.NaN;
  private long lastSample = -1, candidateSince = -1, lastAction = -30000;
  private int sampleCount;
  private Zone stable = Zone.UNKNOWN, candidate = Zone.UNKNOWN;

  public ProximityEngine(int near, int far) {
    if (near > -30 || far < -100 || near - far < 8)
      throw new IllegalArgumentException("거리 기준을 확인하세요");
    this.near = near;
    this.far = far;
  }

  public Action sample(int rssi, long now) {
    if (rssi < -110 || rssi > -20) return Action.NONE;
    if (lastSample >= 0 && (now < lastSample || now - lastSample > 5000)) reset();
    lastSample = now;
    smoothed = Double.isNaN(smoothed) ? rssi : .3 * rssi + .7 * smoothed;
    Zone next = smoothed >= near ? Zone.NEAR : smoothed <= far ? Zone.FAR : Zone.UNKNOWN;
    if (next == Zone.UNKNOWN) {
      candidate = next;
      candidateSince = -1;
      sampleCount = 0;
      return Action.NONE;
    }
    if (next != candidate) {
      candidate = next;
      candidateSince = now;
      sampleCount = 1;
      return Action.NONE;
    }
    sampleCount++;
    long dwell = next == Zone.NEAR ? 3000 : 8000;
    if (now - candidateSince < dwell || sampleCount < 4 || stable == next) return Action.NONE;
    Zone previous = stable;
    stable = next;
    if (previous == Zone.UNKNOWN || now - lastAction < 30000) return Action.NONE;
    lastAction = now;
    return next == Zone.NEAR ? Action.UNLOCK : Action.LOCK;
  }

  public boolean stillValid(Action action, long now) {
    if (lastSample < 0 || now < lastSample || now - lastSample > 5000) return false;
    return action == Action.UNLOCK
        ? stable == Zone.NEAR && candidate == Zone.NEAR && smoothed >= near
        : action == Action.LOCK && stable == Zone.FAR && candidate == Zone.FAR && smoothed <= far;
  }

  public double rssi() {
    return smoothed;
  }

  public String zone(long now) {
    if (lastSample < 0 || now - lastSample > 5000) return "신호 대기";
    if (candidate == Zone.NEAR) return "가까움";
    if (candidate == Zone.FAR) return "멀어짐";
    return "중간 거리";
  }

  public void reset() {
    smoothed = Double.NaN;
    lastSample = -1;
    candidateSince = -1;
    sampleCount = 0;
    stable = Zone.UNKNOWN;
    candidate = Zone.UNKNOWN;
  }
}
