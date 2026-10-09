package com.dautolock.bridge;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import com.dautolock.link.*;
import java.io.*;
import java.util.concurrent.*;

public final class BridgeService extends Service {
  static volatile String status = "차량 조회 중지", detail = "", lastSample = "";
  static volatile long sampledAt = -1;
  private volatile boolean running;
  private volatile BluetoothServerSocket server;
  private volatile BluetoothSocket socket;
  private Thread worker;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService local = Executors.newSingleThreadScheduledExecutor();
  private BydGearReader reader;

  public void onCreate() {
    super.onCreate();
    reader = new BydGearReader(this);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("bridge", "차량 상태 전달", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, BridgeActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    try {
      startForeground(
          20,
          new Notification.Builder(this, "bridge")
              .setSmallIcon(R.drawable.ic_bridge)
              .setContentTitle("D-Autolock · 차량 상태 전달")
              .setContentText("기어 조회 중 · 차량 제어는 휴대폰에서 실행")
              .setContentIntent(open)
              .setOngoing(true)
              .build());
    } catch (RuntimeException e) {
      // Missing foreground-service permission on this firmware: report instead of crashing.
      status = "백그라운드 실행 권한 확인 필요 (" + e.getClass().getSimpleName() + ")";
      stopSelf();
      return;
    }
    local.scheduleWithFixedDelay(
        () -> {
          // An exception here would silently cancel all later reads; keep the loop alive.
          try {
            LinkProtocol.Sample s = reader.read();
            lastSample = s.gearLabel();
            detail = reader.detail;
            sampledAt = SystemClock.elapsedRealtime();
          } catch (RuntimeException e) {
            detail = "기어 조회 오류 · " + e.getClass().getSimpleName();
          }
        },
        0,
        500,
        TimeUnit.MILLISECONDS);
  }

  public int onStartCommand(Intent intent, int flags, int id) {
    if (reader == null || local.isShutdown()) return START_NOT_STICKY;
    if (!running) {
      running = true;
      worker = new Thread(this::serve, "vehicle-state-server");
      worker.start();
    }
    return START_STICKY; // Restarted by the system if the head unit kills it.
  }

  private void serve() {
    while (running) {
      try {
        if (Build.VERSION.SDK_INT >= 31
            && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
          throw new SecurityException("Bluetooth permission");
        Pairing pair = Pairing.parse(new LinkVault(this).read().getString("qr"));
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth를 켜세요");
        server = adapter.listenUsingRfcommWithServiceRecord("D-Autolock Vehicle", pair.service);
        status = "휴대폰 연결 대기";
        socket = server.accept();
        server.close();
        server = null;
        if (!running) break;
        BluetoothSocket active = socket;
        BluetoothDevice peer = active.getRemoteDevice();
        String pinned = getSharedPreferences("bridge", 0).getString("peer", "");
        if (peer.getBondState() != BluetoothDevice.BOND_BONDED
            || (!pinned.isEmpty() && !pinned.equals(peer.getAddress())))
          throw new IOException("등록되지 않은 휴대폰");
        while (running) {
          ScheduledFuture<?> deadline = timer.schedule(() -> close(active), 5, TimeUnit.SECONDS);
          try {
            byte[] nonce = LinkProtocol.readRequest(active.getInputStream(), pair.key);
            if (pinned.isEmpty()) {
              pinned = peer.getAddress();
              if (!getSharedPreferences("bridge", 0).edit().putString("peer", pinned).commit())
                throw new IOException("휴대폰 등록 실패");
            }
            LinkProtocol.Sample sample =
                reader.read(); // A new getter call after each authenticated request.
            LinkProtocol.respond(active.getOutputStream(), pair.key, nonce, sample);
            status = "휴대폰 인증 연결 · 상태 전달 중";
          } finally {
            deadline.cancel(false);
          }
        }
      } catch (Exception e) {
        status =
            e instanceof SecurityException
                ? "Bluetooth 또는 차량 권한 확인 필요"
                : "연결 대기 / 재연결 · " + e.getClass().getSimpleName();
      } finally {
        close(socket);
        socket = null;
        close(server);
        server = null;
      }
      if (running)
        try {
          Thread.sleep(3000);
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

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onDestroy() {
    running = false;
    close(socket);
    close(server);
    if (worker != null) worker.interrupt();
    timer.shutdownNow();
    local.shutdownNow();
    status = "차량 조회 중지";
    sampledAt = -1;
    super.onDestroy();
  }
}
