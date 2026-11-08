package com.dautolock.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.bluetooth.*;
import android.content.*;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProximityServiceTest {
  @Test
  public void fourthBleResultStartsUnlockBeforeNextTimerOrUiHeartbeat() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    Controller c = app.controller();
    AccountPersistenceTest.await(c);
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
    BluetoothAdapter adapter = app.getSystemService(BluetoothManager.class).getAdapter();
    Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
    c.settings
        .edit()
        .putString("address", "AA:BB:CC:DD:EE:FF")
        .putInt("nearWaitSeconds", 0)
        .commit();
    org.robolectric.android.controller.ServiceController<ProximityService> lifecycle =
        Robolectric.buildService(ProximityService.class).create();
    ProximityService service = lifecycle.get();
    service.onStartCommand(new Intent(app, ProximityService.class), 0, 1);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    CommandFlowTest.Protocol protocol = new CommandFlowTest.Protocol();
    protocol.locked = true;
    c.cloud = new com.dautolock.app.api.CloudClient(protocol);
    c.vin = "test-car";
    c.pinHash = "test-pin";
    c.capabilities = new org.json.JSONObject().put("functionNo", "1006");
    c.autoEnabled = true;
    java.lang.reflect.Field callbackField = ProximityService.class.getDeclaredField("callback");
    callbackField.setAccessible(true);
    android.bluetooth.le.ScanCallback callback =
        (android.bluetooth.le.ScanCallback) callbackField.get(service);
    for (int i = 0; i < 4; i++) {
      org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(10));
      callback.onScanResult(
          1,
          new android.bluetooth.le.ScanResult(
              adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF"),
              null,
              -50,
              android.os.SystemClock.elapsedRealtimeNanos()));
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      if (i < 3) assertFalse(c.busy());
    }
    assertTrue(c.busy()); // Only 40 ms elapsed, before the 250 ms or 1 s timers.
    AccountPersistenceTest.await(c);
    assertEquals(java.util.Collections.singletonList("OPENDOOR"), protocol.commands);
    // More samples in the same near zone must not create another unlock.
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(10));
    callback.onScanResult(
        1,
        new android.bluetooth.le.ScanResult(
            adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF"),
            null,
            -50,
            android.os.SystemClock.elapsedRealtimeNanos()));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertFalse(c.busy());
    lifecycle.destroy();
  }

  @Test
  public void serviceReconfiguresWithoutTurningOffRequestedAutomaticMode() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    Controller c = app.controller();
    AccountPersistenceTest.await(c);
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
    Shadows.shadowOf(app.getSystemService(BluetoothManager.class).getAdapter())
        .setState(BluetoothAdapter.STATE_ON);
    c.settings
        .edit()
        .putBoolean("setupRequired", false)
        .putString("address", "AA:BB:CC:DD:EE:FF")
        .commit();
    com.dautolock.app.api.CloudProtocol p =
        new com.dautolock.app.api.CloudProtocol(com.dautolock.app.api.BydConfig.fromRegion("KR")) {
          @Override
          public void postTokenSecure(
              String endpoint,
              java.util.Map<String, Object> data,
              String vin,
              com.dautolock.app.api.BydApiCallback<org.json.JSONObject> callback) {
            callback.onSuccess(new org.json.JSONObject());
          }
        };
    p.setSignToken("test-session");
    c.cloud = new com.dautolock.app.api.CloudClient(p);
    c.vin = "test-car";
    c.pinHash = "test-pin";
    org.robolectric.android.controller.ServiceController<ProximityService> lifecycle =
        Robolectric.buildService(ProximityService.class).create();
    ProximityService service = lifecycle.get();
    service.onStartCommand(
        new Intent(app, ProximityService.class).putExtra("automatic", true), 0, 1);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(c.monitoring);
    assertTrue(c.autoEnabled);
    java.lang.reflect.Field field = ProximityService.class.getDeclaredField("engine");
    field.setAccessible(true);
    Object first = field.get(service);
    c.thresholds(-60, -75, 1, 4, 10);
    Intent reload = Shadows.shadowOf(app).getNextStartedService();
    service.onStartCommand(reload, 0, 2);
    assertTrue(c.monitoring);
    assertTrue(c.autoEnabled);
    assertNotSame(first, field.get(service));
    assertEquals(0, ((com.dautolock.app.core.ProximityEngine) field.get(service)).count());
    lifecycle.destroy();
  }

  @Test
  public void cloudBackoffKeepsBleObservationAndPendingUnlockWithoutSending() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    Controller c = app.controller();
    AccountPersistenceTest.await(c);
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
    Shadows.shadowOf(app.getSystemService(BluetoothManager.class).getAdapter())
        .setState(BluetoothAdapter.STATE_ON);
    c.settings
        .edit()
        .putBoolean("setupRequired", false)
        .putString("address", "AA:BB:CC:DD:EE:FF")
        .commit();
    java.util.concurrent.atomic.AtomicInteger requests =
        new java.util.concurrent.atomic.AtomicInteger();
    com.dautolock.app.api.CloudProtocol protocol =
        new com.dautolock.app.api.CloudProtocol(com.dautolock.app.api.BydConfig.fromRegion("KR")) {
          @Override
          public void postTokenSecure(
              String endpoint,
              java.util.Map<String, Object> data,
              String vin,
              com.dautolock.app.api.BydApiCallback<org.json.JSONObject> callback) {
            requests.incrementAndGet();
            callback.onError("1008", new ServiceBusyException());
          }
        };
    protocol.setSignToken("test-session");
    c.cloud = new com.dautolock.app.api.CloudClient(protocol);
    assertThrows(Exception.class, c.cloud::vehicles);
    org.robolectric.android.controller.ServiceController<ProximityService> lifecycle =
        Robolectric.buildService(ProximityService.class).create();
    ProximityService service = lifecycle.get();
    service.onStartCommand(new Intent(app, ProximityService.class), 0, 1);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    c.autoEnabled = true;
    c.vin = "test-car";
    java.lang.reflect.Field field = ProximityService.class.getDeclaredField("engine");
    field.setAccessible(true);
    com.dautolock.app.core.ProximityEngine engine =
        (com.dautolock.app.core.ProximityEngine) field.get(service);
    for (int i = 0; i < 4; i++) {
      engine.sample(-50, android.os.SystemClock.elapsedRealtime());
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
    }
    assertTrue(c.autoDetail.contains("BYD 1008"));
    assertEquals(-50, c.averageRssi, 0.01);
    assertEquals(
        com.dautolock.app.core.ProximityEngine.Action.UNLOCK,
        engine.pending(android.os.SystemClock.elapsedRealtime()));
    assertEquals(1, requests.get());
    assertTrue(c.monitoring);
    lifecycle.destroy();
    assertTrue(Double.isNaN(c.averageRssi));
  }

  /** A scanning service with automatic mode on and a fake cloud; samples go through the callback. */
  private static final class Rig {
    final DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    final Controller c = app.controller();
    final BluetoothAdapter adapter;
    final CommandFlowTest.Protocol protocol = new CommandFlowTest.Protocol();
    final org.robolectric.android.controller.ServiceController<ProximityService> lifecycle;
    final ProximityService service;
    final android.bluetooth.le.ScanCallback callback;

    Rig(java.util.function.Consumer<SharedPreferences.Editor> settings) throws Exception {
      AccountPersistenceTest.await(c);
      Shadows.shadowOf(app)
          .grantPermissions(
              Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
      adapter = app.getSystemService(BluetoothManager.class).getAdapter();
      Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
      SharedPreferences.Editor edit =
          c.settings
              .edit()
              .putBoolean("setupRequired", false)
              .putString("address", "AA:BB:CC:DD:EE:FF")
              .putInt("nearWaitSeconds", 0);
      settings.accept(edit);
      edit.commit();
      lifecycle = Robolectric.buildService(ProximityService.class).create();
      service = lifecycle.get();
      service.onStartCommand(
          new Intent(app, ProximityService.class).putExtra("automatic", true), 0, 1);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      protocol.locked = true;
      c.cloud = new com.dautolock.app.api.CloudClient(protocol);
      c.vin = "test-car";
      c.pinHash = "test-pin";
      c.capabilities = new org.json.JSONObject().put("functionNo", "1006");
      c.autoEnabled = true;
      java.lang.reflect.Field field = ProximityService.class.getDeclaredField("callback");
      field.setAccessible(true);
      callback = (android.bluetooth.le.ScanCallback) field.get(service);
    }

    void sample(String address, int rssi) {
      callback.onScanResult(
          1,
          new android.bluetooth.le.ScanResult(
              adapter.getRemoteDevice(address),
              null,
              rssi,
              android.os.SystemClock.elapsedRealtimeNanos()));
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** One car sample per second (a phone standing by the car), letting the timers run. */
    void standNear(int seconds) throws Exception {
      for (int i = 0; i < seconds; i++) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        sample("AA:BB:CC:DD:EE:FF", -50);
        AccountPersistenceTest.await(c);
      }
    }

    void idle(int seconds) throws Exception {
      for (int i = 0; i < seconds; i++) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        AccountPersistenceTest.await(c);
      }
    }

    Object field(String name) throws Exception {
      java.lang.reflect.Field f = ProximityService.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(service);
    }

    void reconfigure() {
      Intent reload = Shadows.shadowOf(app).getNextStartedService();
      while (reload != null && !"RECONFIGURE".equals(reload.getAction()))
        reload = Shadows.shadowOf(app).getNextStartedService(); // Skip unrelated service starts.
      assertNotNull(reload);
      service.onStartCommand(reload, 0, 2);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  @Test
  public void savingThresholdsWhileStandingAtTheCarDoesNotReopenIt() throws Exception {
    // Real log 11-08 17:47–17:48: auto-unlocked, the car re-locked itself ~30 s later (no door
    // opened), the user saved thresholds at the car and the rebuilt engine unlocked it again.
    Rig r = new Rig(e -> {});
    r.standNear(4);
    assertEquals(java.util.Collections.singletonList("OPENDOOR"), r.protocol.commands);
    r.standNear(30); // Past the command cooldown, still at the car.
    r.protocol.locked = true; // The car's own relock.
    r.c.thresholds(-70, -85, 0, 5, 10);
    r.reconfigure();
    r.standNear(6);
    assertEquals(java.util.Collections.singletonList("OPENDOOR"), r.protocol.commands);
    assertEquals(
        "NEAR", ((com.dautolock.app.core.ProximityEngine) r.field("engine")).handled());
    r.lifecycle.destroy();
  }

  @Test
  public void savedBeaconThresholdsApplyWithoutRestartingTheScan() throws Exception {
    Rig r =
        new Rig(
            e ->
                e.putString("beaconAddress", "DA:20:E8:F2:77:25")
                    .putInt("beaconNear", -78)
                    .putInt("beaconFar", -85));
    Object before = r.field("beaconEngine");
    assertNotNull(before);
    r.c.beaconThresholds(-75, -88);
    r.reconfigure();
    com.dautolock.app.core.ProximityEngine after =
        (com.dautolock.app.core.ProximityEngine) r.field("beaconEngine");
    assertNotSame(before, after);
    // −86 was "far" under the old −85 and is in between under the saved −88.
    long now = android.os.SystemClock.elapsedRealtime();
    after.sample(-86, now);
    assertEquals("중간 거리", after.zone(now));
    r.lifecycle.destroy();
  }

  @Test
  public void ownBleKeySessionGapDoesNotLockTheCarButARealLossStillDoes() throws Exception {
    // Real log 11-08: the car's advertiser (= the BLE-key module) goes quiet ~5 s during every
    // BLE-key connection; with a 5 s loss-lock that read as "signal lost" next to the car.
    com.dautolock.app.watchble.BleAirtime.reset();
    try {
      Rig r = new Rig(e -> e.putInt("lossLockSeconds", 5));
      r.standNear(4);
      assertEquals(java.util.Collections.singletonList("OPENDOOR"), r.protocol.commands);
      r.standNear(11); // Past the command cooldown.
      long session = android.os.SystemClock.elapsedRealtime();
      com.dautolock.app.watchble.BleAirtime.begin(session); // e.g. a manual BLE-key command.
      r.idle(2);
      com.dautolock.app.watchble.BleAirtime.end(android.os.SystemClock.elapsedRealtime());
      r.idle(6); // 8 s without a car sample: past the 5 s loss-lock, inside the BLE grace.
      assertFalse(r.protocol.commands.contains("LOCKDOOR"));
      r.idle(6); // Grace over and the signal never came back: a real loss still locks.
      assertTrue(r.protocol.commands.contains("LOCKDOOR"));
      r.lifecycle.destroy();
    } finally {
      com.dautolock.app.watchble.BleAirtime.reset();
    }
  }

  @Test
  public void observationPublishesDiagnosticsEvenWithoutAnySignal() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    Controller c = app.controller();
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
    BluetoothAdapter adapter = app.getSystemService(BluetoothManager.class).getAdapter();
    Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
    c.settings
        .edit()
        .putBoolean("setupRequired", false)
        .putString("address", "AA:BB:CC:DD:EE:FF")
        .commit();
    org.robolectric.android.controller.ServiceController<ProximityService> lifecycle =
        Robolectric.buildService(ProximityService.class).create();
    ProximityService service = lifecycle.get();
    service.onStartCommand(new Intent(app, ProximityService.class), 0, 1);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(c.monitoring);
    assertTrue(c.signalDetail.contains("수신 0회"));
    assertEquals(0, c.signalStrength);
    app.sendBroadcast(
        new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(c.monitoring);
    lifecycle.destroy();
    assertFalse(c.monitoring);
  }
}
