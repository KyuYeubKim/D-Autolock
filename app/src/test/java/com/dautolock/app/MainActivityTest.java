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
  public void settingsMenuGroupsSectionsIntoPagesAndBackWalksUp() {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = a.get().getWindow().getDecorView();
      ViewGroup home = root.findViewWithTag("homePage");
      View settings = root.findViewWithTag("settingsPage");
      String[][] rows = {
        {"account", "BYD AUTO 계정"},
        {"bluetooth", "차량 블루투스"},
        {"bridge", "차량 보조 앱 연결 / QR"},
        {"auto", "자동 도어 · 거리 관찰"},
        {"trip", "탑승·하차 동작"},
        {"notify", "알림 및 백그라운드"},
        {"logs", "진단 로그"},
        {"update", "앱 업데이트"}
      };
      for (String[] row : rows) {
        View page = root.findViewWithTag("settings_" + row[0]);
        assertNull(row[1], find(home, row[1]));
        assertNotNull(row[1], find(page, row[1]));
        assertNotNull(row[0], settings.findViewWithTag("menu_" + row[0]));
      }
      for (String group : new String[] {"연결", "자동화", "앱 관리"})
        assertNotNull(group, find(settings, group));
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
      settings.findViewWithTag("menu_account").performClick();
      View account = root.findViewWithTag("settings_account");
      assertEquals(View.VISIBLE, account.getVisibility());
      assertEquals(View.GONE, settings.getVisibility());
      assertNotNull(find(account, "Sub 계정 로그인 / 변경"));
      a.get().onBackPressed(); // Sub-page → settings menu.
      assertEquals(View.VISIBLE, settings.getVisibility());
      assertEquals(View.GONE, account.getVisibility());
      a.get().onBackPressed(); // Settings → home.
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
  public void livePreviewComparesAverageWithDraftThresholdsAndPresetSavesOnlyOnSave()
      throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      c.monitoring = true;
      c.averageRssi = -59.6;
      View root = a.get().getWindow().getDecorView();
      root.findViewWithTag("thresholdEdit").performClick(); // The car BLE badge on the car.
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.VISIBLE, root.findViewWithTag("blePage").getVisibility());
      assertEquals(View.GONE, root.findViewWithTag("homePage").getVisibility());
      TextView preview = root.findViewWithTag("thresholdPreview");
      RangeSlider range = root.findViewWithTag("thresholdRange");
      range.moveNear(-40);
      assertTrue(preview.getText().toString().contains("기준 미충족"));
      find(root, "추천값 적용 · −78 / −83 dBm").performClick();
      assertTrue(preview.getText().toString().contains("해제 신호 기준 충족"));
      assertTrue(preview.getText().toString().contains("−59.6 dBm"));
      assertFalse(c.settings.contains("near"));
      c.averageRssi = -88;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(preview.getText().toString().contains("잠금 신호 기준 충족"));
      c.averageRssi = Double.NaN;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(preview.getText().toString().contains("현재 평균 —"));
      root.findViewWithTag("bleSave").performClick();
      assertEquals(-78, c.settings.getInt("near", 0));
      assertEquals(-83, c.settings.getInt("far", 0));
      assertEquals(1, c.settings.getInt("nearWaitSeconds", 0));
      assertEquals(5, c.settings.getInt("farWaitSeconds", 0));
      assertEquals(10, c.settings.getInt("lossLockSeconds", 0));
      assertFalse(c.monitoring);
      assertFalse(c.autoEnabled);
      assertEquals(View.GONE, root.findViewWithTag("blePage").getVisibility());
    }
  }

  @Test
  public void thresholdHandlesKeepGapAndSaveTimesUsedByEngine() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      root.findViewWithTag("thresholdEdit").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      View page = root.findViewWithTag("blePage");
      java.util.List<EditText> inputs = new java.util.ArrayList<>();
      fields(page, inputs);
      assertTrue(inputs.isEmpty()); // Handles and steppers only, no typing.
      RangeSlider range = page.findViewWithTag("thresholdRange");
      range.moveNear(-92); // The lock handle is pushed to -97 (5 dB gap).
      assertEquals(-97, range.far());
      assertEquals("−97", ((TextView) page.findViewWithTag("farValue")).getText().toString());
      range.moveFar(-45); // The unlock handle moves to -40, the strong end of the track.
      assertEquals(-40, range.near());
      page.findViewWithTag("nearPlus").performClick(); // Already at the end: stays.
      assertEquals(-40, range.near());
      page.findViewWithTag("farMinus").performClick();
      assertEquals(-46, range.far());
      assertTrue(
          ((TextView) page.findViewWithTag("gapInfo")).getText().toString().contains("6 dB 구간"));
      find(page, "추천값 적용 · −78 / −83 dBm").performClick();
      assertEquals(-78, range.near());
      assertEquals(-83, range.far());
      ((SeekBar) page.findViewWithTag("nearWait")).setProgress(1);
      ((SeekBar) page.findViewWithTag("farWait")).setProgress(4);
      ((SeekBar) page.findViewWithTag("lossWait")).setProgress(15); // 20 seconds.
      page.findViewWithTag("bleSave").performClick();
      assertEquals(-78, c.settings.getInt("near", 0));
      assertEquals(-83, c.settings.getInt("far", 0));
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
  public void leavingBlePageWithoutSavingDropsTheDraft() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      root.findViewWithTag("thresholdEdit").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      ((SeekBar) root.findViewWithTag("nearWait")).setProgress(0);
      RangeSlider range = root.findViewWithTag("thresholdRange");
      range.moveNear(-60);
      a.get().onBackPressed();
      assertEquals(View.GONE, root.findViewWithTag("blePage").getVisibility());
      assertEquals(3, c.settings.getInt("nearWaitSeconds", 3));
      assertFalse(c.settings.contains("nearWaitSeconds"));
      assertFalse(c.settings.contains("near"));
      root.findViewWithTag("bleStatus").performClick(); // The header pill opens the same page.
      assertEquals(View.VISIBLE, root.findViewWithTag("blePage").getVisibility());
      assertEquals(Controller.DEFAULT_NEAR, range.near()); // Reloaded, not the dropped draft.
    }
  }

  @Test
  public void beaconTabOffersAddUntilConfiguredThenSavesOnlyTheBeacon() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      assertEquals(View.VISIBLE, root.findViewWithTag("beaconAdd").getVisibility());
      assertEquals(View.GONE, root.findViewWithTag("beaconThresholdEdit").getVisibility());
      root.findViewWithTag("beaconAdd").performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      View empty = (View) find(root, "보조 비콘이 없어요").getParent();
      assertEquals(View.VISIBLE, empty.getVisibility());
      assertNotNull(root.findViewWithTag("beaconAddDevice"));
      c.settings
          .edit()
          .putString("beaconAddress", "AA:BB:CC:DD:EE:01")
          .putString("beaconName", "Holy-IOT")
          .commit();
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.GONE, empty.getVisibility());
      assertNotNull(find(root, "Holy-IOT"));
      RangeSlider beacon = root.findViewWithTag("beaconRange");
      assertEquals(Controller.DEFAULT_BEACON_NEAR, beacon.near());
      root.findViewWithTag("beaconNearMinus").performClick();
      root.findViewWithTag("bleSave").performClick();
      assertEquals(Controller.DEFAULT_BEACON_NEAR - 1, c.settings.getInt("beaconNear", 0));
      assertFalse(c.settings.contains("near")); // The untouched car BLE tab is not re-saved.
      // Saving closes the page (back to wherever it was opened from; the guide may be showing).
      assertEquals(View.GONE, root.findViewWithTag("blePage").getVisibility());
      assertEquals(View.VISIBLE, root.findViewWithTag("beaconThresholdEdit").getVisibility());
      assertEquals(View.GONE, root.findViewWithTag("beaconAdd").getVisibility());
    }
  }

  @Test
  public void carBadgesAndStatusFollowSignalBands() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      View badge = root.findViewWithTag("thresholdEdit"),
          pill = root.findViewWithTag("bleStatus"),
          state = root.findViewWithTag("signalMore");
      assertNotNull(find(badge, "꺼짐"));
      assertNotNull(find(pill, "BLE 꺼짐"));
      assertNotNull(find(state, "감지 꺼짐"));
      c.monitoring = true;
      c.averageRssi = -60; // Above the -78 unlock threshold.
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNotNull(find(badge, "−60 dBm"));
      assertNotNull(find(pill, "BLE 수신 중"));
      assertNotNull(find(state, "가까움 · 열림 구간"));
      c.averageRssi = -90; // Below the -83 lock threshold.
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNotNull(find(state, "멀어짐 · 잠금 구간"));
      c.averageRssi = Double.NaN;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNotNull(find(badge, "끊김"));
      assertNotNull(find(pill, "BLE 미연결"));
      assertNotNull(find(state, "신호 없음 · 대기 중"));
      c.monitoring = false;
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
      assertFalse(decor.findViewWithTag("control_window_close").isEnabled());
      assertFalse(decor.findViewWithTag("carHero").isEnabled()); // 차량 찾기 is the bonnet now.
      assertNull(decor.findViewWithTag("control_window_open")); // Removed in v0.3.46.
      // v0.3.60: 차량 찾기 moved to the bonnet; 원격 Ready and 주차 마무리 left the main screen.
      for (String tag : new String[] {"control_find_car", "control_prepare", "control_finish"})
        assertNull(tag, decor.findViewWithTag(tag));
      assertNotNull(find(decor, "창문 닫기"));
      assertFalse(((Switch) find(decor, "실제 자동 도어 제어")).isChecked());
      assertNotNull(find(decor, "탑승 공조 · READY 상태 진단"));
      assertNotNull(find(decor, "진단 로그 파일 저장"));
      assertNotNull(decor.findViewWithTag("diagnosticsShare"));
      assertTrue(((Switch) decor.findViewWithTag("chargeNotify")).isChecked()); // On by default.
      assertNotNull(find(decor, "탑승 시 공조 시작"));
      assertNotNull(find(decor, "시동 켜기"));
      assertNull(find(decor, "원격 Ready"));
      // All five controls share one row.
      ViewGroup row = (ViewGroup) decor.findViewWithTag("control_unlock").getParent();
      assertEquals(5, row.getChildCount());
      assertSame(row, decor.findViewWithTag("control_window_close").getParent());
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
      CarHeroView car = root.findViewWithTag("carHero");
      car.setEnabled(true);
      car.performClick(); // The bonnet arms 차량 찾기 like any other control.
      assertTrue(car.armed());
      assertTrue(car.getContentDescription().toString().contains("한 번 더 누르면 실행"));
      on.performClick(); // Arming another control disarms the bonnet.
      assertFalse(car.armed());
      assertFalse(car.getContentDescription().toString().contains("한 번 더"));
      car.performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
          .idleFor(java.time.Duration.ofMillis(3100));
      assertFalse(car.armed()); // Times out like the circles.
      car.performClick();
      car.performClick(); // Second tap within 3 s runs it and clears the mark.
      assertFalse(car.armed());
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
            "carHero", "control_unlock", "control_lock", "control_window_close",
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
  public void redSecurityBannerShowsWhileAlertIsActiveAndOffersFinish() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      View banner = root.findViewWithTag("securityAlert");
      assertEquals(View.GONE, banner.getVisibility());
      c.securityAlert = "도어가 자동으로 잠기지 않았습니다.";
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.VISIBLE, banner.getVisibility());
      ViewGroup parent = (ViewGroup) banner.getParent();
      assertEquals(0, parent.indexOfChild(banner));
      banner.performClick();
      AlertDialog finish = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(finish);
      finish.dismiss();
      c.securityAlert = null;
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.GONE, banner.getVisibility());
    }
  }

  @Test
  public void boardingClimateAutoOffEnablesAfterConfirmAndStaysOffOnCancel() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      Switch toggle = (Switch) root.findViewWithTag("climateAutoOff");
      assertFalse(toggle.isChecked());
      // Cancel: stays off, setting unchanged.
      toggle.setChecked(true);
      AlertDialog d1 = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      d1.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertFalse(toggle.isChecked());
      assertFalse(c.settings.getBoolean("climateAutoOff", false));
      // Confirm: turns on and persists.
      toggle.setChecked(true);
      AlertDialog d2 = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      d2.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(toggle.isChecked());
      assertTrue(c.settings.getBoolean("climateAutoOff", false));
      toggle.setChecked(false);
      assertFalse(c.settings.getBoolean("climateAutoOff", true));
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

  @Test
  public void odometerSitsUnderTheCarAndUpdateTimeBesideTheTitle() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = ((DApplication) a.get().getApplication()).controller();
      AccountPersistenceTest.await(c);
      View root = a.get().getWindow().getDecorView();
      TextView odometer = root.findViewWithTag("odometer");
      assertEquals(View.GONE, odometer.getVisibility());
      long now = System.currentTimeMillis();
      c.snapshot =
          new com.dautolock.app.core.VehicleSnapshot(
              new org.json.JSONObject().put("time", now).put("totalMileage", 13042), now);
      c.changed();
      org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(View.VISIBLE, odometer.getVisibility());
      assertEquals("총 주행거리 13,042 km", odometer.getText().toString());
      // v0.3.61: odometer in the picture's bottom-right corner, update time on the title line.
      assertSame(root.findViewWithTag("signalCard"), odometer.getParent());
      TextView checked = root.findViewWithTag("statusCheckedTime");
      assertSame(root.findViewWithTag("doorTitle").getParent(), checked.getParent());
      assertSame(root.findViewWithTag("statusRefresh").getParent().getParent(), checked.getParent());
    }
  }

  private void measureAt(Controller c, View primary, double vehicle, double beacon) {
    c.averageRssi = vehicle;
    c.beaconRssi = beacon;
    primary.performClick();
    org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(4300));
  }

  private Controller wizardReady(org.robolectric.android.controller.ActivityController<MainActivity> a)
      throws Exception {
    Controller c = ((DApplication) a.get().getApplication()).controller();
    AccountPersistenceTest.await(c);
    c.cloud.protocol.setSignToken("test-session");
    c.vin = "test-car";
    c.settings
        .edit()
        .putString("beaconAddress", "AA:BB:CC:DD:EE:01")
        .putString("beaconName", "Holy-IOT")
        .commit();
    c.monitoring = c.autoEnabled = true;
    return c;
  }

  @Test
  public void positionWizardPausesAutomationWalksTheSpotsAndSavesBothSignals() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = wizardReady(a);
      View root = a.get().getWindow().getDecorView();
      root.findViewWithTag("thresholdEdit").performClick();
      root.findViewWithTag("calibrateOpen").performClick();
      assertEquals(View.VISIBLE, root.findViewWithTag("calibratePage").getVisibility());
      assertFalse(c.autoEnabled); // No door commands while measuring.
      TextView title = root.findViewWithTag("calStepTitle"),
          label = root.findViewWithTag("calStepLabel");
      View primary = root.findViewWithTag("calPrimary"), skip = root.findViewWithTag("calSkip");
      primary.performClick(); // 시작하기
      assertEquals("운전석 문 앞", title.getText().toString());
      assertEquals("도어 열림 위치 1 / 6", label.getText().toString());
      measureAt(c, primary, -60, -82); // Moves on by itself after 4 s.
      assertEquals("운전석 뒷문 앞", title.getText().toString());
      measureAt(c, primary, -62, -84);
      measureAt(c, primary, -84, -56); // Trunk: the beacon is the strong one.
      for (int i = 4; i <= 6; i++) skip.performClick();
      assertEquals("운전석에 앉아서", title.getText().toString());
      assertEquals("차 안", label.getText().toString());
      measureAt(c, primary, -66, -50);
      assertEquals("도어 잠금 위치 1 / 6", label.getText().toString());
      measureAt(c, primary, -90, -92);
      measureAt(c, primary, -88, -91);
      for (int i = 9; i <= 12; i++) skip.performClick();
      assertEquals("계산 결과", title.getText().toString());
      String result = ((TextView) root.findViewWithTag("calResult")).getText().toString();
      assertTrue(result, result.contains("도어 열림 위치 3곳 중 3곳"));
      assertTrue(result, result.contains("도어 잠금 위치 2곳 중 2곳"));
      assertTrue(result, result.contains("운전석에서는 잠기지 않아요"));
      primary.performClick(); // 이 기준으로 저장
      assertEquals(-75, c.settings.getInt("near", 0));
      assertEquals(-86, c.settings.getInt("far", 0));
      assertEquals(-73, c.settings.getInt("beaconNear", 0)); // Given only the trunk spot.
      assertEquals(-89, c.settings.getInt("beaconFar", 0));
      assertEquals(View.GONE, root.findViewWithTag("calibratePage").getVisibility());
    }
  }

  @Test
  public void leavingThePositionWizardPutsAutomationBack() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> a =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Controller c = wizardReady(a);
      View root = a.get().getWindow().getDecorView();
      root.findViewWithTag("thresholdEdit").performClick();
      root.findViewWithTag("calibrateOpen").performClick();
      assertFalse(c.autoEnabled);
      root.findViewWithTag("calPrimary").performClick();
      c.averageRssi = -60;
      root.findViewWithTag("calPrimary").performClick(); // Leave mid-measurement.
      a.get().onBackPressed();
      assertEquals(View.GONE, root.findViewWithTag("calibratePage").getVisibility());
      assertTrue(c.autoEnabled);
      assertFalse(c.settings.contains("near")); // Nothing saved.
      c.monitoring = c.autoEnabled = false;
    }
  }
}
