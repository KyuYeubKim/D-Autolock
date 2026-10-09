package com.dautolock.bridge;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import com.dautolock.link.LinkVault;

/**
 * Restarts the vehicle-state server without opening the app. Real logs: after the head unit was
 * switched off and on, the phone's Bridge link failed every 5 s for the whole drive because the
 * server only started when someone opened the Bridge screen.
 */
public final class BridgeBootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent == null ? null : intent.getAction();
    if (action == null) return;
    start(context, action);
  }

  static void start(Context context, String reason) {
    try {
      if (Build.VERSION.SDK_INT >= 31
          && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
              != PackageManager.PERMISSION_GRANTED) return; // Needs one manual start first.
      if (!new LinkVault(context).exists()) return; // Not paired yet (the server reads the key itself).
      context.startForegroundService(
          new Intent(context, BridgeService.class).putExtra("reason", reason));
    } catch (Exception e) {
      BridgeService.status = "자동 시작 보류 (" + e.getClass().getSimpleName() + ") · 앱을 한 번 여세요";
    }
  }
}
