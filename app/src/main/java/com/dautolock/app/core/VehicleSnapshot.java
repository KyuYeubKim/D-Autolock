package com.dautolock.app.core;

import java.time.Instant;
import org.json.JSONObject;

/** Missing fields stay unknown. Power state is not transmission gear or READY. */
public final class VehicleSnapshot {
  public final Integer power, epb;
  public final Double speed, battery;
  public final Boolean locked, doorsClosed, windowsClosed;
  public final long measuredAt, receivedAt;
  private final boolean invalidEpb;

  public VehicleSnapshot(JSONObject data, long receivedAt) {
    this.receivedAt = receivedAt;
    power = integer(data, "powerGear");
    epb = integer(data, "epb");
    invalidEpb = data.has("epb") && !data.isNull("epb") && (epb == null || (epb != 0 && epb != 1));
    speed = number(data, "speed");
    battery = number(data, "soc") != null ? number(data, "soc") : number(data, "elecPercent");
    locked =
        aggregate(
            data,
            new String[] {
              "leftFrontDoorLock", "rightFrontDoorLock", "leftRearDoorLock", "rightRearDoorLock"
            },
            2,
            1);
    // A physical door close code is intentionally NOT guessed across firmware versions.
    doorsClosed = physicalDoors(data);
    windowsClosed =
        aggregate(
            data,
            new String[] {
              "leftFrontWindow", "rightFrontWindow", "leftRearWindow", "rightRearWindow"
            },
            1,
            2);
    measuredAt =
        timestamp(
            data.has("timeStamp")
                ? data.opt("timeStamp")
                : data.has("timestamp") ? data.opt("timestamp") : data.opt("time"));
  }

  private static Boolean physicalDoors(JSONObject d) {
    String[] keys = {"leftFrontDoor", "rightFrontDoor", "leftRearDoor", "rightRearDoor"};
    boolean unknown = false;
    for (String key : keys) {
      Integer value = integer(d, key);
      if (value != null && value == 1) return false;
      if (value == null || value != 0) unknown = true;
    }
    return unknown ? null : true;
  }

  private static Boolean aggregate(JSONObject d, String[] keys, int yes, int no) {
    int yesCount = 0, noCount = 0;
    for (String key : keys) {
      Integer v = integer(d, key);
      if (v == null) return null;
      if (v == yes) yesCount++;
      else if (v == no) noCount++;
      else return null;
    }
    return yesCount == keys.length ? true : noCount == keys.length ? false : null;
  }

  private static Integer integer(JSONObject d, String key) {
    Double n = number(d, key);
    return n == null || n != Math.rint(n) ? null : n.intValue();
  }

  private static Double number(JSONObject d, String key) {
    if (!d.has(key) || d.isNull(key)) return null;
    Object value = d.opt(key);
    if (value instanceof Boolean) return null;
    try {
      double n = Double.parseDouble(String.valueOf(value));
      return Double.isFinite(n) && n >= 0 ? n : null;
    } catch (Exception e) {
      return null;
    }
  }

  public static long timestamp(Object value) {
    if (value == null || value == JSONObject.NULL) return 0;
    try {
      double n = Double.parseDouble(String.valueOf(value));
      if (!Double.isFinite(n) || n <= 0) return 0;
      return (long) (n < 100000000000L ? n * 1000 : n);
    } catch (Exception ignored) {
      try {
        return Instant.parse(String.valueOf(value)).toEpochMilli();
      } catch (Exception e) {
        return 0;
      }
    }
  }

  public boolean fresh(long now) {
    return measuredAt > 0
        && now - measuredAt >= -5000
        && now - measuredAt <= 30000
        && now - receivedAt >= 0
        && now - receivedAt <= 30000;
  }

  public String automaticBlock(boolean lock, long now) {
    if (!fresh(now)) return "차량 상태가 오래되었거나 측정 시각이 없습니다";
    if (speed == null || speed != 0d) return "정차 상태를 확인하지 못했습니다";
    if (power == null || (power != 1 && !(lock && power == 3 && Integer.valueOf(1).equals(epb))))
      return lock ? "전원 OFF 또는 ON·주차브레이크 체결을 확인하지 못했습니다" : "자동 잠금 해제는 차량 전원 OFF 상태에서 실행합니다";
    if (locked == null) return "도어 잠금 상태를 확인하지 못했습니다";
    if (lock && !Boolean.TRUE.equals(doorsClosed)) return "모든 도어가 닫혔는지 확인하지 못했습니다";
    return null;
  }

  public String manualBlock(boolean stop, long now) {
    if (!fresh(now)) return "최신 차량 상태를 확인하지 못했습니다";
    if (speed == null || speed != 0d) return "차량 정차를 확인하지 못했습니다";
    if (stop && (epb == null || epb != 1)) return "주차브레이크 체결을 확인하지 못했습니다";
    if (stop && (power == null || (power != 1 && power != 3))) return "차량 전원 상태를 확인하지 못했습니다";
    return null;
  }

  /** Remote HVAC has its own guard; missing EPB is not evidence that it is engaged. */
  public String climateBlock(long now) {
    if (!fresh(now)) return "최신 차량 상태를 확인하지 못했습니다";
    if (speed == null || speed != 0d) return "차량 정차를 확인하지 못했습니다";
    if (power == null || (power != 1 && power != 3)) return "차량 전원 상태를 확인하지 못했습니다";
    if (invalidEpb || (epb != null && epb != 1)) return "주차브레이크 해제 또는 알 수 없는 상태입니다";
    if (power == 3 && epb == null) return "전원 ON에서는 주차브레이크 체결 확인이 필요합니다";
    return null;
  }

  public String powerLabel() {
    return power == null
        ? "미확인"
        : power == 1 ? "OFF" : power == 3 ? "ON · READY 별도 확인" : "미확인 (" + power + ")";
  }

  public String diagnostic(long now) {
    return "speed="
        + speed
        + " power="
        + power
        + " epb="
        + epb
        + " locked="
        + locked
        + " doorsClosed="
        + doorsClosed
        + " windowsClosed="
        + windowsClosed
        + " measuredAt="
        + measuredAt
        + " ageMs="
        + (measuredAt == 0 ? -1 : now - measuredAt)
        + " fresh="
        + fresh(now);
  }
}
