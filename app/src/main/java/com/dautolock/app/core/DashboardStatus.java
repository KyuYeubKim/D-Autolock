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
