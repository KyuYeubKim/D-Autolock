package com.dautolock.link;

/** Only authenticated, prompt responses enter this cache. All clocks are phone monotonic time. */
public final class LinkState {
  public static final long MAX_AGE_MS = 3000, MAX_ROUND_TRIP_MS = 2000, STABLE_P_MS = 2000;
  private LinkProtocol.Sample sample;
  private long sampled = -1, pSince = -1;
  private int pCount;

  public synchronized void clear() {
    sample = null;
    sampled = -1;
    pSince = -1;
    pCount = 0;
  }

  public synchronized boolean accept(LinkProtocol.Sample next, long requested, long now) {
    if (requested < 0 || now < requested || now - requested > MAX_ROUND_TRIP_MS) {
      clear();
      return false;
    }
    boolean uninterrupted =
        sampled >= 0 && requested >= sampled && requested - sampled <= MAX_AGE_MS;
    sample = next;
    sampled = requested; // Age includes transit and getter time, never just receive time.
    if (next.gear == LinkProtocol.P && next.quality == 0 && next.brake != 0) {
      if (!uninterrupted || pSince < 0) {
        pSince = requested;
        pCount = 0;
      }
      pCount++;
    } else {
      pSince = -1;
      pCount = 0;
    }
    return true;
  }

  public synchronized LinkProtocol.Sample fresh(long now) {
    return sampled >= 0 && now >= sampled && now - sampled <= MAX_AGE_MS ? sample : null;
  }

  public synchronized String block(long now) {
    LinkProtocol.Sample s = fresh(now);
    if (s == null) return "차량 보조 앱 연결 끊김 또는 3초 이상 지난 기어 정보";
    if (s.quality != 0) return "차량 기어 조회 실패 또는 값 불일치";
    if (s.gear != LinkProtocol.P) return "실제 P단 미확인 (" + s.gearLabel() + ")";
    if (s.brake == 0) return "차량 주차브레이크 해제";
    if (pSince < 0 || now - pSince < STABLE_P_MS || pCount < 3) return "P단 2초 연속 확인 중";
    return null;
  }

  public synchronized String describe(long now) {
    LinkProtocol.Sample s = fresh(now);
    if (s == null) return "기어 미확인 · 최신 수신 없음";
    return "기어 "
        + s.gearLabel()
        + " · 주차브레이크 "
        + (s.brake == 1 ? "체결" : s.brake == 0 ? "해제" : "미제공")
        + " · "
        + (now - sampled)
        + " ms 전\n"
        + (block(now) == null ? "P단 연속 확인됨" : block(now));
  }
}
