package com.dautolock.app.core;

import org.json.JSONObject;

/** Whitelisted diagnostic fields only; a server response may have no measurement time. */
public final class HvacSnapshot {
  public final Integer status, acSwitch, windPosition;
  /** Driver set temperature in °C, its 1–17 scale value, and the car's settable °C range. */
  public final Double setTemp, setScale, rangeMin, rangeMax;
  public final long measuredAt, receivedAt;

  public HvacSnapshot(JSONObject response, long receivedAt) {
    this.receivedAt = receivedAt;
    // getStatusNow nests the fields under "statusNow" (pyBYD unwraps it; real log: all null).
    JSONObject nested = response.optJSONObject("statusNow");
    JSONObject data = nested != null ? nested : response;
    status = value(data, "status");
    acSwitch = value(data, "acSwitch");
    windPosition = value(data, "windPosition");
    setTemp = celsius(data, "mainSettingTempNew");
    setScale = celsius(data, "mainSettingTemp");
    rangeMin = celsius(data, "vdeTempRangeMin");
    rangeMax = celsius(data, "vdeTempRangeMax");
    measuredAt =
        VehicleSnapshot.timestamp(
            data.has("timeStamp")
                ? data.opt("timeStamp")
                : data.has("timestamp") ? data.opt("timestamp") : data.opt("time"));
  }

  /** A plausible cabin temperature or scale value; sentinels like -129 are missing. */
  private static Double celsius(JSONObject data, String key) {
    Object raw = data.opt(key);
    if (raw == null || raw == JSONObject.NULL || raw instanceof Boolean) return null;
    try {
      double n = Double.parseDouble(String.valueOf(raw));
      return Double.isFinite(n) && n > 0 && n <= 60 ? n : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * °C offset of the car's 1–17 climate scale from its reported range (pyBYD
   * temp_offset_from_range): min − 1, or null when the range is missing or does not fit.
   */
  public Double tempOffset() {
    if (rangeMin == null || rangeMax == null) return null;
    double span = rangeMax - rangeMin;
    return span > 0 && span <= 16 ? rangeMin - 1 : null;
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
        + " setTemp="
        + setTemp
        + " setScale="
        + setScale
        + " range="
        + rangeMin
        + "~"
        + rangeMax
        + " measuredAt="
        + measuredAt
        + " receivedAt="
        + receivedAt
        + " ageMs="
        + (measuredAt == 0 ? -1 : now - measuredAt);
  }
}
