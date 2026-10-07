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
  public void serviceReconfiguresWithoutTurningOffRequestedAutomaticMode() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    Controller c = app.controller();
    AccountPersistenceTest.await(c);
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
    Shadows.shadowOf(app.getSystemService(BluetoothManager.class).getAdapter())
        .setState(BluetoothAdapter.STATE_ON);
    c.settings.edit().putString("address", "AA:BB:CC:DD:EE:FF").commit();
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
    c.settings.edit().putString("address", "AA:BB:CC:DD:EE:FF").commit();
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

  @Test
  public void observationPublishesDiagnosticsEvenWithoutAnySignal() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    Controller c = app.controller();
    Shadows.shadowOf(app)
        .grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
    BluetoothAdapter adapter = app.getSystemService(BluetoothManager.class).getAdapter();
    Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
    c.settings.edit().putString("address", "AA:BB:CC:DD:EE:FF").commit();
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
