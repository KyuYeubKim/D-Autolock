package com.dautolock.app.core;

/** Summarizes separated post-command samples without claiming continuous READY. */
public final class ReadyObservation {
  private final long sentAt;
  private long lastMeasured;
  private int onCount, offCount, unknownCount, powerOffCount;
  private boolean dropped;

  public ReadyObservation(long sentAt) {
    this.sentAt = sentAt;
  }

  public void accept(VehicleSnapshot s, long now) {
    if (!s.fresh(now) || s.measuredAt < sentAt || s.measuredAt <= lastMeasured) {
      unknownCount++;
      return;
    }
    lastMeasured = s.measuredAt;
    if (Integer.valueOf(1).equals(s.power)) powerOffCount++;
    if (Integer.valueOf(1).equals(s.okLight) && Integer.valueOf(3).equals(s.power)) onCount++;
    else if (Integer.valueOf(0).equals(s.okLight)) {
      offCount++;
      if (onCount > 0) dropped = true;
    } else unknownCount++;
  }

  public void unavailable() {
    unknownCount++;
  }

  public String summary() {
    String result =
        dropped
            ? "OK 표시값 1 → 0 변화 감지"
            : onCount > 0
                ? "OK 표시값 1 관측 " + onCount + "회 · 계기판 대조 필요"
                : offCount > 0 ? "OK 표시값 0 관측 · READY 유지 미확인" : "READY 유지 미확인 · 유효한 OK 표시값 없음";
    return result + " · 전원 OFF 관측 " + powerOffCount + "회 · 미확인 " + unknownCount + "회";
  }

  public String diagnostic() {
    return "okOnCount="
        + onCount
        + " okOffCount="
        + offCount
        + " unknownCount="
        + unknownCount
        + " powerOffCount="
        + powerOffCount
        + " okDropped="
        + dropped
        + " continuousReadyVerified=false";
  }
}
