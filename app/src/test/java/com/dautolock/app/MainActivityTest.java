package com.dautolock.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.DialogInterface;
import android.os.Looper;
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
      assertNotNull(find(decor, "READY · 공조 2초 동작 연동"));
      assertNotNull(find(decor, "진단 로그 파일 저장"));
      assertNotNull(find(decor, "자동 해제 후 문 열림 → 공조 2초"));
    }
  }

  @Test
  public void missingCapabilityCacheDoesNotDisableAutomaticSwitch() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      long until = System.currentTimeMillis() + 3000;
      while (c.initializing && System.currentTimeMillis() < until) Thread.sleep(10);
      assertFalse(c.initializing);
      c.cloud.protocol.setSignToken("test-session");
      c.vin = "test-vehicle";
      c.monitoring = true;
      c.capabilities = new org.json.JSONObject();
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      Switch toggle = (Switch) find(a.get().getWindow().getDecorView(), "실제 자동 도어 제어");
      assertTrue(toggle.isEnabled());
      toggle.performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(dialog);
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(c.autoEnabled);
      assertTrue(toggle.isChecked());
      c.auto(false);
    }
  }

  @Test
  public void manualDoorTapDoesNotShowConfirmation() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Button unlock = (Button) find(a.get().getWindow().getDecorView(), "도어 잠금 해제");
      unlock.setEnabled(true);
      unlock.performClick();
      assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
  }
}
