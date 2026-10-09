package com.dautolock.app.core;

/** Brief labels never turn absent or stale telemetry into a known vehicle state. */
public final class DashboardStatus {
  public static String[] vehicle(VehicleSnapshot state, long now) {
    if (state == null || !state.fresh(now)) return new String[] {"도어 미확인", "시동 미확인", "창문 미확인"};
    return new String[] {
      state.locked == null ? "도어 미확인" : state.locked ? "도어 잠김" : "도어 열림",
      Integer.valueOf(1).equals(state.power)
          ? "시동 꺼짐"
          : Integer.valueOf(3).equals(state.power) ? "시동 켜짐" : "시동 미확인",
      state.windowsClosed == null ? "창문 미확인" : state.windowsClosed ? "창문 닫힘" : "창문 열림"
    };
  }

  private static final java.time.format.DateTimeFormatter CLOCK =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.KOREA)
          .withZone(java.time.ZoneId.of("Asia/Seoul"));
  private static final java.time.format.DateTimeFormatter DAY_CLOCK =
      java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", java.util.Locale.KOREA)
          .withZone(java.time.ZoneId.of("Asia/Seoul"));

  /** When the phone last received vehicle status, in KST. Vehicle measurement time if it lags. */
  public static String checked(VehicleSnapshot state, long now) {
    if (state == null || state.receivedAt <= 0) return "최종 확인 · 아직 없음";
    String text = "최종 확인 " + kst(state.receivedAt, now) + " KST";
    if (state.measuredAt > 0 && Math.abs(state.receivedAt - state.measuredAt) >= 60000)
      text += " · 차량 측정 " + kst(state.measuredAt, now);
    return state.fresh(now) ? text : text + " · 오래된 정보";
  }

  private static String kst(long time, long now) {
    java.time.Instant at = java.time.Instant.ofEpochMilli(time);
    boolean today =
        at.atZone(java.time.ZoneId.of("Asia/Seoul"))
            .toLocalDate()
            .equals(
                java.time.Instant.ofEpochMilli(now)
                    .atZone(java.time.ZoneId.of("Asia/Seoul"))
                    .toLocalDate());
    return (today ? CLOCK : DAY_CLOCK).format(at);
  }

  private static final java.time.format.DateTimeFormatter SHORT =
      java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm", java.util.Locale.KOREA)
          .withZone(java.time.ZoneId.of("Asia/Seoul"));

  /** Header line under the door state, e.g. "업데이트 10/9 20:12" (KST). */
  public static String updated(VehicleSnapshot s, long now) {
    if (s == null || s.receivedAt <= 0) return "업데이트 대기";
    // Non-breaking space keeps "10/9 16:02" together when a narrow phone wraps the line.
    return "업데이트 "
        + SHORT.format(java.time.Instant.ofEpochMilli(s.receivedAt)).replace(' ', ' ')
        + (s.fresh(now) ? "" : " · 오래됨");
  }

  /** Big door title; stale/unknown never reads as locked. */
  public static String doorTitle(VehicleSnapshot s, long now) {
    String door = vehicle(s, now)[0];
    return door.equals("도어 잠김") ? "잠겨 있음" : door.equals("도어 열림") ? "열려 있음" : "도어 미확인";
  }

  /** Battery shows the last reading up to 15 minutes old (it changes slowly). */
  public static String battery(VehicleSnapshot s, long now) {
    if (s == null || s.battery == null || s.receivedAt <= 0 || now - s.receivedAt > CHARGE_MAX_AGE_MS)
      return "--%";
    return Math.round(s.battery) + "%";
  }

  static final long CHARGE_MAX_AGE_MS = 15 * 60 * 1000;

  /**
   * Null unless the last status says charging (state 1) and was received within 15 minutes.
   * Returns {state, battery, remaining, completion}. Completion = vehicle measurement time +
   * reported time-to-full, so the countdown keeps moving between reads.
   */
  public static String[] charging(VehicleSnapshot s, long now) {
    if (s == null || !s.charging() || s.receivedAt <= 0 || now - s.receivedAt > CHARGE_MAX_AGE_MS)
      return null;
    String state = s.fresh(now) ? "충전 중" : "충전 중 · " + Math.max(1, (now - s.receivedAt) / 60000) + "분 전 정보";
    String battery =
        s.battery == null ? "배터리 미확인" : "배터리 " + Math.round(s.battery) + "%"
            + (s.range == null ? "" : " · 주행 가능 " + Math.round(s.range) + " km");
    long base = s.measuredAt > 0 ? s.measuredAt : s.receivedAt;
    if (s.chargeUnderOneMinute) return new String[] {state, battery, "남은 시간 1분 미만", "완료 예상 곧"};
    if (s.chargeMinutes == null)
      return new String[] {state, battery, "남은 시간 미확인", "완료 예상 미확인"};
    long done = base + s.chargeMinutes * 60000L;
    long left = Math.max(0, (done - now + 59999) / 60000);
    String remaining =
        left == 0
            ? "남은 시간 1분 미만"
            : "남은 시간 " + (left >= 60 ? left / 60 + "시간 " + (left % 60 > 0 ? left % 60 + "분" : "") : left + "분");
    String at = kst(done, now);
    return new String[] {
      state, battery, remaining.trim(), "완료 예상 " + at.substring(0, at.length() - 3) + " KST"
    };
  }

  public static String brief(String message) {
    if (message == null || message.isEmpty()) return "차량 상태 확인 대기";
    if (message.startsWith("저장된 계정과 차량을 복원")) return "저장된 연결 복원 완료";
    if (message.startsWith("BYD Sub 계정을 연결")) return "설정에서 계정을 연결하세요";
    if (message.startsWith("관찰 시작")) return "거리 관찰 시작";
    if (message.startsWith("감도·대기 시간 저장")) return "감도 저장 · 상태 갱신 중";
    if (message.equals("잠금 해제 완료 · 차량 상태 확인됨")) return "도어 열림 확인";
    if (message.equals("도어 잠금 완료 · 차량 상태 확인됨")) return "도어 잠김 확인";
    if (message.startsWith("잠금 해제 · 접근 중 조회")) return "도어 열기 준비 완료";
    return message.replace("잠금 해제", "도어 열기").replace('\n', ' ');
  }
}
