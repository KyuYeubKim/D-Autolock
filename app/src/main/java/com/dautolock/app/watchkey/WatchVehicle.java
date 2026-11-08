package com.dautolock.app.watchkey;

/**
 * The {@code watch/login/gain/vehicle} result. The BLE key can be carried here (nested under
 * {@code watchBluetoothDto.watchBluetoothInfo}) as well as in {@code gain/bluetooth}, so we capture
 * both and use whichever is valid.
 */
public final class WatchVehicle {
  public final String vin;
  public final BleKey ble;

  public WatchVehicle(String vin, BleKey ble) {
    this.vin = vin == null ? "" : vin;
    this.ble = ble == null ? new BleKey("", "", "") : ble;
  }
}
