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
  private volatile boolean scanning;
  private boolean autoAttempt;
  private long nextPreflight, lastDiagnostic = -15000, lastCount = -1, started, lastIgnored = -5000;
  private int deviceType;
  private String radioStatus = "BLE 검색 중";
  private final Runnable restartScan =
      () -> {
        if (!scanning) return;
        if (Build.VERSION.SDK_INT >= 31
            && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)) {
          controller.note("주변 기기 권한이 없어 관찰을 종료합니다");
          stopSelf();
          return;
        }
        try {
          BluetoothManager manager = getSystemService(BluetoothManager.class);
          BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
          if (adapter == null || !adapter.isEnabled()) return;
          if (scanner != null)
            try {
              scanner.stopScan(this.callback);
            } catch (SecurityException ignored) {
            }
          scanner = adapter.getBluetoothLeScanner();
          if (scanner == null) throw new IllegalStateException("scanner unavailable");
          scanner.startScan(
              Collections.singletonList(
                  new ScanFilter.Builder()
                      .setDeviceAddress(controller.settings.getString("address", ""))
                      .build()),
              new ScanSettings.Builder()
                  .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                  .setReportDelay(0)
                  .build(),
              this.callback);
          radioStatus = "BLE 검색 중";
          controller.diagnostics.record("SCAN_RESTART", "requested=true");
        } catch (SecurityException e) {
          controller.note("주변 기기 권한이 없어 관찰을 종료합니다");
          controller.diagnostics.record("SCAN_PERMISSION_DENIED", "restart=true");
          stopSelf();
        } catch (Exception e) {
          radioStatus = "BLE 재검색 실패";
          controller.diagnostics.record("SCAN_RESTART_ERROR", e.getClass().getSimpleName());
        }
      };
  private final BroadcastReceiver radio =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())) {
            int state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF) {
              radioStatus = "휴대폰 Bluetooth OFF";
              controller.note("블루투스 OFF · 이전 수신 기록이 있으면 신호 끊김 잠금 조건을 계속 확인합니다");
            } else if (state == BluetoothAdapter.STATE_ON) {
              handler.removeCallbacks(restartScan);
              handler.postDelayed(restartScan, 1000);
            }
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
          controller.diagnostics.record("SCAN_FAILED", "code=" + code);
          radioStatus = "BLE 검색 실패 (" + code + ")";
          controller.note(radioStatus + " · 30초 후 재검색. 이전 수신 기록의 신호 끊김 잠금 조건은 유지됩니다");
          handler.removeCallbacks(restartScan);
          handler.postDelayed(restartScan, 30000);
        }
      };
  private final Runnable heartbeat =
      new Runnable() {
        public void run() {
          if (!scanning) return;
          long now = SystemClock.elapsedRealtime();
          boolean fresh = engine.fresh(now);
          controller.signal =
              engine.zone(now) + (fresh ? " · " + engine.raw() + " dBm" : " · — dBm");
          controller.signalStrength = fresh ? ProximityEngine.strength(engine.rssi()) : 0;
          controller.signalDetail =
              "현재 "
                  + (fresh ? engine.raw() + " dBm" : "미수신")
                  + " / 평균 "
                  + (fresh ? Math.round(engine.rssi()) + " dBm" : "—")
                  + "\n수신 "
                  + engine.count()
                  + "회 · 마지막 수신 "
                  + (engine.age(now) < 0 ? "없음" : engine.age(now) / 1000 + "초 전")
                  + "\n접근 ≥ "
                  + controller.settings.getInt("near", -65)
                  + " / 이탈 ≤ "
                  + controller.settings.getInt("far", -80)
                  + " dBm\n접근 대기 "
                  + controller.settings.getInt("nearWaitSeconds", 3)
                  + "초 / 이탈 대기 "
                  + controller.settings.getInt("farWaitSeconds", 8)
                  + "초 / 신호 끊김 "
                  + controller.settings.getInt("lossLockSeconds", 10)
                  + "초";
          controller.signalDetail += "\n" + radioStatus;
          if (engine.count() == 0 && now - started >= 10000)
            controller.signalDetail +=
                "\n"
                    + (deviceType == BluetoothDevice.DEVICE_TYPE_CLASSIC
                        ? "선택 기기는 일반 Bluetooth입니다. 차량 BLE 기기를 다시 검색하세요."
                        : "선택 주소에서 BLE 광고를 받지 못했습니다. 차량 기기·권한·전원 OFF 시 광고 여부를 확인하세요.");
          controller.autoDetail =
              (controller.autoEnabled ? "자동 제어 ON · " : "관찰 모드 · 자동 제어 OFF\n") + engine.reason(now);
          if (controller.autoEnabled) {
            if (autoAttempt) controller.autoDetail += "\n차량 상태 조회 / 명령 결과 확인 중";
            else if (nextPreflight > now)
              controller.autoDetail +=
                  "\n미전송 조건 재검토까지 " + ((nextPreflight - now + 999) / 1000) + "초";
            else if (controller.busy()) controller.autoDetail += "\n다른 요청 완료 대기 · 감지 조건 유지";
          }
          if (now - lastDiagnostic >= 1000
              && (engine.count() != lastCount || now - lastDiagnostic >= 15000)) {
            controller.diagnostics.record(
                "SCAN_STATUS",
                engine.diagnostic(now)
                    + " auto="
                    + controller.autoEnabled
                    + " busy="
                    + controller.busy()
                    + " "
                    + engine.reason(now));
            lastDiagnostic = now;
            lastCount = engine.count();
          }
          ProximityEngine.Action action = engine.pending(now);
          if (controller.autoEnabled
              && !autoAttempt
              && now >= nextPreflight
              && action != ProximityEngine.Action.NONE) {
            CloudClient.Command command =
                action == ProximityEngine.Action.UNLOCK
                    ? CloudClient.Command.UNLOCK
                    : CloudClient.Command.LOCK;
            autoAttempt = true;
            boolean accepted =
                controller.automaticCommand(
                    command,
                    () -> scanning && engine.stillValid(action, SystemClock.elapsedRealtime()),
                    () -> scanning && engine.claim(action, SystemClock.elapsedRealtime()),
                    () -> engine.alreadySatisfied(action, SystemClock.elapsedRealtime()),
                    () ->
                        handler.post(
                            () -> {
                              autoAttempt = false;
                              long completed = SystemClock.elapsedRealtime();
                              nextPreflight =
                                  engine.pending(completed) == action ? completed + 15000 : 0;
                            }));
            if (!accepted) autoAttempt = false;
            else controller.diagnostics.record("AUTO_CHECK", action + " " + engine.diagnostic(now));
          }
          if (!autoAttempt && !controller.busy())
            controller.pollEntry(
                () ->
                    scanning
                        && engine.fresh(SystemClock.elapsedRealtime())
                        && engine.rssi() > controller.settings.getInt("far", -80));
          controller.changed();
          handler.postDelayed(this, 1000);
        }
      };

  private void accept(ScanResult result) {
    if (!scanning || engine == null) return;
    long now = SystemClock.elapsedRealtime(), sampleAt = result.getTimestampNanos() / 1000000;
    if (now - sampleAt > 3000 || sampleAt > now) {
      if (now - lastIgnored > 5000) {
        lastIgnored = now;
        controller.diagnostics.record("SAMPLE_IGNORED", "ageMs=" + (now - sampleAt));
      }
      return;
    }
    engine.sample(result.getRssi(), sampleAt);
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
    if (scanning) return START_NOT_STICKY;
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("D-Autolock · 거리 관찰 중")
            .setContentText("신호·자동 제어 상태와 진단 로그는 앱에서 확인하세요")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "관찰 종료", stop).build())
            .build();
    try {
      if (Build.VERSION.SDK_INT >= 31)
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      else if (Build.VERSION.SDK_INT >= 29)
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
      else startForeground(1, notification);
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
      deviceType = adapter.getRemoteDevice(address).getType();
      scanner = adapter.getBluetoothLeScanner();
      if (scanner == null) throw new Exception("BLE 관찰을 시작할 수 없습니다");
      engine = controller.proximityEngine();
      started = SystemClock.elapsedRealtime();
      scanning = true;
      scanner.startScan(
          Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build()),
          new ScanSettings.Builder()
              .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
              .setReportDelay(0)
              .build(),
          callback);
      controller.monitoring = true;
      controller.autoEnabled = false;
      PowerManager pm = getSystemService(PowerManager.class);
      controller.diagnostics.record(
          "SCAN_START",
          "type="
              + deviceType
              + " batteryUnrestricted="
              + (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()))
              + " scanPermission=true lossLockSeconds="
              + controller.settings.getInt("lossLockSeconds", 10)
              + " nearWaitSeconds="
              + controller.settings.getInt("nearWaitSeconds", 3)
              + " farWaitSeconds="
              + controller.settings.getInt("farWaitSeconds", 8));
      controller.note("관찰 시작 · 1초마다 신호와 판단 상태를 표시하고 진단 로그에 저장합니다");
      controller.prepareMonitoring();
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
    controller.signal = "관찰 중지 · — dBm";
    controller.signalStrength = 0;
    controller.signalDetail = "거리 관찰을 시작하면 신호를 표시합니다";
    controller.autoDetail = "자동 제어 꺼짐";
    controller.diagnostics.record("SCAN_STOP", "monitoring=false auto=false");
    controller.changed();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
