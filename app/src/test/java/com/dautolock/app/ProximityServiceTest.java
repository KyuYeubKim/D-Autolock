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
