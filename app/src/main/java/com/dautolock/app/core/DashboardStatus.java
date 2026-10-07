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
