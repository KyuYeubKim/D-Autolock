package com.dautolock.app.core;

/** Fresh signals propose a state; only a handled/sent request consumes it. */
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

  public static final long STALE_MS = 5000, COOLDOWN_MS = 10000, LOSS_LOCK_MS = 10000;
  private final int near, far;
  private final long nearDwellMs, farDwellMs, lossLockMs;
  private double smoothed = Double.NaN;
  private int raw, samples, freshSamples;
  private long lastSample = -1, since = -1, lastDispatch = -COOLDOWN_MS, received;
  private Zone candidate = Zone.UNKNOWN, stable = Zone.UNKNOWN, handled = Zone.UNKNOWN;
  private boolean seenNear;
  /** A stable zone was established at least once; survives the reset after a reception gap. */
  private boolean everStable;
  private Action lastAction = Action.NONE;
  private long unlockCheckUntil = -1;
  /**
   * Anti-flap: right after an automatic unlock a phone standing by the car often dips far for a
   * few seconds (body/pocket shadowing). Real logs showed unlock→lock→unlock within 90 s.
   */
  public static final long FLAP_WINDOW_MS = 120000, FLAP_FAR_DWELL_MS = 20000;
  public static final long RELOCK_WINDOW_MS = 60000, RELOCK_NEAR_DWELL_MS = 3000;

  /** Qualify at the configured threshold, then tolerate a small dip during cloud preflight. */
  public synchronized void beginCheck(Action action, long now) {
    unlockCheckUntil = action == Action.UNLOCK && pending(now) == action ? now + 10000 : -1;
  }

  public synchronized void endCheck() {
    unlockCheckUntil = -1;
  }

  public ProximityEngine(int near, int far) {
    this(near, far, 3000, 8000, LOSS_LOCK_MS);
  }

  public ProximityEngine(int near, int far, long nearDwellMs, long farDwellMs, long lossLockMs) {
    if (near > -30 || far < -100 || near - far < 8)
      throw new IllegalArgumentException("거리 기준을 확인하세요");
    if (nearDwellMs < 0
        || nearDwellMs > 15000
        || farDwellMs < 0
        || farDwellMs > 30000
        || lossLockMs < 5000
        || lossLockMs > 60000) throw new IllegalArgumentException("대기 시간 범위를 확인하세요");
    this.near = near;
    this.far = far;
    this.nearDwellMs = nearDwellMs;
    this.farDwellMs = farDwellMs;
    this.lossLockMs = lossLockMs;
  }

  public synchronized Action sample(int rssi, long now) {
    if (rssi < -110 || rssi > -20 || now < 0 || now <= lastSample) return Action.NONE;
    if (lastSample >= 0 && now - lastSample > STALE_MS) {
      unlockCheckUntil = -1;
      smoothed = Double.NaN;
      candidate = Zone.UNKNOWN;
      stable = Zone.UNKNOWN;
      since = -1;
      samples = 0;
      freshSamples = 0;
    }
    lastSample = now;
    raw = rssi;
    received++;
    freshSamples++;
    smoothed = Double.isNaN(smoothed) ? rssi : .3 * rssi + .7 * smoothed;
    if (smoothed < near - 4) unlockCheckUntil = -1;
    Zone next = smoothed >= near ? Zone.NEAR : smoothed <= far ? Zone.FAR : Zone.UNKNOWN;
    if (next != candidate) {
      candidate = next;
      since = now;
      samples = 1;
    } else samples++;
    if (next != Zone.UNKNOWN && samples >= 4 && now - since >= dwell(now)) {
      if (stable != next) {
        if (handled != next) handled = Zone.UNKNOWN;
        stable = next;
      }
      everStable = true;
      if (next == Zone.NEAR) seenNear = true;
    }
    return pending(now);
  }

  private long dwell(long now) {
    boolean recent = lastDispatch >= 0 && now - lastDispatch >= 0;
    if (candidate == Zone.FAR)
      return recent && lastAction == Action.UNLOCK && now - lastDispatch < FLAP_WINDOW_MS
          ? Math.max(farDwellMs, FLAP_FAR_DWELL_MS)
          : farDwellMs;
    return recent && lastAction == Action.LOCK && now - lastDispatch < RELOCK_WINDOW_MS
        ? Math.max(nearDwellMs, RELOCK_NEAR_DWELL_MS)
        : nearDwellMs;
  }

  public synchronized long age(long now) {
    return lastSample < 0 ? -1 : Math.max(0, now - lastSample);
  }

  public synchronized boolean fresh(long now) {
    return lastSample >= 0 && now >= lastSample && now - lastSample <= STALE_MS;
  }

  /** Still valid after claiming a lock; loss alone and a returning phone do not qualify. */
  public synchronized boolean departureConfirmed(long now) {
    return fresh(now)
        && seenNear
        && candidate == Zone.FAR
        && stable == Zone.FAR
        && samples >= 4
        && now - since >= dwell(now);
  }

  public synchronized long cooldown(long now) {
    return Math.max(0, COOLDOWN_MS - (now - lastDispatch));
  }

  /** Start read-only preparation before the unlock threshold, never relax that threshold. */
  public synchronized boolean approaching(long now) {
    return fresh(now)
        && freshSamples >= 4
        && handled != Zone.NEAR
        && cooldown(now) == 0
        && smoothed >= Math.max(near - 8, far + 1);
  }

  public synchronized Action pending(long now) {
    if (lossLockReady(now)) return Action.LOCK;
    if (!fresh(now)
        || candidate == Zone.UNKNOWN
        || stable != candidate
        || samples < 4
        || now - since < dwell(now)
        || handled == candidate
        || cooldown(now) > 0) return Action.NONE;
    if (candidate == Zone.NEAR) return Action.UNLOCK;
    return seenNear ? Action.LOCK : Action.NONE;
  }

  private boolean lossLockReady(long now) {
    return lastSample >= 0
        && now >= lastSample
        && now - lastSample >= lossLockMs
        // Stable reception at some point (not just now): a short gap resets `stable`, and a phone
        // walking away often sends only a few far samples before it is lost (missed lock in logs).
        && everStable
        && received >= 4
        && handled != Zone.FAR
        && cooldown(now) == 0;
  }

  public synchronized boolean stillValid(Action action, long now) {
    if (action == Action.UNLOCK
        && unlockCheckUntil >= now
        && fresh(now)
        && smoothed >= near - 4
        && smoothed > far
        && handled != Zone.NEAR
        && cooldown(now) == 0) return true;
    return action != Action.NONE && pending(now) == action;
  }

  public synchronized boolean claim(Action action, long now) {
    if (!stillValid(action, now)) return false;
    handled = action == Action.LOCK ? Zone.FAR : Zone.NEAR;
    lastDispatch = now;
    lastAction = action;
    unlockCheckUntil = -1;
    return true;
  }

  public synchronized void alreadySatisfied(Action action, long now) {
    if (stillValid(action, now)) {
      handled = action == Action.LOCK ? Zone.FAR : Zone.NEAR;
      unlockCheckUntil = -1;
    }
  }

  public synchronized double rssi() {
    return smoothed;
  }

  public synchronized int raw() {
    return raw;
  }

  public synchronized long count() {
    return received;
  }

  public synchronized String zone(long now) {
    if (!fresh(now)) return lastSample < 0 ? "신호 미수신" : "신호 끊김";
    return candidate == Zone.NEAR ? "가까움" : candidate == Zone.FAR ? "멀어짐" : "중간 거리";
  }

  public synchronized String reason(long now) {
    if (!fresh(now)) {
      if (lastSample < 0) return "선택 기기의 BLE 광고 수신 대기 · 아직 자동 잠금하지 않음";
      if (handled == Zone.FAR) return "신호 끊김 · 잠금 요청 처리됨";
      if (!everStable) return "신호 안정화 기록 부족 · 잠금 보류";
      if (lossLockReady(now)) return "신호 " + lossLockMs / 1000 + "초 끊김 · 도어 잠금 조건 충족";
      return "신호 끊김 잠금까지 " + ((Math.max(lossLockMs - age(now), cooldown(now)) + 999) / 1000) + "초";
    }
    if (candidate == Zone.UNKNOWN) return "접근·이탈 기준 사이 · 거리 변화 대기";
    if (samples < 4 || now - since < dwell(now))
      return "신호 안정화 중 · "
          + samples
          + "회 / "
          + Math.max(0, (dwell(now) - (now - since) + 999) / 1000)
          + "초 남음";
    if (candidate == Zone.FAR && !seenNear) return "접근 기록 없음 · 처음부터 멀리 있을 때 잠그지 않음";
    if (handled == candidate) return "현재 거리의 요청 처리됨 · 반대 거리 구간 대기";
    if (cooldown(now) > 0) return "명령 간격 대기 · " + ((cooldown(now) + 999) / 1000) + "초";
    return candidate == Zone.NEAR ? "잠금 해제 조건 충족" : "도어 잠금 조건 충족";
  }

  public synchronized String diagnostic(long now) {
    return "raw="
        + (received == 0 ? "unknown" : raw)
        + " avg="
        + (Double.isNaN(smoothed) ? "unknown" : Math.round(smoothed))
        + " ageMs="
        + age(now)
        + " total="
        + received
        + " candidate="
        + candidate
        + " stable="
        + stable
        + " samples="
        + samples
        + " dwellMs="
        + (since < 0 ? 0 : now - since)
        + " handled="
        + handled
        + " seenNear="
        + seenNear
        + " cooldownMs="
        + cooldown(now)
        + " pending="
        + pending(now)
        + " unlockCheckMs="
        + Math.max(0, unlockCheckUntil - now)
        + " nearWaitMs="
        + nearDwellMs
        + " farWaitMs="
        + farDwellMs
        + " lossLockMs="
        + lossLockMs;
  }

  public static int strength(double rssi) {
    return Double.isNaN(rssi)
        ? 0
        : (int) Math.round(Math.max(0, Math.min(100, (rssi + 100) * 100 / 70)));
  }
}
