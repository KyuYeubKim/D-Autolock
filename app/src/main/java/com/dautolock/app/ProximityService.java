package com.dautolock.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.dautolock.app.api.CloudClient;
import com.dautolock.app.core.ProximityEngine;
import java.util.*;

public final class ProximityService extends Service {
  private Controller controller;
  private BluetoothLeScanner scanner;
  private ProximityEngine engine;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long lastUi;
  private volatile boolean scanning;
  private final Object engineLock = new Object();
  private final BroadcastReceiver radio =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())
              && i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
            controller.note("블루투스가 꺼져 관찰을 종료했습니다");
            stopSelf();
          }
        }
      };
  private final ScanCallback callback =
      new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
          accept(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
          for (ScanResult result : results) accept(result);
        }

        @Override
        public void onScanFailed(int code) {
          controller.note("블루투스 관찰 실패 (" + code + ") · 잠시 후 다시 시작하세요");
          stopSelf();
        }
      };
  private final Runnable heartbeat =
      new Runnable() {
        public void run() {
          if (!scanning) return;
          synchronized (engineLock) {
            controller.signal =
                engine.zone(SystemClock.elapsedRealtime())
                    + (Double.isNaN(engine.rssi())
                        ? ""
                        : " · " + Math.round(engine.rssi()) + " dBm");
          }
          controller.changed();
          handler.postDelayed(this, 2000);
        }
      };

  private void accept(ScanResult result) {
    if (!scanning || engine == null) return;
    // Ignore buffered advertisements; arrival time alone is not freshness evidence.
    long now = SystemClock.elapsedRealtime();
    long sampleAt = result.getTimestampNanos() / 1000000;
    if (now - sampleAt > 3000 || sampleAt > now) return;
    ProximityEngine.Action action;
    synchronized (engineLock) {
      action = engine.sample(result.getRssi(), sampleAt);
    }
    if (now - lastUi > 800) {
      lastUi = now;
      controller.changed();
    }
    if (action == ProximityEngine.Action.NONE) return;
    if (!controller.autoEnabled) {
      controller.note(action == ProximityEngine.Action.UNLOCK ? "접근 감지 · 관찰 모드" : "이탈 감지 · 관찰 모드");
      return;
    }
    CloudClient.Command command =
        action == ProximityEngine.Action.UNLOCK
            ? CloudClient.Command.UNLOCK
            : CloudClient.Command.LOCK;
    controller.command(
        command,
        true,
        () -> {
          synchronized (engineLock) {
            return scanning && engine.stillValid(action, SystemClock.elapsedRealtime());
          }
        });
  }

  @Override
  public void onCreate() {
    super.onCreate();
    controller = ((DApplication) getApplication()).controller();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("proximity", "차량 거리 관찰", NotificationManager.IMPORTANCE_LOW));
    IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(radio, filter, Context.RECEIVER_EXPORTED);
    else registerReceiver(radio, filter);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "STOP".equals(intent.getAction())) {
      controller.note("거리 관찰을 종료했습니다");
      stopSelf();
      return START_NOT_STICKY;
    }
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, ProximityService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification =
        new Notification.Builder(this, "proximity")
            .setSmallIcon(com.dautolock.app.R.drawable.ic_notification)
            .setContentTitle("D-Autolock · 거리 관찰 중")
            .setContentText("자동 제어 상태는 앱에서 확인하세요")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "관찰 종료", stop).build())
            .build();
    if (scanning) return START_NOT_STICKY;
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      } else if (Build.VERSION.SDK_INT >= 29) {
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
      } else {
        startForeground(1, notification);
      }
      if (Build.VERSION.SDK_INT >= 31
          && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                  != PackageManager.PERMISSION_GRANTED
              || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                  != PackageManager.PERMISSION_GRANTED)) throw new Exception("주변 기기 권한이 필요합니다");
      String address = controller.settings.getString("address", "");
      if (!BluetoothAdapter.checkBluetoothAddress(address))
        throw new Exception("관찰할 블루투스 기기를 선택하세요");
      BluetoothManager manager = getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) throw new Exception("휴대폰 블루투스를 켜세요");
      scanner = adapter.getBluetoothLeScanner();
      if (scanner == null) throw new Exception("BLE 관찰을 시작할 수 없습니다");
      engine =
          new ProximityEngine(
              controller.settings.getInt("near", -65), controller.settings.getInt("far", -80));
      scanner.startScan(
          Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build()),
          new ScanSettings.Builder()
              .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
              .setReportDelay(0)
              .build(),
          callback);
      scanning = true;
      controller.monitoring = true;
      controller.autoEnabled = false;
      controller.note("관찰 시작 · 첫 거리 구간에서는 도어를 제어하지 않습니다");
      handler.post(heartbeat);
    } catch (Exception e) {
      controller.note(e.getMessage() == null ? "거리 관찰을 시작하지 못했습니다" : e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    scanning = false;
    handler.removeCallbacksAndMessages(null);
    if (scanner != null)
      try {
        scanner.stopScan(callback);
      } catch (SecurityException ignored) {
      }
    try {
      unregisterReceiver(radio);
    } catch (IllegalArgumentException ignored) {
    }
    controller.monitoring = false;
    controller.autoEnabled = false;
    controller.signal = "관찰 중지";
    controller.changed();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
