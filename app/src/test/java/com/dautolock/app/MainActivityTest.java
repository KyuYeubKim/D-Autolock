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
  @Test
  public void livePreviewComparesAverageWithDraftThresholdsAndPresetSavesOnlyOnApply()
      throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.monitoring = true;
      c.averageRssi = -59.6;
      find(a.get().getWindow().getDecorView(), "거리 감도 / 대기 시간").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View decor = d.getWindow().getDecorView();
      TextView preview = decor.findViewWithTag("thresholdPreview");
      SeekBar near = decor.findViewWithTag("near");
      near.setProgress(-40 + 92);
      assertTrue(preview.getText().toString().contains("기준 미충족"));
      find(decor, "시작값 적용 · −60 / −75 dBm").performClick();
      assertTrue(preview.getText().toString().contains("해제 신호 기준 충족"));
      assertTrue(preview.getText().toString().contains("-59.6 dBm"));
      assertFalse(c.settings.contains("near"));
      c.averageRssi = -78;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(preview.getText().toString().contains("잠금 신호 기준 충족"));
      c.averageRssi = Double.NaN;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(preview.getText().toString().contains("현재 평균 —"));
      d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      assertEquals(-60, c.settings.getInt("near", 0));
      assertEquals(-75, c.settings.getInt("far", 0));
      assertEquals(1, c.settings.getInt("nearWaitSeconds", 0));
      assertEquals(4, c.settings.getInt("farWaitSeconds", 0));
      assertEquals(10, c.settings.getInt("lossLockSeconds", 0));
      assertFalse(c.monitoring);
      assertFalse(c.autoEnabled);
    }
  }

  @Test
  public void sensitivitySlidersKeepGapAndSaveTimesUsedByEngine() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      find(a.get().getWindow().getDecorView(), "거리 감도 / 대기 시간").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View decor = dialog.getWindow().getDecorView();
      java.util.List<EditText> inputs = new java.util.ArrayList<>();
      fields(decor, inputs);
      assertTrue(inputs.isEmpty());
      SeekBar near = decor.findViewWithTag("near"), far = decor.findViewWithTag("far");
      near.setProgress(0); // -92; far is pushed to -100 to maintain separation.
      assertEquals(0, far.getProgress());
      far.setProgress(62); // -38; near moves to -30.
      assertEquals(62, near.getProgress());
      dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
      ((SeekBar) decor.findViewWithTag("nearWait")).setProgress(1);
      ((SeekBar) decor.findViewWithTag("farWait")).setProgress(4);
      ((SeekBar) decor.findViewWithTag("lossWait")).setProgress(15); // 20 seconds.
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      assertEquals(-65, c.settings.getInt("near", 0));
      assertEquals(-80, c.settings.getInt("far", 0));
      assertEquals(1, c.settings.getInt("nearWaitSeconds", 0));
      assertEquals(4, c.settings.getInt("farWaitSeconds", 0));
      assertEquals(20, c.settings.getInt("lossLockSeconds", 0));
      com.dautolock.app.core.ProximityEngine e = c.proximityEngine();
      e.sample(-50, 0);
      e.sample(-50, 200);
      e.sample(-50, 400);
      e.sample(-50, 1000);
      assertEquals(com.dautolock.app.core.ProximityEngine.Action.UNLOCK, e.pending(1000));
      assertTrue(e.diagnostic(1000).contains("lossLockMs=20000"));
    }
  }

  @Test
  public void cancellingSliderDialogDoesNotChangeSettings() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      find(a.get().getWindow().getDecorView(), "거리 감도 / 대기 시간").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      ((SeekBar) dialog.getWindow().getDecorView().findViewWithTag("nearWait")).setProgress(0);
      dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      assertEquals(3, c.settings.getInt("nearWaitSeconds", 3));
      assertFalse(c.settings.contains("nearWaitSeconds"));
    }
  }

  private void fields(View v, java.util.List<EditText> result) {
    if (v instanceof EditText) result.add((EditText) v);
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) fields(group.getChildAt(i), result);
    }
  }

  @Test
  public void savedLoginShowsIdAndMaskedPlaceholdersWithoutExposingSecrets() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.loginUser = "sub@example.com";
      c.pinHash = "test-pin-hash";
      java.lang.reflect.Field password = Controller.class.getDeclaredField("loginPassword");
      password.setAccessible(true);
      password.set(c, "test-secret");
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      find(a.get().getWindow().getDecorView(), "Sub 계정 로그인 / 변경").performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(dialog);
      java.util.List<EditText> inputs = new java.util.ArrayList<>();
      fields(dialog.getWindow().getDecorView(), inputs);
      assertEquals(3, inputs.size());
      assertEquals("sub@example.com", inputs.get(0).getText().toString());
      assertEquals("", inputs.get(1).getText().toString());
      assertTrue(inputs.get(1).getHint().toString().startsWith("********"));
      assertTrue(inputs.get(2).getHint().toString().contains("PIN 저장됨"));
      assertTrue(
          inputs.get(1).getTransformationMethod()
              instanceof android.text.method.PasswordTransformationMethod);
      assertFalse(inputs.get(1).isSaveEnabled());
      assertFalse(inputs.get(2).isSaveEnabled());
      assertNotEquals(
          0, dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
      inputs.get(0).setText("another@example.com");
      assertEquals("BYD 로그인 비밀번호", inputs.get(1).getHint().toString());
      assertEquals("BYD 원격 제어 PIN (6자리)", inputs.get(2).getHint().toString());
      dialog.dismiss();
    }
  }

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
