package com.dautolock.app.watchkey;

import android.content.Context;
import android.content.SharedPreferences;
import com.dautolock.app.api.BydConfig;
import com.dautolock.app.core.DiagnosticLog;
import com.dautolock.app.watchble.WatchBleClient;
import com.dautolock.app.watchble.WatchBleCodec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Facade for the Bluetooth (watch) key feature, used by the settings UI. Everything the feature
 * touches lives in this package plus one settings flag ("watchKeyEnabled", default off) — so it is
 * fully reversible: turning the flag off restores the previous behaviour instantly, and reverting
 * this package's commit removes it entirely.
 *
 * <p>Stage A is issuance + status only. There is no BLE connection and no change to the lock/unlock
 * decision here; those come in later stages, still behind this flag.
 */
public final class WatchKey {

  public static final String FLAG = "watchKeyEnabled";

  private final Context context;
  private final SharedPreferences settings;
  private final DiagnosticLog diagnostics;
  private final WatchKeyStore store;

  public WatchKey(Context context, SharedPreferences settings, DiagnosticLog diagnostics) {
    this.context = context.getApplicationContext();
    this.settings = settings;
    this.diagnostics = diagnostics;
    this.store = new WatchKeyStore(this.context);
  }

  public boolean enabled() {
    return settings.getBoolean(FLAG, false);
  }

  public void setEnabled(boolean on) {
    settings.edit().putBoolean(FLAG, on).apply();
    diagnostics.record("WATCHKEY_FLAG", on ? "on" : "off");
  }

  public boolean hasKey() {
    return store.hasBleKey();
  }

  /** The car's BLE MAC for the issued key, or empty. Used to scan its advertisement RSSI (Stage C). */
  public String bleMac() {
    return store.hasBleKey() ? store.macAddress() : "";
  }

  public String statusLine() {
    if (!store.hasBleKey()) {
      return store.hasWatchToken() ? "승인됨 · 블루투스 키 미발급" : "발급되지 않음";
    }
    String vin = store.vin();
    return "발급됨 · "
        + WatchKeyProvision.mask(store.macAddress())
        + (vin.isEmpty() ? "" : " · 차량 연결됨");
  }

  /** Begin provisioning. Callbacks are posted through {@code main}; returns the run so UI can cancel. */
  public WatchKeyProvision register(WatchKeyProvision.Listener listener, Executor main) {
    WatchKeyClient client =
        new WatchKeyClientImpl(context, BydConfig.fromRegion("KR"), diagnostics::record);
    WatchKeyProvision provision =
        new WatchKeyProvision(client, store, main, diagnostics::record);
    provision.start(listener);
    return provision;
  }

  /**
   * Stage B: run one manual BLE lock/unlock test with the issued key. Connects over BLE, does the
   * dkey handshake and sends a single command; callbacks are posted on the main thread by the client.
   * Manual only — never called from the automatic door logic. Returns the client so the UI can cancel.
   */
  public WatchBleClient bleTest(boolean lock, WatchBleClient.Listener listener) {
    if (!store.hasBleKey()) {
      if (listener != null) listener.onResult(false, "발급된 블루투스 키가 없습니다", -1);
      return null;
    }
    WatchBleClient client = new WatchBleClient(context, diagnostics::record);
    client.start(
        store.dkey(),
        store.macAddress(),
        store.keyNumber(),
        lock ? WatchBleCodec.LOCK : WatchBleCodec.UNLOCK,
        listener);
    return client;
  }

  /** Result of one blocking BLE command: whether the car confirmed it, a message, and the door bitmap. */
  public static final class BleResult {
    public final boolean success;
    public final String message;
    public final int doors;
    /**
     * The control frame reached the GATT stack before the failure: the car may have acted on it,
     * so the result is unconfirmed rather than unsent (check the state before any other channel).
     */
    public final boolean controlSent;

    public BleResult(boolean success, String message, int doors) {
      this(success, message, doors, false);
    }

    public BleResult(boolean success, String message, int doors, boolean controlSent) {
      this.success = success;
      this.message = message;
      this.doors = doors;
      this.controlSent = controlSent;
    }
  }

  /**
   * Stage C: send one control over the issued watch BLE key and block until the car answers or the
   * timeout elapses. Runs the same handshake as the manual test. Intended for the Controller's cloud
   * worker thread (never the main thread): the GATT callbacks land on the main looper and release
   * this latch. Returns a non-null result; {@code success} false means the caller should fall back to
   * the cloud. Does nothing and reports failure when no key is issued.
   */
  /** Connection cap for commands sent by the app (not the settings test): see WatchBleClient. */
  public static final long CONNECT_TIMEOUT_MS = 3000;

  public BleResult sendBleBlocking(byte controlByte, long timeoutMs) {
    return send(controlByte, timeoutMs, 0, 0);
  }

  /**
   * Like {@link #sendBleBlocking}, but on success the authenticated link stays open up to {@code
   * holdMs} (start authorization), closing early on {@link #releaseHold}, a disconnect, or three
   * connected-RSSI readings at or below {@code releaseBelowRssi}. Returns once the car answered.
   */
  public BleResult sendBleAndHold(
      byte controlByte, long timeoutMs, long holdMs, int releaseBelowRssi) {
    return send(controlByte, timeoutMs, holdMs, releaseBelowRssi);
  }

  /** The link kept open by {@link #sendBleAndHold}, if any. */
  private volatile WatchBleClient held;

  /** Close a held link; true if there was one. */
  public boolean releaseHold(String reason) {
    WatchBleClient h = held;
    held = null;
    if (h == null || !h.holding()) return false;
    h.release(reason);
    return true;
  }

  private BleResult send(byte controlByte, long timeoutMs, long holdMs, int releaseBelowRssi) {
    if (!store.hasBleKey()) return new BleResult(false, "no_key", -1);
    // One link at a time: a held start-authorization link gives way to the next command.
    if (releaseHold("next_command"))
      try {
        Thread.sleep(400); // Let the module drop our previous link before connecting again.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    final BleResult[] out = {null};
    final CountDownLatch latch = new CountDownLatch(1);
    final WatchBleClient client = new WatchBleClient(context, diagnostics::record);
    if (holdMs > 0) client.holdAfterSuccess(holdMs, releaseBelowRssi);
    client.start(
        store.dkey(),
        store.macAddress(),
        store.keyNumber(),
        controlByte,
        new WatchBleClient.Listener() {
          @Override
          public void onProgress(String message) {}

          @Override
          public void onResult(boolean success, String message, int doorStates) {
            out[0] = new BleResult(success, message, doorStates, client.controlSent());
            latch.countDown();
          }
        },
        timeoutMs,
        CONNECT_TIMEOUT_MS);
    try {
      if (!latch.await(timeoutMs + 1500, TimeUnit.MILLISECONDS)) {
        client.cancel();
        return new BleResult(false, "timeout", -1, client.controlSent());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      client.cancel();
      return new BleResult(false, "interrupted", -1, client.controlSent());
    }
    if (out[0] != null && out[0].success && client.holding()) held = client;
    return out[0] == null ? new BleResult(false, "no_result", -1) : out[0];
  }

  /** Wipe the stored key material. Used by the "키 삭제 / 초기화" button. */
  public void clear() {
    store.clearAll();
    diagnostics.record("WATCHKEY_CLEARED", "stored key wiped");
  }
}
