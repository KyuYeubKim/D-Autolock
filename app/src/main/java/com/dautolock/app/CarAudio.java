package com.dautolock.app;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Whether this phone's audio or call Bluetooth is connected to the car's head unit — the device the
 * 차량 보조 앱 was paired with. The head unit connects only while the car is on and the phone is in
 * or right at it, so this marks "in the car" far more reliably than BLE strength (BydLock uses the
 * same signal). The Bridge's own RFCOMM link is not a profile connection and never counts.
 * Event-driven: profile broadcasts plus one read when scanning starts, no polling.
 */
final class CarAudio {
  static final String[] ACTIONS = {
    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
  };
  private final Controller controller;
  private volatile boolean a2dp, headset, everConnected;

  CarAudio(Controller controller) {
    this.controller = controller;
  }

  boolean connected() {
    return a2dp || headset;
  }

  /**
   * Whether the car is on as far as its audio tells: TRUE while the head unit's audio is connected,
   * FALSE once it has been connected in this run and dropped again (the car was switched off or the
   * phone left it), null while it has never connected (a phone that does not use the car's audio, or
   * nothing seen yet), so nothing can be concluded.
   */
  Boolean carOn() {
    if (connected()) return Boolean.TRUE;
    return everConnected ? Boolean.FALSE : null;
  }

  /** A profile connection broadcast; only the car's head unit counts. */
  void onBroadcast(Intent intent) {
    String action = intent.getAction();
    boolean audio = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action);
    if (!audio && !BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) return;
    BluetoothDevice device =
        Build.VERSION.SDK_INT >= 33
            ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
            : legacyDevice(intent);
    String car = controller.vehicleLink.headUnitAddress();
    if (device == null || car.isEmpty() || !car.equalsIgnoreCase(device.getAddress())) return;
    boolean on = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED;
    set(audio ? BluetoothProfile.A2DP : BluetoothProfile.HEADSET, on);
  }

  @SuppressWarnings("deprecation")
  private static BluetoothDevice legacyDevice(Intent intent) {
    return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
  }

  /** Reads the current connections once (when scanning starts). */
  void refresh(Context context) {
    String car = controller.vehicleLink.headUnitAddress();
    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (car.isEmpty() || adapter == null || !adapter.isEnabled()) {
      clear();
      return;
    }
    if (Build.VERSION.SDK_INT >= 31
        && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return;
    BluetoothDevice device = adapter.getRemoteDevice(car);
    for (int profile : new int[] {BluetoothProfile.A2DP, BluetoothProfile.HEADSET})
      try {
        adapter.getProfileProxy(
            context,
            new BluetoothProfile.ServiceListener() {
              public void onServiceConnected(int which, BluetoothProfile proxy) {
                try {
                  set(which, proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED);
                } catch (SecurityException ignored) {
                } finally {
                  adapter.closeProfileProxy(which, proxy);
                }
              }

              public void onServiceDisconnected(int which) {}
            },
            profile);
      } catch (SecurityException ignored) {
      }
  }

  void clear() {
    a2dp = headset = everConnected = false;
  }

  private synchronized void set(int profile, boolean on) {
    boolean before = connected();
    if (profile == BluetoothProfile.A2DP) a2dp = on;
    else headset = on;
    if (before == connected()) return;
    if (connected()) everConnected = true;
    controller.diagnostics.record(
        "CAR_AUDIO",
        "connected=" + connected() + " profile=" + (profile == BluetoothProfile.A2DP ? "A2DP" : "HFP"));
    // The head unit is up: the 차량 보조 앱 link need not wait out its reconnect interval.
    if (connected()) controller.vehicleLink.wake();
    // It went down: the car was just switched off — the start of the power-off grace.
    else controller.carAudioDropped();
    controller.changed();
  }
}
