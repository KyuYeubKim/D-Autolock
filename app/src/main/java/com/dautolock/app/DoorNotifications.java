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

  static final String CANCEL_STOP = "com.dautolock.app.CANCEL_STOP";
  static final int STOP_PENDING_ID = 5;

  /** Cancelling is always safe, so the action works without unlocking the phone. */
  static void stopPending(Context context, int seconds) {
    channels(context);
    if (!enabled(context)) return;
    PendingIntent cancel =
        PendingIntent.getBroadcast(
            context,
            6,
            new Intent(context, DoorActionReceiver.class).setAction(CANCEL_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    try {
      context
          .getSystemService(NotificationManager.class)
          .notify(
              STOP_PENDING_ID,
              new Notification.Builder(context, "door_events")
                  .setSmallIcon(R.drawable.ic_notification)
                  .setContentTitle(seconds + "초 후 차량 전원 종료")
                  .setContentText("차 안에 사람이 있으면 취소하세요. 종료 직전 P단·정차·잠금을 다시 확인합니다")
                  .setContentIntent(open(context))
                  .setTimeoutAfter((seconds + 30) * 1000L)
                  .setVisibility(Notification.VISIBILITY_PUBLIC)
                  .addAction(new Notification.Action.Builder(null, "종료 취소", cancel).build())
                  .build());
    } catch (SecurityException ignored) {
    }
  }

  static final String FINISH = "com.dautolock.app.FINISH";
  static final int SECURITY_ID = 7;
  static final int ALERT_RED = 0xFFE53935;

  /**
   * Red, high-priority alert when the car was left unlocked or powered ON. Its actions are explicit
   * user commands (phone must be unlocked); nothing is sent automatically from here.
   */
  static void securityAlert(Context context, String detail) {
    channels(context);
    if (!enabled(context)) return;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel("security_alerts", "차량 잠금·시동 경고", NotificationManager.IMPORTANCE_HIGH);
    channel.enableVibration(true);
    manager.createNotificationChannel(channel);
    android.text.SpannableString title = new android.text.SpannableString("차량 확인 필요 · 수동으로 잠그고 시동을 끄세요");
    title.setSpan(
        new android.text.style.ForegroundColorSpan(ALERT_RED), 0, title.length(), 0);
    try {
      manager.notify(
          SECURITY_ID,
          new Notification.Builder(context, "security_alerts")
              .setSmallIcon(R.drawable.ic_warning)
              .setColor(ALERT_RED)
              .setContentTitle(title)
              .setContentText(detail)
              .setStyle(new Notification.BigTextStyle().bigText(detail))
              .setContentIntent(open(context))
              .setCategory(Notification.CATEGORY_STATUS)
              .setVisibility(Notification.VISIBILITY_PRIVATE)
              .addAction(action(context, FINISH, "시동 끄고 잠금", 8))
              .addAction(action(context, LOCK, "도어 잠금", 9))
              .build());
    } catch (SecurityException ignored) {
    }
  }

  static void cancelSecurityAlert(Context context) {
    context.getSystemService(NotificationManager.class).cancel(SECURITY_ID);
  }

  static void cancelStopPending(Context context) {
    context.getSystemService(NotificationManager.class).cancel(STOP_PENDING_ID);
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
