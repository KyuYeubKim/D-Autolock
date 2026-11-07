package com.dautolock.app.watchkey;

/**
 * The watch provisioning REST steps against the BYD region host. An interface so {@link
 * WatchKeyProvision} can be driven by a fake in unit tests — the real implementation ({@link
 * WatchKeyClientImpl}) is only ever exercised on the user's device against their own account, never
 * from the build/test environment.
 */
public interface WatchKeyClient {

  /** {@code watch/login/getServerCurrentTime} → server epoch millis (to align timestamps). */
  long serverTime() throws Exception;

  /** {@code watch/login/create/qrcode} → the uuid + QR payload for the owner to approve. */
  QrInfo createQrCode() throws Exception;

  /** {@code watch/login/check/qrcode} → the raw {@code codeStatus} (approval state). */
  int qrStatus(String uuid) throws Exception;

  /** {@code watch/login/gain/token} → the watch session (after owner approval). */
  WatchTokens gainToken(String uuid) throws Exception;

  /** {@code watch/login/gain/vehicle} → the bound VIN. */
  String gainVehicleVin(WatchTokens tokens) throws Exception;

  /** {@code watch/login/gain/bluetooth} → the BLE key (dkey / keyNumber / MAC). */
  BleKey gainBluetooth(WatchTokens tokens) throws Exception;
}
