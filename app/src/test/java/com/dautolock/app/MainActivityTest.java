package com.dautolock.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
public class MainActivityTest {
  @Test
  public void optionalBridgeCanBeSkippedAndGuideCompletionExplicitlyStartsAutomaticControl()
      throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.settings
          .edit()
          .putBoolean("setupRequired", true)
          .putBoolean("setupComplete", false)
          .putBoolean("setupIntroSeen", true)
          .putInt("setupStep", 5)
          .putString("address", "AA:BB:CC:DD:EE:FF")
          .commit();
      c.vin = "test-car";
      c.pinHash = "test-pin";
      c.cloud.protocol.setSignToken("test-session");
      org.robolectric.Shadows.shadowOf(a.get().getApplication())
          .grantPermissions(
              android.Manifest.permission.BLUETOOTH_SCAN,
              android.Manifest.permission.BLUETOOTH_CONNECT,
              android.Manifest.permission.ACCESS_FINE_LOCATION,
              android.Manifest.permission.ACCESS_COARSE_LOCATION,
              android.Manifest.permission.POST_NOTIFICATIONS);
      View root = a.get().getWindow().getDecorView();
      root.findViewWithTag("overflowMenu").performClick();
      PopupMenu popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();
      org.robolectric.Shadows.shadowOf(popup)
          .getOnMenuItemClickListener()
          .onMenuItemClick(popup.getMenu().findItem(2));
      assertNotNull(find(root, "건너뛰기 · 다음"));
      root.findViewWithTag("setupNext").performClick();
      assertNotNull(find(root, "STEP 7 / 7"));
      root.findViewWithTag("setupNext").performClick();
      assertTrue(c.settings.getBoolean("setupComplete", false));
      assertEquals(View.VISIBLE, root.findViewWithTag("homePage").getVisibility());
      android.content.Intent start =
          org.robolectric.Shadows.shadowOf(a.get().getApplication()).getNextStartedService();
      assertNotNull(start);
      assertTrue(start.getBooleanExtra("automatic", false));
      c.stop();
    }
  }

  @Before
  public void noNetworkUpdateChecks() {
    org.robolectric.RuntimeEnvironment.getApplication()
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("autoUpdate", false)
        .putBoolean("setupRequired", false)
        .commit();
  }

  @Test
  public void overflowSettingsMovesAllRequestedSectionsAndBackReturnsHome() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = a.get().getWindow().getDecorView();
      ViewGroup home = root.findViewWithTag("homePage");
      View settings = root.findViewWithTag("settingsPage");
      String[] moved = {
        "차량 보조 앱 연결 / QR",
        "자동 도어",
        "자동 동작 설정",
        "앱 업데이트",
        "01  BYD AUTO 계정",
        "02  차량 블루투스",
        "알림 · 진단 로그 관리"
      };
      for (String label : moved) {
        assertNull(find(home, label));
        assertNotNull(find(settings, label));
      }
      assertNotNull(home.findViewWithTag("statusRefresh"));
      assertNotNull(find(home, "활동 로그"));
      assertNull(find(settings, "활동 로그"));
      root.findViewWithTag("overflowMenu").performClick();
      PopupMenu popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();
      assertEquals("설정", popup.getMenu().findItem(1).getTitle());
      org.robolectric.Shadows.shadowOf(popup)
          .getOnMenuItemClickListener()
          .onMenuItemClick(popup.getMenu().findItem(1));
      assertEquals(View.VISIBLE, settings.getVisibility());
      assertEquals(View.GONE, home.getVisibility());
      a.get().onBackPressed();
      assertEquals(View.VISIBLE, home.getVisibility());
      assertEquals(View.GONE, settings.getVisibility());
    }
  }

  @Test
  public void signalPlusExpandsAndCollapsesWithoutChangingAutomation() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = a.get().getWindow().getDecorView();
      View extra = root.findViewWithTag("signalExtra");
      assertEquals(View.GONE, extra.getVisibility());
      root.findViewWithTag("signalMore").performClick();
      assertEquals(View.VISIBLE, extra.getVisibility());
      root.findViewWithTag("signalMore").performClick();
      assertEquals(View.GONE, extra.getVisibility());
      assertFalse(((DApplication) a.get().getApplication()).controller().autoEnabled);
    }
  }

  @Test
  public void firstInstallGuideCanPauseAndResumeAndDoesNotAutomaticallyControlDoors()
      throws Exception {
    org.robolectric.RuntimeEnvironment.getApplication()
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("setupRequired", true)
        .putBoolean("setupComplete", false)
        .remove("setupIntroSeen")
        .commit();
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      View root = a.get().getWindow().getDecorView();
      assertEquals(View.VISIBLE, root.findViewWithTag("setupPage").getVisibility());
      assertNotNull(find(root, "STEP 1 / 7"));
      assertFalse(root.findViewWithTag("setupNext").isEnabled());
      org.robolectric.Shadows.shadowOf(a.get().getApplication())
          .grantPermissions(
              android.Manifest.permission.BLUETOOTH_SCAN,
              android.Manifest.permission.BLUETOOTH_CONNECT,
              android.Manifest.permission.ACCESS_FINE_LOCATION);
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      root.findViewWithTag("setupNext").performClick();
      assertNotNull(find(root, "STEP 2 / 7"));
      a.get().onBackPressed();
      find(root.findViewWithTag("homePage"), "처음 설정 이어하기").performClick();
      assertNotNull(find(root, "STEP 2 / 7"));
      assertFalse(c.autoEnabled);
    }
  }

  @Test
  public void dashboardUsesRequestedCardOrder() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = a.get().getWindow().getDecorView();
      View alerts = root.findViewWithTag("alertsCard"),
          signal = root.findViewWithTag("signalCard"),
          controls = root.findViewWithTag("controlsCard"),
          logs = root.findViewWithTag("logCard");
      ViewGroup parent = (ViewGroup) alerts.getParent();
      assertTrue(parent.indexOfChild(alerts) < parent.indexOfChild(signal));
      assertTrue(parent.indexOfChild(signal) < parent.indexOfChild(controls));
      assertSame(parent, logs.getParent());
      assertTrue(parent.indexOfChild(controls) < parent.indexOfChild(logs));
      assertEquals(View.GONE, root.findViewWithTag("settingsPage").getVisibility());
      assertNotNull(signal.findViewWithTag("thresholdEdit"));
      assertNotNull(find(controls, "도어 열기"));
    }
  }

  @Test
  public void restoredSetupStartsAutomaticServiceAndSettingsReconfigureIt() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      org.robolectric.Shadows.shadowOf(a.get().getApplication())
          .grantPermissions(
              android.Manifest.permission.BLUETOOTH_SCAN,
              android.Manifest.permission.BLUETOOTH_CONNECT,
              android.Manifest.permission.ACCESS_FINE_LOCATION,
              android.Manifest.permission.ACCESS_COARSE_LOCATION,
              android.Manifest.permission.POST_NOTIFICATIONS);
      c.vin = "test-car";
      c.pinHash = "test-pin";
      c.cloud.protocol.setSignToken("test-session");
      c.settings.edit().putString("address", "AA:BB:CC:DD:EE:FF").commit();
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      android.content.Intent start =
          org.robolectric.Shadows.shadowOf(a.get().getApplication()).getNextStartedService();
      assertNotNull(start);
      assertTrue(start.getBooleanExtra("automatic", false));
      c.thresholds(-60, -75, 1, 4, 10);
      android.content.Intent reconfigure =
          org.robolectric.Shadows.shadowOf(a.get().getApplication()).getNextStartedService();
      assertEquals("RECONFIGURE", reconfigure.getAction());
      assertTrue(reconfigure.getBooleanExtra("automatic", false));
    }
  }

  @Test
  public void livePreviewComparesAverageWithDraftThresholdsAndPresetSavesOnlyOnApply()
      throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.monitoring = true;
      c.averageRssi = -59.6;
      a.get().getWindow().getDecorView().findViewWithTag("thresholdEdit").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View decor = d.getWindow().getDecorView();
      TextView preview = decor.findViewWithTag("thresholdPreview");
      SeekBar near = decor.findViewWithTag("near");
      near.setProgress(-40 + 92);
      assertTrue(preview.getText().toString().contains("기준 미충족"));
      find(decor, "추천값 적용 · −70 / −85 dBm").performClick();
      assertTrue(preview.getText().toString().contains("해제 신호 기준 충족"));
      assertTrue(preview.getText().toString().contains("-59.6 dBm"));
      assertFalse(c.settings.contains("near"));
      c.averageRssi = -88;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(preview.getText().toString().contains("잠금 신호 기준 충족"));
      c.averageRssi = Double.NaN;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(preview.getText().toString().contains("현재 평균 —"));
      d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      assertEquals(-70, c.settings.getInt("near", 0));
      assertEquals(-85, c.settings.getInt("far", 0));
      assertEquals(1, c.settings.getInt("nearWaitSeconds", 0));
      assertEquals(5, c.settings.getInt("farWaitSeconds", 0));
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
      a.get().getWindow().getDecorView().findViewWithTag("thresholdEdit").performClick();
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
      assertEquals(-70, c.settings.getInt("near", 0));
      assertEquals(-85, c.settings.getInt("far", 0));
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
      a.get().getWindow().getDecorView().findViewWithTag("thresholdEdit").performClick();
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

  private Button button(View v, String text) {
    if (v instanceof Button && text.contentEquals(((Button) v).getText())) return (Button) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        Button b = button(((ViewGroup) v).getChildAt(i), text);
        if (b != null) return b;
      }
    return null;
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
      assertFalse(find(decor, "도어 열기").isEnabled());
      assertFalse(decor.findViewWithTag("control_power_off").isEnabled());
      assertFalse(decor.findViewWithTag("control_prepare").isEnabled());
      assertFalse(((Switch) find(decor, "실제 자동 도어 제어")).isChecked());
      assertNotNull(find(decor, "탑승 공조 · READY 상태 진단"));
      assertNotNull(find(decor, "진단 로그 파일 저장"));
      assertNotNull(find(decor, "탑승 시 공조 시작"));
      assertNotNull(find(decor, "시동 켜기"));
      assertNotNull(find(decor, "출차 준비"));
      assertNotNull(find(decor, "하차 마무리"));
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
  public void controlsNeedSecondTapWithinThreeSecondsAndStopKeepsConfirmation() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = a.get().getWindow().getDecorView();
      View stop = root.findViewWithTag("control_power_off");
      stop.setEnabled(true);
      stop.performClick();
      assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
      assertNotNull(find(stop, "한 번 더"));
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
          .idleFor(java.time.Duration.ofMillis(3100));
      assertNotNull(find(stop, "시동 끄기"));
      stop.performClick(); // Re-armed after timeout, still no action.
      assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
      View on = root.findViewWithTag("control_power_on");
      on.setEnabled(true);
      on.performClick(); // Arming another control disarms the first.
      assertNotNull(find(stop, "시동 끄기"));
      stop.performClick();
      stop.performClick();
      AlertDialog confirm = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(confirm);
      assertNotNull(find(stop, "시동 끄기"));
      confirm.dismiss();
      View prepare = root.findViewWithTag("control_prepare");
      prepare.setEnabled(true);
      prepare.performClick();
      assertNotNull(find(prepare, "한 번 더 누르세요"));
      prepare.performClick(); // 출차 준비 asks for parking confirmation before powering on.
      assertNotSame(confirm, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
  }

  @Test
  public void automationSwitchOffStopsEverythingAndRemembersIt() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      Switch automation = root.findViewWithTag("automationSwitch");
      c.monitoring = c.autoEnabled = true;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(automation.isChecked());
      automation.setChecked(false);
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertFalse(c.monitoring);
      assertFalse(c.autoEnabled);
      assertFalse(c.settings.getBoolean("autoStart", true));
      assertTrue(
          ((TextView) root.findViewWithTag("automationReason")).getText().toString().startsWith("꺼짐"));
      assertTrue(c.message.contains("모두 중단"));
      for (String tag :
          new String[] {
            "control_prepare", "control_finish", "control_unlock", "control_lock",
            "control_power_on", "control_power_off", "doorTile", "batteryItem", "powerItem",
            "windowItem", "statusRefresh", "thresholdEdit", "activityAll", "diagnosticsOpen"
          }) assertNotNull(tag, root.findViewWithTag(tag));
    }
  }

  @Test
  public void refreshIconShowsCheckedTimeAndSpinsWhileReading() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      TextView checked = root.findViewWithTag("statusCheckedTime");
      View icon = root.findViewWithTag("statusRefresh");
      assertEquals("업데이트 대기", checked.getText().toString());
      icon.performClick(); // No vehicle: explains instead of reading.
      assertEquals("설정에서 BYD 계정과 차량을 먼저 연결하세요", c.message);
      c.statusReading = true;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(checked.getText().toString().startsWith("확인 중…"));
      assertEquals("차량 상태 새로고침 중", icon.getContentDescription());
      c.statusReading = false;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals("차량 상태 새로고침", icon.getContentDescription());
      ViewGroup rows = root.findViewWithTag("activityRows");
      assertNotNull(find(rows, "설정에서 BYD 계정과 차량을 먼저 연결하세요"));
      assertNotNull(find(rows, "보류"));
    }
  }

  @Test
  public void sharedModeDisablesAutomaticStopSwitchAndDelayDefaultsToFifteenSeconds() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = a.get().getWindow().getDecorView();
      Switch stop = (Switch) find(root, "자동 잠금 후 차량 전원 종료");
      assertTrue(stop.isEnabled());
      assertNotNull(find(root, "자동 종료 대기 시간 · 15초"));
      ((Switch) root.findViewWithTag("sharedVehicle")).setChecked(true);
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertFalse(stop.isEnabled());
      assertEquals(View.GONE, root.findViewWithTag("cancelStop").getVisibility());
      ((Switch) root.findViewWithTag("sharedVehicle")).setChecked(false);
    }
  }

  @Test
  public void chargingCardAppearsAboveVehicleStatusOnlyWhileCharging() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      View card = root.findViewWithTag("chargeCard");
      assertEquals(View.GONE, card.getVisibility());
      long now = System.currentTimeMillis();
      org.json.JSONObject data =
          new org.json.JSONObject()
              .put("time", now)
              .put("chargeState", 1)
              .put("elecPercent", 80)
              .put("fullHour", 0)
              .put("fullMinute", 30);
      c.snapshot = new com.dautolock.app.core.VehicleSnapshot(data, now);
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.VISIBLE, card.getVisibility());
      ViewGroup parent = (ViewGroup) card.getParent();
      assertTrue(parent.indexOfChild(card) < parent.indexOfChild(root.findViewWithTag("alertsCard")));
      assertEquals("⚡ 충전 중", ((TextView) root.findViewWithTag("chargeHeadline")).getText().toString());
      String detail = ((TextView) root.findViewWithTag("chargeDetail")).getText().toString();
      assertTrue(detail.contains("배터리 80%"));
      assertTrue(detail.contains("남은 시간 30분"));
      assertTrue(detail.contains("완료 예상"));
      c.snapshot = new com.dautolock.app.core.VehicleSnapshot(data.put("chargeState", 0), now);
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.GONE, card.getVisibility());
    }
  }

  @Test
  public void qrScanOpensOwnScannerAndScannerStartsWithoutAndroidX() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.vin = "test-car";
      org.robolectric.Shadows.shadowOf(a.get().getApplication())
          .grantPermissions(
              android.Manifest.permission.BLUETOOTH_SCAN,
              android.Manifest.permission.BLUETOOTH_CONNECT,
              android.Manifest.permission.ACCESS_FINE_LOCATION,
              android.Manifest.permission.ACCESS_COARSE_LOCATION);
      button(a.get().getWindow().getDecorView(), "차량 보조 앱 연결 / QR").performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      find(dialog.getWindow().getDecorView(), "차량 화면의 QR 스캔").performClick();
      // Older Android may first launch a system intent (e.g. Bluetooth enable); find the scanner.
      android.content.Intent started;
      do started = org.robolectric.Shadows.shadowOf(a.get()).getNextStartedActivity();
      while (started != null && started.getComponent() == null);
      assertNotNull(started);
      assertEquals(BridgeQrActivity.class.getName(), started.getComponent().getClassName());
      // Camera not yet allowed: the scanner asks for it instead of crashing.
      try (org.robolectric.android.controller.ActivityController<BridgeQrActivity> scan =
          Robolectric.buildActivity(BridgeQrActivity.class, started).setup()) {
        assertFalse(scan.get().isFinishing());
      }
      c.vin = "";
    }
  }

  @Test
  public void mainScreenAllowsScreenCaptureButLoginDialogStaysProtected() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      assertEquals(
          0, a.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      find(a.get().getWindow().getDecorView(), "Sub 계정 로그인 / 변경").performClick();
      AlertDialog login = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotEquals(
          0, login.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
      login.dismiss();
    }
  }

  @Test
  public void manualDoorTapDoesNotShowConfirmation() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View unlock = a.get().getWindow().getDecorView().findViewWithTag("control_unlock");
      unlock.setEnabled(true);
      unlock.performClick();
      unlock.performClick();
      assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
  }
}
