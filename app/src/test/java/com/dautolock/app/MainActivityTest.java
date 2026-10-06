package com.dautolock.app;

import static org.junit.Assert.*;

import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
public class MainActivityTest {
  private TextView find(View v, String text) {
    if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView r = find(g.getChildAt(i), text);
        if (r != null) return r;
      }
    }
    return null;
  }

  @Test
  public void homeInflatesAndCannotSendCommandsBeforeSetup() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> c =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View decor = c.get().getWindow().getDecorView();
      assertNotNull(find(decor, "D-Autolock"));
      assertNotNull(find(decor, "블루투스 기기 검색 / 선택"));
      assertNotNull(find(decor, "Sub 계정 로그인 / 변경"));
      assertFalse(find(decor, "도어 잠금 해제").isEnabled());
      assertFalse(find(decor, "Stop · 차량 종료 검증").isEnabled());
      assertFalse(((Switch) find(decor, "실제 자동 도어 제어")).isChecked());
      assertNotNull(find(decor, "자동 READY · 지원 확인 필요"));
    }
  }
}
