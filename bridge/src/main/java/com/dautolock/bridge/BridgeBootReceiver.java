package com.dautolock.bridge;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import com.dautolock.link.LinkVault;

/**
 * Restarts the vehicle-state server without opening the app. Real logs: after the head unit was
 * switched off and on, the phone's Bridge link failed for the whole drive because the server only
 * started when someone opened the Bridge screen. Every attempt is written to {@link BootLog} so the
 * vehicle diagnostic can show whether the broadcast arrived and whether the start was allowed.
 */
public final class BridgeBootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent == null ? null : intent.getAction();
    if (action == null) return;
    String tag = action.substring(action.lastIndexOf('.') + 1);
    if (Build.VERSION.SDK_INT >= 31
        && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      BootLog.add(context, tag + " · 자동시작 안 함(주변 기기 권한 없음)");
      return;
    }
    if (!new LinkVault(context).exists()) {
      BootLog.add(context, tag + " · 자동시작 안 함(QR 미등록)");
      return;
    }
    try {
      context.startForegroundService(
          new Intent(context, BridgeService.class).putExtra("reason", action));
      BootLog.add(context, tag + " · startForegroundService 호출함");
    } catch (Throwable e) {
      // Android 12+ can block a background FGS start; record the exact reason for the diagnostic.
      BootLog.add(context, tag + " · 자동시작 실패: " + e.getClass().getSimpleName());
      BridgeService.status = "자동 시작 차단됨 (" + e.getClass().getSimpleName() + ") · 앱을 한 번 여세요";
      try {
        context.startService(new Intent(context, BridgeService.class).putExtra("reason", action));
        BootLog.add(context, tag + " · startService 재시도함");
      } catch (Throwable ignored) {
      }
    }
  }
}
