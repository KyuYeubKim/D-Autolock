package com.dautolock.app.core;

import org.json.JSONObject;

/** Whitelisted diagnostic fields only; a server response may have no measurement time. */
public final class HvacSnapshot {
  public final Integer status, acSwitch, windPosition;
  public final long measuredAt, receivedAt;

  public HvacSnapshot(JSONObject data, long receivedAt) {
    this.receivedAt = receivedAt;
    status = value(data, "status");
    acSwitch = value(data, "acSwitch");
    windPosition = value(data, "windPosition");
    measuredAt =
        VehicleSnapshot.timestamp(
            data.has("timeStamp")
                ? data.opt("timeStamp")
                : data.has("timestamp") ? data.opt("timestamp") : data.opt("time"));
  }

  private static Integer value(JSONObject data, String key) {
    Object raw = data.opt(key);
    if (raw == null || raw == JSONObject.NULL || raw instanceof Boolean) return null;
    try {
      double n = Double.parseDouble(String.valueOf(raw));
      return Double.isFinite(n) && n == Math.rint(n) && n >= 0 && n <= 100 ? (int) n : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  public String label(long now) {
    String state =
        Integer.valueOf(1).equals(status)
            ? "ON"
            : Integer.valueOf(2).equals(status) ? "OFF" : "미확인";
    String timing =
        measuredAt == 0
            ? "측정 시각 미제공"
            : now - measuredAt > 30000 || now - measuredAt < -5000 || now - receivedAt > 30000
                ? "오래된 값·시각 확인 필요"
                : "최근 측정";
    return "공조 서버 응답 " + state + " · " + timing;
  }

  public String diagnostic(long now) {
    return "status="
        + status
        + " acSwitch="
        + acSwitch
        + " windPosition="
        + windPosition
        + " measuredAt="
        + measuredAt
        + " receivedAt="
        + receivedAt
        + " ageMs="
        + (measuredAt == 0 ? -1 : now - measuredAt);
  }
}
