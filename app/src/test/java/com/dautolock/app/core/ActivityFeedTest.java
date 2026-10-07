package com.dautolock.app.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ActivityFeedTest {
  private final long now = 1791500000000L; // 2026-10-09 07:53:20 KST

  @Test
  public void failureAndWaitingWinOverSuccessWords() {
    assertEquals(ActivityFeed.Kind.SUCCESS, ActivityFeed.kind("도어 잠금 완료 · 차량 상태 확인됨"));
    assertEquals(
        ActivityFeed.Kind.FAILURE, ActivityFeed.kind("최신 상태를 확인하지 못했습니다 · 자동 제어 보류"));
    assertEquals(ActivityFeed.Kind.WAIT, ActivityFeed.kind("자동 시작 보류 · 앱에서 주변 기기 권한과 블루투스를 확인하세요"));
    assertEquals(ActivityFeed.Kind.WAIT, ActivityFeed.kind("이전 요청을 처리 중입니다"));
    assertEquals(ActivityFeed.Kind.INFO, ActivityFeed.kind("거리 관찰을 종료했습니다"));
  }

  @Test
  public void doorWordingMatchesDashboard() {
    assertEquals("도어 열기 완료 · 차량 상태 확인됨", ActivityFeed.friendly("잠금 해제 완료\n· 차량 상태 확인됨"));
  }

  @Test
  public void repeatedMessagesFoldAndLimitApplies() {
    List<ActivityFeed.Entry> raw =
        Arrays.asList(
            new ActivityFeed.Entry(now, "이전 요청을 처리 중입니다"),
            new ActivityFeed.Entry(now - 1000, "이전 요청을 처리 중입니다"),
            new ActivityFeed.Entry(now - 2000, "도어 잠금 완료"),
            new ActivityFeed.Entry(now - 3000, "거리 관찰 시작"),
            new ActivityFeed.Entry(now - 4000, "차량 상태 업데이트 완료"));
    List<ActivityFeed.Entry> rows = ActivityFeed.collapse(raw, 2);
    assertEquals(2, rows.size());
    assertEquals(2, rows.get(0).count);
    assertEquals(now, rows.get(0).time);
    assertEquals("도어 잠금 완료", rows.get(1).text);
  }

  @Test
  public void timesAreKoreaTimeWithRelativeHint() {
    assertEquals("07:53:20 KST · 방금", ActivityFeed.time(now, now));
    assertEquals("07:48:20 KST · 5분 전", ActivityFeed.time(now - 300000, now));
    assertEquals("10/08 07:53 KST", ActivityFeed.time(now - 86400000L, now));
  }
}
