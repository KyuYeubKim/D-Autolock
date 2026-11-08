package com.dautolock.app.watchble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Stage B manual BLE lock/unlock test. Connects to the car's BLE MAC over raw {@link BluetoothGatt}
 * (no Nordic library), runs the reference handshake with the issued dkey — wake-up → random-exchange
 * → authenticate → one control command — and reports the vehicle's result. Manual and one-shot: it
 * is invoked only from the settings test buttons, never from the automatic door logic, and the cloud
 * path is unchanged. The {@link WatchBleCodec} supplies the exact frames/crypto.
 *
 * <p>Diagnostics log the handshake progress and the vehicle result code (never the dkey). This is a
 * destructive action against the car (it actually locks/unlocks and leaves a BLE bond), so it runs
 * only on the user's own car with their own issued key.
 */
@SuppressLint("MissingPermission") // start() checks BLUETOOTH_CONNECT before any GATT call.
public final class WatchBleClient {

  public interface Listener {
    void onProgress(String message);

    void onResult(boolean success, String message, int doorStates);
  }

  private static final long STEP_TIMEOUT_MS = 6000;
  private static final long OVERALL_TIMEOUT_MS = 35000;
  private static final long RANDOM_FRAME_DELAY_MS = 200;
  private static final long AUTH_FRAME_DELAY_MS = 180;

  private enum Step {
    IDLE,
    CONNECTING,
    DISCOVERING,
    ENABLING,
    WAKE,
    RANDOM,
    AUTH,
    CONTROL,
    DONE
  }

  private final Context context;
  private final BiConsumer<String, String> diag;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final WatchBleCodec codec = new WatchBleCodec();

  private Listener listener;
  private String dkey;
  private byte controlByte;
  private byte keyState;

  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic sendCh;
  private BluetoothGattCharacteristic recvCh;
  private volatile Step step = Step.IDLE;
  private boolean finished;

  private final byte[] rxBuf = new byte[4096];
  private int rxLen;

  private Runnable stepTimeout;
  private Runnable overallTimeout;

  public WatchBleClient(Context context, BiConsumer<String, String> diag) {
    this.context = context.getApplicationContext();
    this.diag = diag;
  }

  /** Begin the test. {@code controlByte} is {@link WatchBleCodec#UNLOCK}/{@link WatchBleCodec#LOCK}. */
  public void start(String dkey, String mac, String keyNumber, byte controlByte, Listener listener) {
    this.listener = listener;
    this.dkey = dkey == null ? "" : dkey.trim();
    this.controlByte = controlByte;
    this.keyState = parseKeyState(keyNumber);

    if (!WatchBleCodec.isValidDkey(this.dkey)) {
      fail("발급된 dkey가 올바르지 않습니다");
      return;
    }
    if (mac == null || mac.trim().isEmpty()) {
      fail("블루투스 MAC이 없습니다");
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      fail("블루투스 연결 권한이 필요합니다");
      return;
    }
    BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    if (adapter == null || !adapter.isEnabled()) {
      fail("블루투스가 꺼져 있습니다");
      return;
    }

    BluetoothDevice device;
    try {
      device = adapter.getRemoteDevice(mac.trim());
    } catch (Exception e) {
      fail("MAC 주소가 올바르지 않습니다");
      return;
    }
    diag("WATCHBLE_START", "mac=" + WatchKeyMask.mac(mac) + " bond=" + bondName(device) + " cmd=" + (controlByte == WatchBleCodec.LOCK ? "lock" : "unlock"));
    codec.clearSession();
    step = Step.CONNECTING;
    progress("연결 중…");
    overallTimeout = this::onOverallTimeout;
    main.postDelayed(overallTimeout, OVERALL_TIMEOUT_MS);
    try {
      gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    } catch (SecurityException e) {
      fail("블루투스 연결 권한이 필요합니다");
    }
  }

  public void cancel() {
    main.post(() -> finish(false, "취소됨", -1));
  }

