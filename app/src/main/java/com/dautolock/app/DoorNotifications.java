package com.dautolock.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class DoorNotifications {
  static final String UNLOCK = "com.dautolock.app.UNLOCK", LOCK = "com.dautolock.app.LOCK";
  static final int RESULT_ID = 2;

  static void channels(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("proximity", "자동 도어 제어", NotificationManager.IMPORTANCE_LOW));
    NotificationChannel events =
        new NotificationChannel("door_events", "도어 동작 결과", NotificationManager.IMPORTANCE_DEFAULT);
    events.enableVibration(true);
    manager.createNotificationChannel(events);
  }

  static PendingIntent open(Context context) {
    return PendingIntent.getActivity(
        context,
        0,
        new Intent(context, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private static Notification.Action action(
      Context context, String action, String label, int request) {
    PendingIntent intent =
        PendingIntent.getBroadcast(
            context,
            request,
            new Intent(context, DoorActionReceiver.class).setAction(action),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Action.Builder builder = new Notification.Action.Builder(null, label, intent);
    if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true);
    return builder.build();
  }

  static Notification ongoing(Context context, boolean automatic) {
    channels(context);
    PendingIntent stop =
        PendingIntent.getService(
            context,
            3,
            new Intent(context, ProximityService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(context, "proximity")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("D-Autolock · " + (automatic ? "자동 도어 켜짐" : "거리 관찰 중"))
        .setContentText("열기 / 잠금 · 상태를 확인한 뒤 실행합니다")
        .setContentIntent(open(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .addAction(action(context, UNLOCK, "열기", 1))
        .addAction(action(context, LOCK, "닫기 · 잠금", 2))
        .addAction(new Notification.Action.Builder(null, "관찰 종료", stop).build())
        .build();
  }

  static boolean enabled(Context context) {
    return (Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
        && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
  }

  static void result(Context context, String title, String detail) {
    result(context, RESULT_ID, title, detail);
  }

  static void result(Context context, int id, String title, String detail) {
    channels(context);
    if (!enabled(context)) return;
    try {
      context
          .getSystemService(NotificationManager.class)
          .notify(
              id,
              new Notification.Builder(context, "door_events")
                  .setSmallIcon(R.drawable.ic_notification)
                  .setContentTitle(title)
                  .setContentText(detail)
                  .setStyle(new Notification.BigTextStyle().bigText(detail))
                  .setContentIntent(open(context))
                  .setAutoCancel(true)
                  .setVisibility(Notification.VISIBILITY_PRIVATE)
                  .build());
    } catch (SecurityException ignored) {
    }
  }
}
