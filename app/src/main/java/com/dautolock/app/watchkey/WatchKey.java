package com.dautolock.app.watchkey;

import android.content.Context;
import android.content.SharedPreferences;
import com.dautolock.app.api.BydConfig;
import com.dautolock.app.core.DiagnosticLog;
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

  /** Wipe the stored key material. Used by the "키 삭제 / 초기화" button. */
  public void clear() {
    store.clearAll();
    diagnostics.record("WATCHKEY_CLEARED", "stored key wiped");
  }
}
