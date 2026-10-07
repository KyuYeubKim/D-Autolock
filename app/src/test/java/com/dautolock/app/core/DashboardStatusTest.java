package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class DashboardStatusTest {
  private final long now = 1791500000000L;

  private JSONObject state() throws Exception {
    JSONObject data = new JSONObject().put("time", now).put("powerGear", 3);
    for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"})
      data.put(side + "DoorLock", 2).put(side + "Window", 1);
    return data;
  }

  @Test
  public void onlyThreeRequestedStatesAreShownAndOneOpenWindowCountsAsOpen() throws Exception {
    assertArrayEquals(
        new String[] {"도어 잠김", "시동 켜짐", "창문 닫힘"},
        DashboardStatus.vehicle(new VehicleSnapshot(state(), now), now));
    JSONObject d = state().put("leftFrontWindow", 2).put("powerGear", 1);
    for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"})
      d.put(side + "DoorLock", 1);
    assertArrayEquals(
        new String[] {"도어 열림", "시동 꺼짐", "창문 열림"},
        DashboardStatus.vehicle(new VehicleSnapshot(d, now), now));
  }

  @Test
  public void staleAndUnknownTelemetryNeverAppearsAsOffOrClosed() throws Exception {
    String[] unknown = {"도어 미확인", "시동 미확인", "창문 미확인"};
    assertArrayEquals(unknown, DashboardStatus.vehicle(null, now));
    assertArrayEquals(
        unknown,
        DashboardStatus.vehicle(new VehicleSnapshot(state().put("time", now - 31000), now), now));
    assertArrayEquals(
        unknown,
        DashboardStatus.vehicle(new VehicleSnapshot(new JSONObject().put("time", now), now), now));
  }

  @Test
  public void checkedTimeIsKoreaTimeAndMarksLaggingOrStaleTelemetry() throws Exception {
    assertEquals("최종 확인 · 아직 없음", DashboardStatus.checked(null, now));
    assertEquals(
        "최종 확인 07:53:20 KST", DashboardStatus.checked(new VehicleSnapshot(state(), now), now));
    assertEquals(
        "최종 확인 07:53:20 KST · 차량 측정 07:51:20 · 오래된 정보",
        DashboardStatus.checked(
            new VehicleSnapshot(state().put("time", now - 120000), now), now));
    long yesterday = now - 86400000L;
    assertEquals(
        "최종 확인 10/08 07:53:20 KST · 오래된 정보",
        DashboardStatus.checked(
            new VehicleSnapshot(state().put("time", yesterday), yesterday), now));
  }

  @Test
  public void summaryShortensRoutineStatusButKeepsFailureReason() {
    assertEquals("도어 열림 확인", DashboardStatus.brief("잠금 해제 완료 · 차량 상태 확인됨"));
    assertEquals("감도 저장 · 상태 갱신 중", DashboardStatus.brief("감도·대기 시간 저장 · 새 기준으로 관찰을 다시 시작합니다"));
    assertTrue(DashboardStatus.brief("제어 보류: 정차 상태를 확인하지 못했습니다").contains("정차"));
  }
}
