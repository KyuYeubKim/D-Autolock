package com.dautolock.app.watchkey;

/** The Bluetooth key issued by {@code watch/login/gain/bluetooth}. */
public final class BleKey {
  public final String dkey;
  public final String keyNumber;
  public final String macAddress;

  public BleKey(String dkey, String keyNumber, String macAddress) {
    this.dkey = dkey == null ? "" : dkey;
    this.keyNumber = keyNumber == null ? "" : keyNumber;
    this.macAddress = macAddress == null ? "" : macAddress;
  }

  public boolean valid() {
    return !dkey.isEmpty() && !macAddress.isEmpty();
  }
}
