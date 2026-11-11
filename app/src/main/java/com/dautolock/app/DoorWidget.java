package com.dautolock.app;

import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;
import com.dautolock.app.api.CloudClient;

/**
 * Home-screen widget with 도어 열기 and 도어 잠금. Like the in-app controls, a button only arms on
 * the first tap ("다시 누르면 …") and sends on a second tap within {@link #ARM_MS}; the command then
 * runs exactly like the app's manual button (same safety checks, the result as a notification), so
 * 도어 열기 on an open, switched-off car is also the start authorization.
 *
 * <p>The provider is exported (the launcher must reach it), so it acts only on the standard widget
 * broadcasts. Taps go to the non-exported {@link DoorActionReceiver} through this app's own
 * PendingIntents — no other app can send a door command through the widget.
 */
public final class DoorWidget extends AppWidgetProvider {
  static final String TAP_UNLOCK = "com.dautolock.app.WIDGET_UNLOCK";
  static final String TAP_LOCK = "com.dautolock.app.WIDGET_LOCK";
  static final long ARM_MS = 3000, SENT_SHOW_MS = 3000, INIT_WAIT_MS = 5000;

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static String armed, sent;
  private static long armedAt, sentAt;

  @Override
  public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
    manager.updateAppWidget(ids, views(context));
  }

  /** The armed button, if the first tap was within {@link #ARM_MS}. */
  static synchronized String armedAction(long now) {
    return armed != null && now - armedAt <= ARM_MS ? armed : null;
  }

  private static synchronized String sentAction(long now) {
    return sent != null && now - sentAt <= SENT_SHOW_MS ? sent : null;
  }

  /** First tap arms; a second tap on the same button within {@link #ARM_MS} returns true. */
  private static synchronized boolean confirm(String action, long now) {
    if (action.equals(armed) && now - armedAt <= ARM_MS) {
      armed = null;
      return true;
    }
    armed = action;
    armedAt = now;
    return false;
  }

  private static synchronized void markSent(String action, long now) {
    sent = action;
    sentAt = now;
  }

  /** Test-only: the tap state is static. */
  static synchronized void reset() {
    armed = sent = null;
    armedAt = sentAt = 0;
  }

  static void render(Context context) {
    AppWidgetManager manager = AppWidgetManager.getInstance(context);
    if (manager == null) return;
    int[] ids = manager.getAppWidgetIds(new ComponentName(context, DoorWidget.class));
    if (ids == null || ids.length == 0) return;
    manager.updateAppWidget(ids, views(context));
  }

  static RemoteViews views(Context context) {
    long now = SystemClock.elapsedRealtime();
    String armedNow = armedAction(now), sentNow = sentAction(now);
    RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_doors);
    button(
        v,
        R.id.widget_unlock,
        R.id.widget_unlock_icon,
        R.id.widget_unlock_label,
        TAP_UNLOCK.equals(armedNow),
        TAP_UNLOCK.equals(sentNow) ? "열기 요청함" : TAP_UNLOCK.equals(armedNow) ? "다시 누르면 열기" : "도어 열기");
    button(
        v,
        R.id.widget_lock,
        R.id.widget_lock_icon,
        R.id.widget_lock_label,
        TAP_LOCK.equals(armedNow),
        TAP_LOCK.equals(sentNow) ? "잠금 요청함" : TAP_LOCK.equals(armedNow) ? "다시 누르면 잠금" : "도어 잠금");
    v.setOnClickPendingIntent(R.id.widget_unlock, tap(context, TAP_UNLOCK, 21));
    v.setOnClickPendingIntent(R.id.widget_lock, tap(context, TAP_LOCK, 22));
    return v;
  }

  private static void button(
      RemoteViews v, int box, int icon, int label, boolean armedNow, String text) {
    v.setInt(box, "setBackgroundResource", armedNow ? R.drawable.widget_button_armed : R.drawable.widget_button);
    v.setInt(icon, "setColorFilter", armedNow ? 0xffffffff : Palette.ACCENT);
    v.setTextColor(label, armedNow ? 0xffffffff : Palette.TEXT);
    v.setTextViewText(label, text);
  }

  private static PendingIntent tap(Context context, String action, int requestCode) {
    // A user's tap: the foreground broadcast queue delivers it without waiting behind other apps.
    Intent intent =
        new Intent(context, DoorActionReceiver.class)
            .setAction(action)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
    return PendingIntent.getBroadcast(
        context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /**
   * A widget button tap (from {@link DoorActionReceiver}). Requires an unlocked phone, as the
   * notification buttons do; waits briefly when the app process has just been started by the tap.
   *
   * <p>{@code done} ends the broadcast and must run as soon as the tap is handled, never after the
   * label resets: a manifest receiver gets its broadcasts one at a time, so while the arming tap's
   * broadcast was held for {@link #ARM_MS} the second tap waited behind it, arrived after the
   * window and only armed again — the widget could never send (real log 11-11 12:15/12:27: no
   * WIDGET_ACTION at all). The labels reset on the main thread without holding the broadcast.
   */
  static void onTap(Context context, String action, Runnable done) {
    Context app = context.getApplicationContext();
    long now = SystemClock.elapsedRealtime();
    if (!confirm(action, now)) {
      render(app);
      main.postDelayed(() -> render(app), ARM_MS + 100);
      finish(done);
      return;
    }
    KeyguardManager guard = app.getSystemService(KeyguardManager.class);
    if (guard != null && guard.isDeviceLocked()) {
      DoorNotifications.result(app, "차량 제어 보류", "휴대폰 잠금을 해제한 뒤 다시 누르세요");
      render(app);
      finish(done);
      return;
    }
    Controller controller = ((DApplication) app).controller();
    if (!controller.initializing) {
      send(app, controller, action);
      finish(done);
      return;
    }
    // The tap started the process: keep the broadcast (and so the process) until the app is ready.
    new Thread(
            () -> {
              long until = SystemClock.elapsedRealtime() + INIT_WAIT_MS;
              while (controller.initializing && SystemClock.elapsedRealtime() < until)
                try {
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              main.post(
                  () -> {
                    send(app, controller, action);
                    finish(done);
                  });
            },
            "door-widget")
        .start();
  }

  private static void send(Context app, Controller controller, String action) {
    boolean unlock = TAP_UNLOCK.equals(action);
    if (controller.initializing) {
      DoorNotifications.result(app, "차량 제어 보류", "앱을 준비하는 중입니다. 잠시 뒤 다시 누르세요");
    } else if (controller.busy()) {
      DoorNotifications.result(app, "차량 제어 보류", "다른 차량 요청을 처리하는 중입니다. 잠시 뒤 다시 누르세요");
    } else {
      markSent(action, SystemClock.elapsedRealtime());
      controller.diagnostics.record("WIDGET_ACTION", unlock ? "UNLOCK" : "LOCK");
      controller.command(
          unlock ? CloudClient.Command.UNLOCK : CloudClient.Command.LOCK, false, () -> true);
    }
    render(app);
    main.postDelayed(() -> render(app), SENT_SHOW_MS + 100);
  }

  private static void finish(Runnable done) {
    if (done != null) done.run();
  }
}
