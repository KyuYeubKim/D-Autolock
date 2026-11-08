package com.dautolock.app.watchkey;

import java.util.concurrent.Executor;

/**
 * Drives the provisioning sequence: generate QR → wait for the owner to approve it in the official
 * BYD app → gain the watch token, VIN and BLE key → store. Runs off the UI thread and posts every
 * callback through {@code main}. Secrets (dkey, tokens) are never logged — only masked/boolean
 * presence goes to diagnostics.
 */
public final class WatchKeyProvision {

  public interface Listener {
    void onStatus(String message);

    void onQrReady(QrInfo qr);

    void onApproved();

    void onIssued(String summary);

    void onError(String message);
  }

  public interface Diag {
    void record(String event, String detail);
  }

  /**
   * The {@code codeStatus} at/above which the QR is treated as approved. The exact values are not
   * known from static analysis and must be confirmed from the first real run (every value seen is
   * logged as WATCHKEY_QR_STATUS); adjust this constant once the real mapping is known.
   */
  static final int APPROVED_STATUS = 2;

  private static final long POLL_MS = 2500, APPROVAL_TIMEOUT_MS = 120000;

  private final WatchKeyClient client;
  private final WatchKeyStore store;
  private final Executor main;
  private final Diag diag;
  private volatile boolean cancelled;
  long pollMs = POLL_MS; // Overridable in tests.
  long timeoutMs = APPROVAL_TIMEOUT_MS;

  public WatchKeyProvision(WatchKeyClient client, WatchKeyStore store, Executor main, Diag diag) {
    this.client = client;
    this.store = store;
    this.main = main;
    this.diag = diag;
  }

  public void cancel() {
    cancelled = true;
  }

  public void start(Listener l) {
    Thread t = new Thread(() -> runBlocking(l), "watchkey-provision");
    t.setDaemon(true);
    t.start();
  }

  /** The sequence, run on the calling thread. Package-visible so tests drive it without a thread. */
  void runBlocking(Listener l) {
    try {
      post(() -> l.onStatus("서버 시간 확인 중…"));
      try {
        client.serverTime();
      } catch (Exception ignore) {
        // Best effort: the impl falls back to the device clock.
      }

      diag("WATCHKEY_PROVISION", "step=create_qr");
      post(() -> l.onStatus("QR 생성 중…"));
      QrInfo qr = client.createQrCode();
      if (qr == null || qr.payload.isEmpty()) {
        fail(l, "QR을 생성하지 못했습니다.");
        return;
      }
      try {
        store.saveQrUuid(qr.uuid);
      } catch (Exception ignore) {
        // Non-fatal: re-issue just needs a fresh QR.
      }
      post(() -> l.onQrReady(qr));
      post(() -> l.onStatus("공식 BYD 앱으로 이 QR을 스캔해 승인해 주세요…"));

      long deadline = System.currentTimeMillis() + timeoutMs;
      int last = Integer.MIN_VALUE;
      boolean approved = false;
      while (!cancelled && System.currentTimeMillis() < deadline) {
        sleep(pollMs);
        if (cancelled) break;
        int status;
        try {
          status = client.qrStatus(qr.uuid);
        } catch (Exception e) {
          diag("WATCHKEY_QR_POLL_ERR", String.valueOf(e.getMessage()));
          continue;
        }
        if (status != last) {
          diag("WATCHKEY_QR_STATUS", "codeStatus=" + status);
          last = status;
        }
        if (status >= APPROVED_STATUS) {
          approved = true;
          break;
        }
      }
      if (cancelled) {
        post(() -> l.onStatus("취소됨"));
        return;
      }
      if (!approved) {
        fail(l, "승인 대기 시간이 지났습니다. 공식 BYD 앱에서 QR을 승인한 뒤 다시 시도해 주세요.");
        return;
      }

      post(() -> l.onApproved());
      post(() -> l.onStatus("키 발급 중…"));
      WatchTokens tokens = client.gainToken(qr.uuid);
      if (tokens == null || !tokens.valid()) {
        fail(l, "세션 토큰을 받지 못했습니다.");
        return;
      }

      // Gain the vehicle + the BLE key. The first token right after approval can be reported as
      // expired by these two steps (9013/9012/1002/1010) while the server finishes establishing the
      // session; the reference recovers by refreshing the token (re-running gain/token from the same
      // uuid, no re-scan) and retrying. Do the same once.
      String vin = tokens.vin;
      BleKey ble = null;
      Exception lastError = null;
      for (int attempt = 1; attempt <= 2 && (ble == null || !ble.valid()); attempt++) {
        try {
          try {
            String v = client.gainVehicleVin(tokens);
            if (v != null && !v.isEmpty()) vin = v;
          } catch (Exception ve) {
            diag("WATCHKEY_VEHICLE_ERR", String.valueOf(ve.getMessage()));
            if (isTokenExpired(ve)) throw ve; // Let a token-expiry here trigger the refresh below.
          }
          ble = client.gainBluetooth(tokens);
        } catch (Exception e) {
          lastError = e;
          if (attempt == 1 && isTokenExpired(e)) {
            diag("WATCHKEY_TOKEN_REFRESH", "refresh+retry after " + e.getMessage());
            post(() -> l.onStatus("세션 갱신 중…"));
            sleep(1500);
            if (cancelled) return;
            WatchTokens refreshed = client.gainToken(qr.uuid);
            if (refreshed != null && refreshed.valid()) {
              tokens = refreshed;
              if (!tokens.vin.isEmpty()) vin = tokens.vin;
            }
          } else {
            break;
          }
        }
      }
      if (ble == null || !ble.valid()) {
        fail(l, lastError != null ? "발급 실패: " + lastError.getMessage() : "블루투스 키를 받지 못했습니다.");
        return;
      }
      final BleKey issued = ble;
      final String boundVin = vin == null ? "" : vin;

      store.saveKey(issued.dkey, issued.keyNumber, issued.macAddress, tokens, boundVin);
      diag(
          "WATCHKEY_ISSUED",
          "mac="
              + mask(issued.macAddress)
              + " keyNo="
              + (issued.keyNumber.isEmpty() ? "?" : "set")
              + " vin="
              + (boundVin.isEmpty() ? "?" : "set"));
      post(() -> l.onIssued("블루투스 키 발급 완료 · " + mask(issued.macAddress)));
    } catch (Exception e) {
      diag("WATCHKEY_ERROR", String.valueOf(e.getMessage()));
      fail(l, "발급 실패: " + e.getMessage());
    }
  }

  private void fail(Listener l, String message) {
    post(() -> l.onError(message));
  }

  private void post(Runnable r) {
    main.execute(r);
  }

  private void diag(String event, String detail) {
    if (diag != null) diag.record(event, detail);
  }

  private void sleep(long ms) {
    if (ms <= 0) return;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      cancelled = true;
    }
  }

  /** Watch token/session-expiry codes (reference {@code isTokenExpiredError}): refresh and retry. */
  static boolean isTokenExpired(Exception e) {
    String m = e == null ? "" : String.valueOf(e.getMessage());
    return m.contains("9013") || m.contains("9012") || m.contains("1002") || m.contains("1010");
  }

  /** A MAC with only the last two octets shown, for logs/UI. */
  static String mask(String mac) {
    if (mac == null || mac.length() < 5) return "??";
    return "··:" + mac.substring(mac.length() - 5);
  }
}
