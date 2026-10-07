package com.dautolock.app.core;

import java.time.Instant;
import org.json.JSONObject;

/** Missing fields stay unknown. Power state is not transmission gear or READY. */
public final class VehicleSnapshot {
  public final Integer power, epb, okLight;
  public final Double speed, battery;
  public final Boolean locked, doorsClosed, windowsClosed;
  public final long measuredAt, receivedAt;
  private final boolean invalidEpb;
  public final String epbStatus;

  public VehicleSnapshot(JSONObject data, long receivedAt) {
    this.receivedAt = receivedAt;
    power = integer(data, "powerGear");
    okLight = integer(data, "okLight");
    epb = integer(data, "epb");
    Object brake = data.opt("epb");
    boolean absentBrake = unavailableBrake(brake);
    invalidEpb = !absentBrake && (epb == null || (epb != 0 && epb != 1));
    epbStatus =
        absentBrake
            ? "unavailable"
            : invalidEpb ? "invalid" : Integer.valueOf(1).equals(epb) ? "engaged" : "released";
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

  private static boolean unavailableBrake(Object value) {
    if (value == null || value == JSONObject.NULL) return true;
    if (value instanceof Boolean) return false;
    String text = String.valueOf(value).trim();
    return text.isEmpty()
        || text.equals("--")
        || text.equalsIgnoreCase("NaN")
        || text.equals("-1")
        || text.equals("-1.0");
  }

  public String automaticStopBlock(long now) {
    return automaticStopBlock(now, false, -1);
  }

  /** A verified live P may cover absent EPB only, never explicit released/invalid telemetry. */
  public String automaticStopBlock(long now, boolean livePark, int vehicleBrake) {
    String block = manualBlock(false, now);
    if (block != null) return block;
    if (power == null || (power != 1 && power != 3)) return "차량 전원 상태를 확인하지 못했습니다";
    if (power == 3) {
      if (vehicleBrake == 0 || "released".equals(epbStatus) || "invalid".equals(epbStatus))
        return "주차브레이크 해제 또는 알 수 없는 상태입니다";
      if (!Integer.valueOf(1).equals(epb) && !livePark)
        return "주차브레이크 정보 미제공 · 자동 종료 불가. 차량 보조 앱의 최신 P단 또는 수동 주차 확인이 필요합니다";
    }
    if (!Boolean.TRUE.equals(locked) || !Boolean.TRUE.equals(doorsClosed))
      return "잠금·모든 도어 닫힘 확인이 필요합니다";
    return null;
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
    if (yesCount == keys.length) return Boolean.TRUE;
    if (noCount == keys.length) return Boolean.FALSE;
    return null;
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
    return automaticBlock(lock, now, false);
  }

  /** Departure can qualify a door lock, never a power-off request. */
  public String automaticBlock(boolean lock, long now, boolean departureConfirmed) {
    if (!fresh(now)) return "차량 상태가 오래되었거나 측정 시각이 없습니다";
    if (speed == null || speed != 0d) return "정차 상태를 확인하지 못했습니다";
    if (power == null || (power != 1 && power != 3)) return "차량 전원 상태를 확인하지 못했습니다";
    if (!lock && power != 1) return "자동 잠금 해제는 차량 전원 OFF 상태에서 실행합니다";
    if (lock && power == 3 && !Integer.valueOf(1).equals(epb)) {
      if (!"unavailable".equals(epbStatus)) return "주차브레이크 해제 또는 알 수 없는 상태입니다";
      if (!departureConfirmed) return "전원 ON·주차브레이크 미제공: 신호 세기로 이탈을 확인해야 잠급니다";
    }
    if (locked == null) return "도어 잠금 상태를 확인하지 못했습니다";
    if (lock && !Boolean.TRUE.equals(doorsClosed)) return "모든 도어가 닫혔는지 확인하지 못했습니다";
    return null;
  }

  public String manualBlock(boolean stop, long now) {
    if (stop) return manualStopBlock(now, false);
    if (!fresh(now)) return "최신 차량 상태를 확인하지 못했습니다";
    if (speed == null || speed != 0d) return "차량 정차를 확인하지 못했습니다";
    return null;
  }

  /** A one-use manual parking confirmation may cover absent telemetry, never a released brake. */
  public String manualStopBlock(long now, boolean parkingConfirmed) {
    String block = manualBlock(false, now);
    if (block != null) return block;
    if (power == null || (power != 1 && power != 3)) return "차량 전원 상태를 확인하지 못했습니다";
    if (power == 1) return null; // Already OFF: skip the command without requiring EPB telemetry.
    if (Integer.valueOf(1).equals(epb)) return null;
    if (!"unavailable".equals(epbStatus)) return "주차브레이크 해제 또는 알 수 없는 상태입니다";
    if (!parkingConfirmed) return "주차브레이크 정보 미제공 · 자동 종료 불가. P단·주차브레이크를 직접 확인한 뒤 수동 Stop을 사용하세요";
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

  /** A reported lamp value is diagnostic evidence, not authorization to drive. */
  public String readyLabel(long now) {
    if (!fresh(now)) return "READY 미확인 · 오래된 차량 상태";
    if (okLight == null) return "READY 미확인 · OK 표시값 미제공";
    if (okLight == 0) return "OK 표시값 OFF (0)";
    if (okLight == 1 && Integer.valueOf(3).equals(power)) return "OK 표시값 ON (1) · 계기판 대조 필요";
    return "READY 미확인 · OK 표시값과 전원 상태 대조 필요";
  }

  public String diagnostic(long now) {
    return "speed="
        + speed
        + " power="
        + power
        + " okLight="
        + okLight
        + " epb="
        + epb
        + " epbStatus="
        + epbStatus
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
