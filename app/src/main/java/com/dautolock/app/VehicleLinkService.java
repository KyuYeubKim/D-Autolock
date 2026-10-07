package com.dautolock.app;

import android.app.*;
import android.content.*;
import android.os.*;

public final class VehicleLinkService extends Service {
  private Controller controller;

  public void onCreate() {
    super.onCreate();
    controller = ((DApplication) getApplication()).controller();
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("vehicle_link", "차량 기어 연결", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            30,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    startForeground(
        30,
        new Notification.Builder(this, "vehicle_link")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("D-Autolock · 차량 상태 연결")
            .setContentText("보조 앱에서 최신 기어를 확인합니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build());
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (controller.vehicleLink.configured()) controller.vehicleLink.start();
    else stopSelf();
    return START_NOT_STICKY;
  }

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onDestroy() {
    controller.vehicleLink.stop();
    super.onDestroy();
  }
}
