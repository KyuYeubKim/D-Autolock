package com.dautolock.app.watchkey;

import android.content.Context;
import android.content.SharedPreferences;
import com.dautolock.app.api.BydConfig;
import com.dautolock.app.core.DiagnosticLog;
import com.dautolock.app.watchble.WatchBleClient;
import com.dautolock.app.watchble.WatchBleCodec;
import java.util.concurrent.Executor;

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

  /** Wipe the stored key material. Used by the "키 삭제 / 초기화" button. */
  public void clear() {
    store.clearAll();
    diagnostics.record("WATCHKEY_CLEARED", "stored key wiped");
  }
}
