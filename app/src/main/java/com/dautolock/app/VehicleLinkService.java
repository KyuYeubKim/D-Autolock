package com.dautolock.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

public final class VehicleLinkService extends Service {
  private Controller controller;

  public void onCreate() {
    super.onCreate();
    controller = ((DApplication) getApplication()).controller();
    // Share ProximityService's single ongoing notification (same id + channel) so the user never
    // sees a second persistent notification when both services run.
    Notification notification = DoorNotifications.ongoing(this, controller.autoEnabled);
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          DoorNotifications.ONGOING_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    else startForeground(DoorNotifications.ONGOING_ID, notification);
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