  private final BluetoothGattCallback callback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
          main.post(
              () -> {
                if (finished) return;
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                  diag("WATCHBLE_GATT", "connected status=" + status);
                  step = Step.DISCOVERING;
                  progress("서비스 확인 중…");
                  safeDiscover();
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                  diag("WATCHBLE_GATT", "disconnected status=" + status + " step=" + step);
                  if (!finished) fail("연결이 끊겼습니다 (status=" + status + ")");
                }
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
          main.post(() -> onServices(status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
          main.post(
              () -> {
                if (finished || step != Step.ENABLING) return;
                diag("WATCHBLE_NOTIFY", "cccd status=" + status);
                startHandshake();
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
          main.post(() -> onWrite(status));
        }

        // Android 13+ notification callback.
        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
          final byte[] copy = value == null ? new byte[0] : Arrays.copyOf(value, value.length);
          main.post(() -> onNotify(copy));
        }

        // Pre-13 notification callback.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
          if (Build.VERSION.SDK_INT >= 33) return; // Handled by the value overload.
          byte[] v = ch.getValue();
          final byte[] copy = v == null ? new byte[0] : Arrays.copyOf(v, v.length);
          main.post(() -> onNotify(copy));
        }
      };

  private void onServices(int status) {
    if (finished) return;
    if (status != BluetoothGatt.GATT_SUCCESS) {
      fail("서비스 검색 실패 (status=" + status + ")");
      return;
    }
    BluetoothGattService svc = gatt.getService(UUID.fromString(WatchBleCodec.SERVICE_UUID));
    if (svc == null) {
      fail("차량 BLE 서비스를 찾지 못했습니다");
      return;
    }
    sendCh = svc.getCharacteristic(UUID.fromString(WatchBleCodec.SEND_CHARACTERISTIC_UUID));
    recvCh = svc.getCharacteristic(UUID.fromString(WatchBleCodec.RECEIVE_CHARACTERISTIC_UUID));
    if (sendCh == null || recvCh == null) {
      fail("차량 BLE 특성을 찾지 못했습니다");
      return;
    }
    step = Step.ENABLING;
    progress("알림 활성화 중…");
    try {
      gatt.setCharacteristicNotification(recvCh, true);
      BluetoothGattDescriptor cccd = recvCh.getDescriptor(UUID.fromString(WatchBleCodec.CCCD_UUID));
      if (cccd == null) {
        fail("알림 설정자(CCCD)를 찾지 못했습니다");
        return;
      }
      if (Build.VERSION.SDK_INT >= 33) {
        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
      } else {
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gatt.writeDescriptor(cccd);
      }
    } catch (SecurityException e) {
      fail("블루투스 연결 권한이 필요합니다");
    }
  }

  private void startHandshake() {
    step = Step.WAKE;
    progress("차량 깨우는 중…");
    writeFrame(codec.createWakeUpFrame());
  }

  private void onWrite(int status) {
    if (finished) return;
    if (status != BluetoothGatt.GATT_SUCCESS) {
      fail("프레임 전송 실패 (status=" + status + ", step=" + step + ")");
      return;
    }
    switch (step) {
      case WAKE:
        main.postDelayed(
            () -> {
              if (finished) return;
              step = Step.RANDOM;
              progress("키 교환 중…");
              armStepTimeout();
              writeFrame(codec.createRandomExchangeFrame(keyState));
            },
            RANDOM_FRAME_DELAY_MS);
        break;
      case RANDOM:
      case AUTH:
      case CONTROL:
        // Written; now waiting for the vehicle's response (timeout already armed).
        break;
      default:
        break;
    }
  }

  private void onNotify(byte[] value) {
    if (finished || value.length == 0) return;
    appendRx(value);
    byte[] payload;
    int[] consumed = new int[1];
    while ((payload = WatchBleCodec.extractPayload(rxBuf, rxLen, consumed)) != null) {
      dropRx(consumed[0]);
      handlePayload(payload);
      if (finished) return;
    }
    dropRx(consumed[0]);
  }

  private void handlePayload(byte[] p) {
    int type = codec.parseResponseType(p);
    if (step == Step.RANDOM && type == WatchBleCodec.RESPONSE_RANDOM_EXCHANGE) {
      int[] r = codec.parseRandomExchange(p);
      if (r == null) {
        fail("키 교환 응답이 올바르지 않습니다");
        return;
      }
      int ks = r[0];
      int crc = r[1];
      diag("WATCHBLE_RANDOM", "keyState=" + ks + " crc=" + crc);
      if (crc != 1) {
        fail("키 교환 CRC 오류");
        return;
      }
      if (ks != 1) {
        fail("블루투스 키가 활성 상태가 아닙니다 (keyState=" + ks + ")");
        return;
      }
      clearStepTimeout();
      main.postDelayed(
          () -> {
            if (finished) return;
            step = Step.AUTH;
            progress("인증 중…");
            armStepTimeout();
            try {
              writeFrame(codec.createAuthenticationFrame(dkey));
            } catch (RuntimeException e) {
              fail("인증 프레임 생성 실패: " + e.getMessage());
            }
          },
          AUTH_FRAME_DELAY_MS);
    } else if (step == Step.AUTH && type == WatchBleCodec.RESPONSE_AUTHENTICATION) {
      int result = codec.parseAuthenticationResult(p);
      diag("WATCHBLE_AUTH", "result=" + result);
      if (result != 1) {
        fail("차량이 인증을 거부했습니다 (result=" + result + ")");
        return;
      }
      clearStepTimeout();
      step = Step.CONTROL;
      progress(controlByte == WatchBleCodec.LOCK ? "잠금 명령 전송 중…" : "해제 명령 전송 중…");
      armStepTimeout();
      writeFrame(codec.createControlFrame(controlByte));
    } else if (step == Step.CONTROL && type == WatchBleCodec.RESPONSE_CONTROL) {
      int cmdType = codec.parseResponseCommandType(p);
      int code = codec.parseControlCode(p);
      if (cmdType != WatchBleCodec.CONTROL_COMMAND_TYPE || code != (controlByte & 0xFF)) {
        return; // Not our command's response; keep waiting.
      }
      int result = codec.parseControlResult(p);
      int doors = codec.parseDoorStates(p);
      diag("WATCHBLE_CONTROL", "code=" + code + " result=" + result + " doors=" + doors);
      clearStepTimeout();
      if (result == 1) {
        finish(true, (controlByte == WatchBleCodec.LOCK ? "잠금" : "해제") + " 성공 (BLE)", doors);
      } else {
        finish(false, "차량이 명령을 거부했습니다 (result=" + result + ")", doors);
      }
    }
  }

  // --- transport helpers -------------------------------------------------------------------------

  private void writeFrame(byte[] frame) {
    if (finished || gatt == null || sendCh == null || frame == null) return;
    try {
      int props = sendCh.getProperties();
      int writeType =
          (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
              ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
              : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
      if (Build.VERSION.SDK_INT >= 33) {
        gatt.writeCharacteristic(sendCh, frame, writeType);
      } else {
        sendCh.setWriteType(writeType);
        sendCh.setValue(frame);
        gatt.writeCharacteristic(sendCh);
      }
    } catch (SecurityException e) {
      fail("블루투스 연결 권한이 필요합니다");
    }
  }

  private void safeDiscover() {
    try {
      // A short settle before discovery improves reliability on some controllers.
      main.postDelayed(
          () -> {
            if (finished || gatt == null) return;
            try {
              gatt.discoverServices();
            } catch (SecurityException e) {
              fail("블루투스 연결 권한이 필요합니다");
            }
          },
          300);
    } catch (Throwable t) {
      fail("서비스 검색을 시작하지 못했습니다");
    }
  }

  private void appendRx(byte[] v) {
    if (rxLen + v.length > rxBuf.length) {
      // Keep only the tail that could still start a frame.
      int keep = Math.min(rxLen, 19);
      System.arraycopy(rxBuf, rxLen - keep, rxBuf, 0, keep);
      rxLen = keep;
      if (keep + v.length > rxBuf.length) return; // Oversized; drop.
    }
    System.arraycopy(v, 0, rxBuf, rxLen, v.length);
    rxLen += v.length;
  }

  private void dropRx(int n) {
    if (n <= 0) return;
    if (n >= rxLen) {
      rxLen = 0;
      return;
    }
    System.arraycopy(rxBuf, n, rxBuf, 0, rxLen - n);
    rxLen -= n;
  }

  // --- timeouts & finish -------------------------------------------------------------------------

  private void armStepTimeout() {
    clearStepTimeout();
    final Step waiting = step;
    stepTimeout =
        () -> {
          if (!finished && step == waiting) fail(stepName(waiting) + " 응답 시간이 초과되었습니다");
        };
    main.postDelayed(stepTimeout, STEP_TIMEOUT_MS);
  }

  private void clearStepTimeout() {
    if (stepTimeout != null) {
      main.removeCallbacks(stepTimeout);
      stepTimeout = null;
    }
  }

  private void onOverallTimeout() {
    if (!finished) fail("시간 초과 (step=" + step + ")");
  }

  private void fail(String message) {
    finish(false, message, -1);
  }

  private void finish(boolean success, String message, int doorStates) {
    if (finished) return;
    finished = true;
    step = Step.DONE;
    clearStepTimeout();
    if (overallTimeout != null) {
      main.removeCallbacks(overallTimeout);
      overallTimeout = null;
    }
    diag("WATCHBLE_RESULT", (success ? "ok" : "fail") + " · " + message);
    try {
      if (gatt != null) {
        gatt.disconnect();
        gatt.close();
      }
    } catch (Throwable ignore) {
      // Best effort.
    }
    gatt = null;
    final Listener l = listener;
    if (l != null) l.onResult(success, message, doorStates);
  }

  private void progress(String message) {
    Listener l = listener;
    if (l != null) l.onProgress(message);
  }

  private void diag(String event, String detail) {
    if (diag != null) diag.accept(event, detail);
  }

  private static byte parseKeyState(String keyNumber) {
    try {
      long n = Long.parseLong(keyNumber == null ? "0" : keyNumber.trim());
      return (byte) Math.max(0, Math.min(255, n));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String stepName(Step s) {
    switch (s) {
      case RANDOM:
        return "키 교환";
      case AUTH:
        return "인증";
      case CONTROL:
        return "명령";
      default:
        return String.valueOf(s);
    }
  }

  private String bondName(BluetoothDevice d) {
    try {
      int b = d.getBondState();
      return b == BluetoothDevice.BOND_BONDED ? "bonded" : b == BluetoothDevice.BOND_BONDING ? "bonding" : "none";
    } catch (Throwable t) {
      return "?";
    }
  }

  /** Local MAC masking for logs (no dependency on the watchkey package). */
  private static final class WatchKeyMask {
    static String mac(String m) {
      if (m == null || m.length() < 5) return "??";
      return "··:" + m.substring(m.length() - 5);
    }
  }
}
