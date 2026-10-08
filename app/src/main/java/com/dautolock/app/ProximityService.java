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
  private volatile ProximityEngine engine;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private volatile boolean scanning;
  private boolean autoAttempt;
  private boolean notificationAutomatic;
  private long lastScanAttempt;
  private long nextUnlockCheck;
  private long watchdogIntervalMs = 120000;
  private String lastDiagnosticReason = "";
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
          lastScanAttempt = SystemClock.elapsedRealtime();
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
          if ((engine.age(now) >= 60000 || (engine.count() == 0 && now - started >= 60000))
              && now - lastScanAttempt >= watchdogIntervalMs) {
            lastScanAttempt = now;
            controller.diagnostics.record(
                "SCAN_WATCHDOG",
                "noSamplesMs=" + engine.age(now) + " restart=true nextMs=" + watchdogIntervalMs);
            restartScan.run();
            // Out of range for long: restarting every 2 minutes only costs battery. Back off to 16 min.
            watchdogIntervalMs = Math.min(960000, watchdogIntervalMs * 2);
          }
          if (notificationAutomatic != controller.autoEnabled) {
            notificationAutomatic = controller.autoEnabled;
            getSystemService(NotificationManager.class)
                .notify(1, DoorNotifications.ongoing(ProximityService.this, notificationAutomatic));
          }
          boolean fresh = engine.fresh(now);
          controller.averageRssi = fresh ? engine.rssi() : Double.NaN;
          long cloudWait = controller.cloud.backoffMillis();
          controller.signal =
              engine.zone(now) + (fresh ? " · " + engine.raw() + " dBm" : " · — dBm");
          controller.signalStrength = fresh ? ProximityEngine.strength(engine.rssi()) : 0;
          controller.signalDetail =
              "평균 "
                  + (fresh ? Math.round(engine.rssi()) + " dBm" : "—")
                  + " · 수신 "
                  + engine.count()
                  + "회 · "
                  + (engine.age(now) < 0 ? "없음" : engine.age(now) / 1000 + "초 전")
                  + "\n접근 ≥ "
                  + controller.settings.getInt("near", Controller.DEFAULT_NEAR)
                  + " / 이탈 ≤ "
                  + controller.settings.getInt("far", Controller.DEFAULT_FAR)
                  + " dBm\n대기 "
                  + controller.settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT)
                  + "초 / "
                  + controller.settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT)
                  + "초 · 신호 끊김 "
                  + controller.settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS)
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
            else if (cloudWait > 0)
              controller.autoDetail +=
                  "\nBYD 1008 · 재확인까지 " + ((cloudWait + 999) / 1000) + "초 · 계정 유지";
            else if (nextPreflight > now)
              controller.autoDetail +=
                  "\n미전송 조건 재검토까지 " + ((nextPreflight - now + 999) / 1000) + "초";
            else if (controller.busy()) controller.autoDetail += "\n다른 요청 완료 대기 · 감지 조건 유지";
          }
          // One line per second filled ~90% of the bounded log. Record state changes and a
          // 15-second heartbeat instead (decisions are logged separately as PROXIMITY_READY etc.).
          String diagnosticReason = engine.zone(now) + "|" + engine.reason(now) + "|" + controller.busy();
          if (now - lastDiagnostic >= 1000
              && (!diagnosticReason.equals(lastDiagnosticReason) || now - lastDiagnostic >= 15000)) {
            lastDiagnosticReason = diagnosticReason;
            controller.diagnostics.record(
                "SCAN_STATUS",
                engine.diagnostic(now)
                    + " auto="
                    + controller.autoEnabled
                    + " busy="
                    + controller.busy()
                    + " cloudWaitMs="
                    + cloudWait
                    + " "
                    + engine.reason(now));
            lastDiagnostic = now;
            lastCount = engine.count();
          }
          evaluate();
          if (!autoAttempt && !controller.busy())
            controller.pollEntry(
                () ->
                    scanning
                        && engine.fresh(SystemClock.elapsedRealtime())
                        && engine.rssi() > controller.settings.getInt("far", Controller.DEFAULT_FAR));
          controller.changed();
          handler.postDelayed(this, 1000);
        }
      };

  private ProximityEngine.Action readyAction = ProximityEngine.Action.NONE;
  private long readyAt;
  private final Runnable decisionTick =
      new Runnable() {
        @Override
        public void run() {
          if (!scanning) return;
          evaluate();
          handler.postDelayed(this, 250);
        }
      };

  /** Main-thread decisions: each BLE result immediately, with a timer for loss/busy recovery. */
  private void evaluate() {
    if (!scanning || engine == null) return;
    long now = SystemClock.elapsedRealtime();
    if (!controller.autoEnabled) {
      readyAction = ProximityEngine.Action.NONE;
      controller.discardApproachPreflight();
      return;
    }
    if (!engine.approaching(now)) controller.discardApproachPreflight();
    long cloudWait = controller.cloud.backoffMillis();
    ProximityEngine.Action pending = engine.pending(now);
    if (pending != readyAction) {
      // A departure ends the in-use backoff (signal flicker inside the car must not reset it).
      if (pending == ProximityEngine.Action.LOCK) {
        controller.unlockInUseBlocks = 0;
        nextUnlockCheck = 0;
      }
      readyAction = pending;
      readyAt = now;
      if (pending != ProximityEngine.Action.NONE)
        controller.diagnostics.record("PROXIMITY_READY", pending + " " + engine.diagnostic(now));
    }
    ProximityEngine.Action action = pending;
    if (controller.autoEnabled
        && !autoAttempt
        && cloudWait == 0
        && now >= nextPreflight
        && (action != ProximityEngine.Action.UNLOCK || now >= nextUnlockCheck)
        && action != ProximityEngine.Action.NONE) {
      CloudClient.Command command =
          action == ProximityEngine.Action.UNLOCK
              ? CloudClient.Command.UNLOCK
              : CloudClient.Command.LOCK;
      autoAttempt = true;
      final ProximityEngine checkedEngine = engine;
      checkedEngine.beginCheck(action, now);
      boolean accepted =
          controller.automaticCommand(
              command,
              () ->
                  scanning
                      && engine == checkedEngine
                      && checkedEngine.stillValid(action, SystemClock.elapsedRealtime()),
              () ->
                  scanning
                      && engine == checkedEngine
                      && checkedEngine.claim(action, SystemClock.elapsedRealtime()),
              () -> checkedEngine.alreadySatisfied(action, SystemClock.elapsedRealtime()),
              () ->
                  handler.post(
                      () -> {
                        autoAttempt = false;
                        checkedEngine.endCheck();
                        long completed = SystemClock.elapsedRealtime();
                        long recheck =
                            controller.automaticRecheckMs(action == ProximityEngine.Action.UNLOCK);
                        if (recheck > 15000)
                          controller.diagnostics.record(
                              "AUTO_RECHECK_BACKOFF",
                              action + " delayMs=" + recheck + " reason=vehicle_in_use");
                        // In-use backoff gates only unlock re-checks; a departure lock is never
                        // delayed by it.
                        nextUnlockCheck = recheck > 15000 ? completed + recheck : 0;
                        nextPreflight =
                            engine == checkedEngine && engine.pending(completed) == action
                                ? completed + 15000
                                : 0;
                        evaluate();
                      }),
              () ->
                  scanning
                      && engine == checkedEngine
                      && checkedEngine.departureConfirmed(SystemClock.elapsedRealtime()),
              readyAt);
      if (!accepted) {
        autoAttempt = false;
        checkedEngine.endCheck();
      } else
        controller.diagnostics.record(
            "AUTO_CHECK",
            action
                + " source="
                + (engine.fresh(now) ? "signal" : "signal_loss")
                + " "
                + engine.diagnostic(now));
    }
    if (!autoAttempt
        && pending == ProximityEngine.Action.NONE
        && cloudWait == 0
        && now >= nextPreflight
        && now >= nextUnlockCheck
        && !controller.busy()
        && engine.approaching(now)) {
      ProximityEngine preparingEngine = engine;
      controller.prefetchUnlock(
          () ->
              scanning
                  && engine == preparingEngine
                  && preparingEngine.approaching(SystemClock.elapsedRealtime()),
          () -> handler.post(this::evaluate));
    }
  }

  private void accept(ScanResult result) {
    if (Looper.myLooper() != handler.getLooper()) {
      handler.post(() -> accept(result));
      return;
    }
    if (!scanning || engine == null) return;
    watchdogIntervalMs = 120000; // Advertisements arrive again: restore the normal watchdog.
    long now = SystemClock.elapsedRealtime(), sampleAt = result.getTimestampNanos() / 1000000;
    if (now - sampleAt > 3000 || sampleAt > now) {
      if (now - lastIgnored > 5000) {
        lastIgnored = now;
        controller.diagnostics.record("SAMPLE_IGNORED", "ageMs=" + (now - sampleAt));
      }
      return;
    }
    engine.sample(result.getRssi(), sampleAt);
    evaluate();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    controller = ((DApplication) getApplication()).controller();
    DoorNotifications.channels(this);
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
    boolean automatic =
        intent != null && intent.getBooleanExtra("automatic", false) && controller.setupReady();
    if (scanning) {
      if (intent != null && "RECONFIGURE".equals(intent.getAction())) {
        controller.discardApproachPreflight();
        readyAction = ProximityEngine.Action.NONE;
        engine = controller.proximityEngine();
        nextPreflight = 0;
        started = SystemClock.elapsedRealtime();
        controller.averageRssi = Double.NaN;
        controller.diagnostics.record("SCAN_RECONFIGURE", "freshSamplesRequired=true");
      }
      if (intent != null && "RESCAN".equals(intent.getAction())) restartScan.run();
      if (intent != null && intent.hasExtra("automatic")) controller.autoEnabled = automatic;
      controller.changed();
      return START_NOT_STICKY;
    }
    Notification notification = DoorNotifications.ongoing(this, automatic);
    notificationAutomatic = automatic;
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
      lastScanAttempt = started;
      scanning = true;
      scanner.startScan(
          Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build()),
          new ScanSettings.Builder()
              .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
              .setReportDelay(0)
              .build(),
          callback);
      controller.monitoring = true;
      controller.autoEnabled = automatic;
      PowerManager pm = getSystemService(PowerManager.class);
      controller.diagnostics.record(
          "SCAN_START",
          "type="
              + deviceType
              + " batteryUnrestricted="
              + (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()))
              + " scanPermission=true lossLockSeconds="
              + controller.settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS)
              + " nearWaitSeconds="
              + controller.settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT)
              + " farWaitSeconds="
              + controller.settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT));
      controller.diagnostics.record(
          "AUTO_OPTIONS",
          "automatic="
              + automatic
              + " climate="
              + controller.settings.getBoolean("autoReady", false)
              + " windows="
              + controller.settings.getBoolean("closeWindows", true)
              + " stop="
              + controller.settings.getBoolean("autoStop", true));
      controller.note("관찰 시작 · 신호 수신 즉시 판단 / 접근 중 상태 미리 조회 / 화면·로그 1초 갱신");
      controller.prepareMonitoring();
      handler.post(heartbeat);
      handler.post(decisionTick);
    } catch (Exception e) {
      controller.note(e.getMessage() == null ? "거리 관찰을 시작하지 못했습니다" : e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    scanning = false;
    controller.discardApproachPreflight();
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
    controller.averageRssi = Double.NaN;
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
