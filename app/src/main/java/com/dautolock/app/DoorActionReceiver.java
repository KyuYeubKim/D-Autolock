package com.dautolock.app;

import android.app.KeyguardManager;
import android.content.*;
import com.dautolock.app.api.CloudClient;

public final class DoorActionReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (!DoorNotifications.UNLOCK.equals(action) && !DoorNotifications.LOCK.equals(action)) return;
    Controller controller = ((DApplication) context.getApplicationContext()).controller();
    KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
    if (controller.initializing
        || !controller.monitoring
        || (keyguard != null && keyguard.isDeviceLocked())) {
      DoorNotifications.result(context, "차량 제어 보류", "휴대폰 잠금을 해제하고 앱의 거리 관찰 상태를 확인하세요");
      return;
    }
    controller.diagnostics.record(
        "NOTIFICATION_ACTION", DoorNotifications.UNLOCK.equals(action) ? "UNLOCK" : "LOCK");
    controller.command(
        DoorNotifications.UNLOCK.equals(action)
            ? CloudClient.Command.UNLOCK
            : CloudClient.Command.LOCK,
        false,
        () -> true);
  }
}
