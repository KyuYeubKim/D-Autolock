package com.dautolock.app;

import android.bluetooth.*;
import android.content.*;
import android.os.SystemClock;
import com.dautolock.link.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Receives only paired, authenticated reads. Independent of the BLE distance scan. */
final class VehicleLink {
  private final Controller controller;
  private final LinkVault vault;
  final LinkState state = new LinkState();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private Pairing pairing;
  private String vehicle = "", address = "";
  private volatile long epoch;
  private Thread thread;
  private BluetoothSocket socket;
  volatile String status = "차량 보조 앱 미등록";

  VehicleLink(Controller c) {
    controller = c;
    vault = new LinkVault(c.context);
  }

  synchronized void restore() {
    try {
      JSONObject data = vault.read();
      if (data.has("qr")) {
        pairing = Pairing.parse(data.getString("qr"));
        vehicle = data.getString("vin");
        address = data.getString("address");
        status = "차량 보조 앱 등록됨 · 연결 대기";
      }
    } catch (Exception e) {
      status = "차량 연결키 복원 실패 · QR을 다시 등록하세요";
    }
  }

  boolean required() {
    return controller.settings.getBoolean("vehicleLinkRequired", false);
  }

  synchronized boolean configured() {
    return pairing != null && !vehicle.isEmpty() && vehicle.equals(controller.vin);
  }

  synchronized void configure(String qr, String target) throws Exception {
    if (controller.vin.isEmpty()) throw new Exception("BYD 차량을 먼저 선택하세요");
    Pairing next = Pairing.parse(qr);
    if (!BluetoothAdapter.checkBluetoothAddress(target))
      throw new Exception("차량 Bluetooth 기기를 선택하세요");
    stop();
    // Set the guard before committing: storage failure must never fall back to the old guard.
    if (!controller.settings.edit().putBoolean("vehicleLinkRequired", true).commit())
      throw new Exception("차량 연결 설정 저장 실패");
    vault.save(
        new JSONObject().put("qr", next.qr()).put("vin", controller.vin).put("address", target));
    pairing = next;
    vehicle = controller.vin;
    address = target;
    status = "QR 등록 완료 · 연결 대기";
    controller.diagnostics.record("VEHICLE_LINK_PAIRED", "vehicleBound=true");
  }

  synchronized void forget() throws Exception {
    stop();
    vault.clear();
    pairing = null;
    vehicle = "";
    address = "";
    // Requiring a new pairing is deliberate. Removing a source does not authorize a Stop.
    status = "차량 보조 앱 연결 해제 · 자동 Stop에는 QR 재등록 필요";
  }

  synchronized String block() {
    if (!configured()) return "선택 차량의 보조 앱을 QR로 연결하세요";
    return state.block(SystemClock.elapsedRealtime());
  }

  synchronized int brake() {
    LinkProtocol.Sample s = configured() ? state.fresh(SystemClock.elapsedRealtime()) : null;
    return s == null ? -1 : s.brake;
  }

  synchronized String automaticStopBlock(com.dautolock.app.core.VehicleSnapshot snapshot) {
    if (!configured()) return "선택 차량의 보조 앱을 QR로 연결하세요";
    long now = SystemClock.elapsedRealtime();
    String block = state.block(now);
    if (block != null) return block;
    LinkProtocol.Sample sample = state.fresh(now);
    if (sample == null) return "차량 P단 최신 수신 없음";
    return snapshot.automaticStopBlock(System.currentTimeMillis(), true, sample.brake);
  }

  synchronized String describe() {
    return status + "\n" + state.describe(SystemClock.elapsedRealtime());
  }

  synchronized void start() {
    if (thread != null && thread.isAlive()) return;
    if (!configured()) {
      status = "선택 차량에 맞는 QR 등록 필요";
      return;
    }
    long ticket = ++epoch;
    Pairing pair = pairing;
    String target = address;
    thread = new Thread(() -> receive(ticket, pair, target), "vehicle-state-client");
    thread.start();
  }

  synchronized void stop() {
    epoch++;
    state.clear();
    close(socket);
    socket = null;
    if (thread != null) thread.interrupt();
    thread = null;
    status = "차량 상태 연결 중지";
  }

  private synchronized boolean attach(long ticket, BluetoothSocket next) {
    if (ticket != epoch) {
      close(next);
      return false;
    }
    socket = next;
    return true;
  }

  private void receive(long ticket, Pairing pair, String target) {
    String last = "";
    long lastLog = -15000;
    while (ticket == epoch) {
      BluetoothSocket active = null;
      try {
        if (android.os.Build.VERSION.SDK_INT >= 31
            && controller.context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
          throw new SecurityException("Bluetooth permission");
        BluetoothManager manager = controller.context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth off");
        BluetoothDevice peer = adapter.getRemoteDevice(target);
        if (peer.getBondState() != BluetoothDevice.BOND_BONDED)
          throw new IOException("Pairing required");
        status = "차량 보조 앱 연결 중";
        active = peer.createRfcommSocketToServiceRecord(pair.service);
        if (!attach(ticket, active)) break;
        BluetoothSocket connection = active;
        ScheduledFuture<?> connectTimeout =
            timer.schedule(() -> close(connection), 15, TimeUnit.SECONDS);
        try {
          active.connect();
        } finally {
          connectTimeout.cancel(false);
        }
        while (ticket == epoch) {
          byte[] nonce = LinkProtocol.nonce();
          long requested = SystemClock.elapsedRealtime();
          ScheduledFuture<?> deadline =
              timer.schedule(() -> close(connection), 2500, TimeUnit.MILLISECONDS);
          LinkProtocol.Sample sample;
          try {
            LinkProtocol.request(active.getOutputStream(), pair.key, nonce);
            sample = LinkProtocol.readResponse(active.getInputStream(), pair.key, nonce);
          } finally {
            deadline.cancel(false);
          }
          long now = SystemClock.elapsedRealtime();
          synchronized (this) {
            if (ticket != epoch) break;
            if (!state.accept(sample, requested, now))
              throw new IOException("Vehicle read too slow");
            status = "차량 보조 앱 인증 연결됨";
          }
          controller.vehicleSample(sample);
          String diagnostic = sample.diagnostic();
          if (!diagnostic.equals(last) || now - lastLog >= 15000) {
            controller.diagnostics.record(
                "VEHICLE_LINK_SAMPLE", diagnostic + " rttMs=" + (now - requested));
            last = diagnostic;
            lastLog = now;
          }
          controller.changed();
          Thread.sleep(500);
        }
      } catch (Exception e) {
        synchronized (this) {
          if (ticket != epoch) break;
          state.clear();
          status = "차량 보조 앱 미연결 · 전원·페어링·QR 확인 (" + e.getClass().getSimpleName() + ")";
          controller.diagnostics.record(
              "VEHICLE_LINK_DISCONNECTED", "reason=" + e.getClass().getSimpleName());
        }
        controller.changed();
      } finally {
        close(active);
        synchronized (this) {
          if (ticket == epoch) {
            socket = null;
            state.clear();
          }
        }
      }
      if (ticket == epoch)
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          break;
        }
    }
  }

  private static void close(Closeable c) {
    if (c != null)
      try {
        c.close();
      } catch (IOException ignored) {
      }
  }
}
