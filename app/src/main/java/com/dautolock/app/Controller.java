package com.dautolock.app;

import android.content.*;
import android.os.*;
import com.dautolock.app.api.*;
import com.dautolock.app.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

final class Controller {
  final Context context;
  final android.content.SharedPreferences settings;
  final DiagnosticLog diagnostics;
  final AutoUpdater updater;
  final VehicleLink vehicleLink;
  final CarAudio carAudio = new CarAudio(this);
  private final ParkUnlockTrigger parkUnlock = new ParkUnlockTrigger();
  private final ParkedOnWatch parkedOn = new ParkedOnWatch();
  private final SecureStore store;
  private final Supplier<CloudClient> clients;
  volatile String loginUser = "";
  private volatile String loginPassword = "";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicInteger generation = new AtomicInteger();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final UnlockPreflightCache unlockPreflight = new UnlockPreflightCache();
  private volatile long nextApproachRead;
  private final AtomicInteger statusRevision = new AtomicInteger();
  private volatile boolean statusRefreshRequested;
  private volatile long statusRequestedAt, nextStatusRead;
  private final Runnable statusRefreshTask = this::refreshStatusWhenNeeded;
  volatile CloudClient cloud;

  /**
   * Stage C: the watch BLE key as a fast local actuator. Lock/unlock/engine commands are sent over
   * the issued key first (≈1.6 s, no cloud) and fall back to the cloud when the key is out of range
   * or the handshake fails. Distance is still decided by the RSSI proximity engine, and every safety
   * gate (live P, speed, door, departure, ownership) still comes from the cloud/Bridge snapshot — the
   * BLE response only reports the door-lock state. Behind the watchKeyEnabled flag (default off) plus
   * a watchKeyAuto toggle; a test may replace {@link #ble} with a fake. STOP (TURNOFFENGINE) has no
   * BLE code, so the automatic Stop path is unchanged.
   */
  interface WatchBleSender {
    boolean ready();

    com.dautolock.app.watchkey.WatchKey.BleResult send(byte controlByte, long timeoutMs);

    /** Send, and on success keep the key connected up to {@code holdMs} (start authorization). */
    default com.dautolock.app.watchkey.WatchKey.BleResult sendAndHold(
        byte controlByte, long timeoutMs, long holdMs) {
      return send(controlByte, timeoutMs);
    }

    /** End a held key connection, if any. */
    default void releaseHold(String reason) {}
  }

  static final long WATCH_BLE_AUTO_TIMEOUT_MS = 8000;
  /**
   * Car clock (second resolution) vs phone clock tolerance when deciding that a state read after an
   * unanswered BLE command was measured after the command began.
   */
  static final long BLE_PROBE_CLOCK_SLACK_MS = 3000;
  /** Extra state reads when the first read after a door/Stop command does not show the result yet. */
  static final int READBACK_RETRIES = 2;
  volatile long readbackRetryMs = 3000; // Shortened by tests.

  /**
   * Whether a read taken after a command cannot judge it yet: measured before the command was sent,
   * or without the field that shows the result (lock state; power for Stop). A read that does show
   * the opposite state is an answer, not a gap, and is not asked again.
   */
  static boolean readbackPending(VehicleSnapshot s, boolean stop, long sentAt) {
    return s == null || s.measuredAt < sentAt || (stop ? s.power == null : s.locked == null);
  }

  private com.dautolock.app.watchkey.WatchKey watchKey;
  WatchBleSender ble =
      new WatchBleSender() {
        @Override
        public boolean ready() {
          return settings.getBoolean(com.dautolock.app.watchkey.WatchKey.FLAG, false)
              && settings.getBoolean("watchKeyAuto", true)
              && watchKey().hasKey();
        }

        @Override
        public com.dautolock.app.watchkey.WatchKey.BleResult send(byte controlByte, long timeoutMs) {
          return watchKey().sendBleBlocking(controlByte, timeoutMs);
        }

        @Override
        public com.dautolock.app.watchkey.WatchKey.BleResult sendAndHold(
            byte controlByte, long timeoutMs, long holdMs) {
          return watchKey().sendBleAndHold(controlByte, timeoutMs, holdMs);
        }

        @Override
        public void releaseHold(String reason) {
          com.dautolock.app.watchkey.WatchKey key = watchKey;
          if (key != null) key.releaseHold(reason);
        }
      };

  /** End a start-authorization key connection (the car came on, or the phone has left). */
  void releaseKeyHold(String reason) {
    ble.releaseHold(reason);
  }

  private com.dautolock.app.watchkey.WatchKey watchKey() {
    if (watchKey == null)
      watchKey = new com.dautolock.app.watchkey.WatchKey(context, settings, diagnostics);
    return watchKey;
  }

  /**
   * The car's watch-key BLE MAC when the feature is on and a key is issued, else empty. Used by the
   * scanner to log the key device's advertisement RSSI — feasibility data for a future BLE-only
   * proximity mode. Observation only; it drives no decision.
   */
  String watchKeyMac() {
    if (!settings.getBoolean(com.dautolock.app.watchkey.WatchKey.FLAG, false)) return "";
    try {
      return watchKey().bleMac();
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * The key module has not advertised for this long while the scanner watched for it: it is out of
   * range, or another connection holds it (it stops advertising while connected). Real log 11-10
   * 21:29–21:35: the official BYD app's key held it through a whole drive; our lock then got a 34 ms
   * "connection" on that link, no answer, and 6 s were lost before the cloud. Such a send can only
   * fail, so the cloud is used straight away.
   */
  static final long KEY_SILENT_SKIP_MS = 60000;
  private volatile long keyWatchSince = -1, keyAdvertAt = -1;
  private final java.util.concurrent.atomic.AtomicBoolean keySilentLogged =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** The scanner started watching for the key module's advertisement (elapsed clock). */
  void keyWatchStarted(long now) {
    keyWatchSince = now;
    keySilentLogged.set(false);
  }

  void keyWatchStopped() {
    keyWatchSince = -1;
  }

  void keyAdvertSeen(long now) {
    keyAdvertAt = now;
    keySilentLogged.set(false);
  }

  /** How long the watched key module has been silent, or -1 when the scanner is not watching it. */
  long keySilentMs(long now) {
    long since = keyWatchSince;
    if (since < 0) return -1;
    // Our own key connection keeps it quiet (a held start authorization lasts up to 2 minutes):
    // that is not silence, and the key is ours to use.
    if (com.dautolock.app.watchble.BleAirtime.quiet(now)) return 0;
    return Math.max(0, now - Math.max(since, keyAdvertAt));
  }

  /** True once per silent stretch, for a single diagnostic line. */
  boolean firstKeySilentReport() {
    return keySilentLogged.compareAndSet(false, true);
  }

  /**
   * Whether this phone holds a Bluetooth LE link to the key module right now, by any app: "yes",
   * "no" or "unknown". Diagnostic only — tells a module held by another app from one out of range.
   */
  @android.annotation.SuppressLint("MissingPermission")
  String keyLinked() {
    String mac = watchKeyMac();
    if (mac.isEmpty()) return "unknown";
    try {
      if (android.os.Build.VERSION.SDK_INT >= 31
          && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
              != android.content.pm.PackageManager.PERMISSION_GRANTED) return "unknown";
      android.bluetooth.BluetoothManager manager =
          context.getSystemService(android.bluetooth.BluetoothManager.class);
      if (manager == null) return "unknown";
      for (android.bluetooth.BluetoothDevice d :
          manager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT))
        if (mac.equalsIgnoreCase(d.getAddress())) return "yes";
      return "no";
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  /**
   * The watch BLE control code for a command, or null if it has none (then the cloud is used). Only
   * the doors: the Korean Dolphin authenticated the key and then never answered START_CLIMATE (3)
   * — three times out of three, and the climate did not start (real log 11-08 19:11, 19:15, 22:02).
   * STOP_CLIMATE (8) was never seen working either, so 시동 켜기/끄기 stay on the cloud.
   */
  static Byte watchBleCode(CloudClient.Command command) {
    switch (command) {
      case UNLOCK:
        return com.dautolock.app.watchble.WatchBleCodec.UNLOCK;
      case LOCK:
        return com.dautolock.app.watchble.WatchBleCodec.LOCK;
      default:
        return null;
    }
  }

  volatile JSONArray vehicles = new JSONArray();
  volatile JSONObject capabilities = new JSONObject();
  volatile String vin = "", pinHash = "", vehicleName = "차량을 연결하세요";
  volatile String permissionSummary = "공유 권한은 차량 선택 후 확인합니다";
  volatile VehicleSnapshot snapshot;
  volatile boolean monitoring = false, autoEnabled = false;
  volatile String signal = "신호 대기", message = "BYD 계정과 차량 블루투스를 연결하세요";
  volatile String signalDetail = "거리 관찰을 시작하면 현재 신호를 표시합니다",
      autoDetail = "자동 제어 꺼짐",
      lastControl = "아직 제어 요청 없음";
  volatile String climateStatus = "공조 · 아직 실행 없음",
      windowsStatus = "창문 · 아직 실행 없음",
      stopStatus = "자동 종료 · 아직 실행 없음";
  volatile String readyStatus = "READY 진단 · 공조 시작 후 상태를 확인합니다";
  private volatile ReadinessWatch readinessWatch;
  volatile int signalStrength = 0;
  volatile double averageRssi = Double.NaN;
  // Auxiliary (beacon) signal — observed only for now; it does not drive any lock/unlock decision.
  volatile int beaconStrength = 0;
  volatile double beaconRssi = Double.NaN;
  volatile String beaconSignal = "보조 신호 대기", beaconDetail = "";
  volatile boolean initializing = true;
  private volatile long entryUntil, nextEntryCheck;
  private int entryTicket;
  private boolean entrySawClosed, entryOpened;
  private final LinkedList<ActivityFeed.Entry> events = new LinkedList<>();
  volatile boolean statusReading;
  /**
   * Recommended starting sensitivity (from real logs: standing by the car dips to about -85 dBm).
   * Only used when nothing was saved; existing users keep their own values.
   */
  // Near loosened −75→−78 (far −80→−83): the primary module sits up front, so trunk/passenger
  // approaches hovered just under −75 for ~12 s before crossing; a lower bar opens sooner there.
  static final int DEFAULT_NEAR = -78, DEFAULT_FAR = -83;
  static final int DEFAULT_NEAR_WAIT = 1, DEFAULT_FAR_WAIT = 5, DEFAULT_LOSS = 10;
  // Beacon near loosened −70→−78 (far −80→−85): shielded in the trunk it reads −74~−80 on a rear
  // approach, so −70 never crossed early; −78 lets the beacon OR-unlock help from trunk/passenger.
  static final int DEFAULT_BEACON_NEAR = -78, DEFAULT_BEACON_FAR = -85;
  // Trip ownership is in memory only: an app restart mid-trip means no automatic Stop (safe side).
  static final long TRIP_MAX_MS = 12L * 60 * 60 * 1000;
  private volatile long tripUnlockedAt = -1, vehicleActivityAt = -1;
  private volatile boolean tripDriven;
  private final AtomicInteger stopTicket = new AtomicInteger();
  volatile long stopDueAt = -1;
  /** Red "secure the car manually" alert text while the car was left unlocked or powered ON. */
  volatile String securityAlert;
  private volatile long securityAlertAt;
  private volatile String securityAlertLast = "";
  static final long SECURITY_RENOTIFY_MS = 5 * 60 * 1000;

  void raiseSecurityAlert(String kind, String text) {
    // Same alert already showing: do nothing but the periodic re-notify. Avoids log/UI spam when a
    // blocked auto-lock retries every ~18 s.
    boolean sameActive = text.equals(securityAlert);
    securityAlert = text;
    long now = System.currentTimeMillis();
    if (!sameActive) diagnostics.record("SECURITY_ALERT", "kind=" + kind);
    if (!text.equals(securityAlertLast) || now - securityAlertAt >= SECURITY_RENOTIFY_MS) {
      securityAlertAt = now;
      securityAlertLast = text;
      DoorNotifications.securityAlert(context, text);
    }
    if (!sameActive) changed();
  }

  void clearSecurityAlert(String reason) {
    if (securityAlert == null) return;
    securityAlert = null;
    securityAlertLast = "";
    diagnostics.record("SECURITY_ALERT_CLEAR", "reason=" + reason);
    DoorNotifications.cancelSecurityAlert(context);
    changed();
  }

  /**
   * Automatic lock refused after the user actually left: tell them to secure it manually. Only when
   * departure was confirmed — a bare signal loss next to a running car is most likely the phone
   * sitting inside, so alerting there (repeatedly) was a false alarm (real log: ~18 s spam while
   * parked ON with BLE lost). raiseSecurityAlert itself dedupes repeats of the same text.
   */
  private void lockFailedAlert(String reason, boolean departureConfirmed) {
    if (!departureConfirmed) return;
    VehicleSnapshot s = snapshot;
    if (s == null
        || !s.fresh(System.currentTimeMillis())
        || s.speed == null
        || s.speed != 0d) return; // Moving or unknown: the phone is most likely inside.
    boolean on = Integer.valueOf(3).equals(s.power);
    // A door is actually open after the user left: lock is held. Warn clearly so nothing/no one is
    // left in an open, unlocked car (child/luggage). Only on a confirmed departure.
    if (Boolean.FALSE.equals(s.doorsClosed)) {
      raiseSecurityAlert(
          "door_open",
          "차량 문이 열려 있어 자동으로 잠그지 못했습니다. 문을 닫고 직접 잠그세요."
              + (on ? " 시동도 켜져 있습니다." : ""));
      return;
    }
    if (Boolean.TRUE.equals(s.locked) && !on) return;
    raiseSecurityAlert(
        "lock_failed",
        (Boolean.TRUE.equals(s.locked) ? "도어는 잠겨 있지만 " : "도어가 자동으로 잠기지 않았습니다. ")
            + (on ? "시동이 켜져 있습니다. " : "")
            + "직접 잠그고 시동을 끄거나 아래 버튼을 누르세요. 사유: "
            + reason);
  }

  /** Consecutive automatic UNLOCK checks refused because the car was powered ON or moving. */
  volatile int unlockInUseBlocks;

  /**
   * Consecutive automatic LOCK checks refused because the car is ON and the departure was no longer
   * confirmed by the time BYD answered (real log 11-07: the phone hovered at the far threshold and
   * the car was queried every ~30 s for 6 min). ProximityService waits for a surer departure.
   */
  volatile int lockDepartureBlocks;

  /** The phone's audio/call Bluetooth is on the car's head unit: someone (this phone) is inside. */
  boolean inCarByAudio() {
    return carAudio.connected() && settings.getBoolean("carAudioHold", true);
  }

  /**
   * Re-check delay after an unretired automatic UNLOCK: 15 s, or backoff up to 5 min while the car
   * is in use. LOCK is never backed off — a departure lock must fire promptly (a lock backoff once
   * delayed a real departure lock by up to 2 min).
   */
  long automaticRecheckMs(boolean unlock) {
    int blocks = unlock ? unlockInUseBlocks : 0;
    if (blocks <= 0) return 15000;
    return Math.min(300000, 30000L << Math.min(4, blocks - 1));
  }
  private final List<Runnable> observers = new CopyOnWriteArrayList<>();

  Controller(Context context) {
    this(context, new SecureStore(context), CloudClient::new);
  }

  Controller(Context context, SecureStore store, Supplier<CloudClient> clients) {
    this.context = context;
    this.store = store;
    this.clients = clients;
    settings = context.getSharedPreferences("settings", 0);
    updater = new AutoUpdater(context, settings, this::changed);
    updater.notice = this::note;
    diagnostics = new DiagnosticLog(new java.io.File(context.getFilesDir(), "diagnostics"));
    vehicleLink = new VehicleLink(this);
    cloud = configure(clients.get());
    String version = "?";
    try {
      version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
    } catch (Exception ignored) {
    }
    diagnostics.record(
        "APP_START", "version=" + version + " sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);
    worker.execute(
        () -> {
          try {
            JSONObject saved = store.read();
            loginUser = saved.optString("loginUser");
            loginPassword = saved.optString("loginPassword");
            vin = saved.optString("vin");
            pinHash = saved.optString("pinHash");
            vehicleName = saved.optString("vehicleName", "차량을 연결하세요");
            JSONObject session = saved.optJSONObject("session");
            if (session != null && !session.optString("signToken").isEmpty())
              cloud.protocol.restoreSession(session);
            note(
                cloud.protocol.isLoggedIn()
                    ? (hasSavedLogin()
                        ? "저장된 계정과 차량을 복원했습니다"
                        : "계정 복원 완료 · 비밀번호 자동 저장은 한 번 로그인하면 적용됩니다")
                    : hasSavedLogin() ? "계정 저장됨 · 사용 시 자동 재연결합니다" : "BYD Sub 계정을 연결하세요");
          } catch (Exception e) {
            note("저장된 계정을 복원하지 못했습니다. 다시 로그인하세요");
          } finally {
            restoreTrip();
            vehicleLink.restore();
            if (!settings.contains("setupRequired")) {
              boolean existing =
                  !loginUser.isEmpty()
                      || !vin.isEmpty()
                      || cloud.protocol.isLoggedIn()
                      || !settings.getString("address", "").isEmpty();
              settings
                  .edit()
                  .putBoolean("setupRequired", !existing)
                  .putBoolean("setupComplete", existing)
                  .apply();
            }
            initializing = false;
            changed();
          }
        });
  }

  private CloudClient configure(CloudClient client) {
    client.setDiagnostics(diagnostics::record);
    client.climateTempOffset = settings.getFloat("climateTempOffset", 16f);
    client.setSessionRecovery(
        () -> {
          if (client != cloud || !hasSavedLogin())
            throw new Exception("Sub 계정 로그인 / 변경에서 계정을 한 번 저장하세요");
          note("저장된 Sub 계정으로 재연결 중…");
          client.login(loginUser, loginPassword);
          save();
          note("저장된 계정 재연결 완료 · 차량과 블루투스 선택 유지");
        });
    return client;
  }

  boolean hasSavedLogin() {
    return !loginUser.isEmpty() && !loginPassword.isEmpty();
  }

  boolean sameAccount(String user) {
    return !user.isEmpty()
        && (user.equals(loginUser) || (loginUser.isEmpty() && cloud.protocol.matchesLogin(user)));
  }

  boolean savedPasswordFor(String user) {
    return sameAccount(user) && !loginPassword.isEmpty();
  }

  boolean savedPinFor(String user) {
    return sameAccount(user) && !pinHash.isEmpty();
  }

  void observe(Runnable r) {
    observers.add(r);
  }

  void remove(Runnable r) {
    observers.remove(r);
  }

  void changed() {
    main.post(
        () -> {
          for (Runnable r : observers) r.run();
        });
  }

  synchronized void note(String value) {
    message = value;
    diagnostics.record("EVENT", value);
    events.addFirst(new ActivityFeed.Entry(System.currentTimeMillis(), value));
    while (events.size() > 30) events.removeLast();
    changed();
  }

  synchronized String log() {
    StringBuilder out = new StringBuilder();
    for (ActivityFeed.Entry e : events)
      out.append(LogDisplay.clock(java.time.Instant.ofEpochMilli(e.time)))
          .append("  ")
          .append(e.text)
          .append("\n\n");
    return out.toString().trim();
  }

  synchronized List<ActivityFeed.Entry> activity() {
    return new ArrayList<>(events);
  }

  boolean setupReady() {
    return !initializing
        && !vin.isEmpty()
        && !pinHash.isEmpty()
        && (cloud.protocol.isLoggedIn() || hasSavedLogin())
        && android.bluetooth.BluetoothAdapter.checkBluetoothAddress(
            settings.getString("address", ""));
  }

  void startMonitoring(boolean automatic, boolean reconfigure) {
    if (!setupReady()) {
      note("계정·차량·제어 PIN·BYD BLE 기기를 먼저 설정하세요");
      return;
    }
    try {
      startVehicleLink();
      context.startForegroundService(
          new Intent(context, ProximityService.class)
              .setAction(reconfigure ? "RECONFIGURE" : "START")
              .putExtra("automatic", automatic && !setupPending()));
    } catch (Exception e) {
      note("자동 시작 보류 · 앱에서 주변 기기 권한과 블루투스를 확인하세요");
      diagnostics.record("AUTO_START_ERROR", e.getClass().getSimpleName());
    }
  }

  boolean busy() {
    return busy.get();
  }

  private boolean setupPending() {
    return settings.getBoolean("setupRequired", false)
        && !settings.getBoolean("setupComplete", false);
  }

  private interface Work {
    void run() throws Exception;
  }

  private boolean run(Work work) {
    return run(work, () -> {}, false);
  }

  private boolean run(Work work, Runnable finished, boolean quietBusy) {
    if (!busy.compareAndSet(false, true)) {
      if (!quietBusy) note("이전 요청을 처리 중입니다");
      return false;
    }
    changed();
    worker.execute(
        () -> {
          try {
            work.run();
          } catch (Exception e) {
            note(e.getMessage() == null ? "요청을 처리하지 못했습니다" : e.getMessage());
          } finally {
            busy.set(false);
            finished.run();
            changed();
          }
        });
    return true;
  }

  private void save() throws Exception {
    store.save(accountData(cloud, loginUser, loginPassword, pinHash, vin, vehicleName));
  }

  private JSONObject accountData(
      CloudClient client, String user, String password, String pin, String vehicle, String name)
      throws Exception {
    return new JSONObject()
        .put("session", client.protocol.exportSession())
        .put("loginUser", user)
        .put("loginPassword", password)
        .put("vin", vehicle)
        .put("pinHash", pin)
        .put("vehicleName", name);
  }

  void login(String user, String password, String pin) {
    if (initializing || busy()) {
      note("계정 복원 또는 이전 요청 완료 후 다시 연결하세요");
      return;
    }
    stop();
    run(
        () -> {
          boolean same = sameAccount(user);
          String savedPassword = password.isEmpty() && same ? loginPassword : password;
          String savedPin = pin.isEmpty() && same ? pinHash : CryptoUtils.md5Hex(pin);
          if (user.isEmpty()
              || savedPassword.isEmpty()
              || (pin.isEmpty() ? !same || pinHash.isEmpty() : !pin.matches("[0-9]{6}")))
            throw new Exception("계정 정보와 6자리 제어 PIN을 입력하세요");
          CloudClient next = configure(clients.get());
          note("한국 BYD 계정 연결 중…");
          next.login(user, savedPassword);
          String nextVin = same ? vin : "";
          String nextName = same ? vehicleName : "차량을 선택하세요";
          // Commit the complete new account before replacing the last working account in memory.
          store.save(accountData(next, user, savedPassword, savedPin, nextVin, nextName));
          cloud = next;
          loginUser = user;
          loginPassword = savedPassword;
          vin = nextVin;
          vehicleName = nextName;
          pinHash = savedPin;
          if (!same)
            settings
                .edit()
                .remove("address")
                .remove("deviceName")
                .remove("beaconAddress")
                .remove("beaconName")
                .remove("beaconNear")
                .remove("beaconFar")
                .remove("climateTempOffset")
                .apply();
          if (!same) {
            vehicleLink.forget();
            forgetCharge();
            cloud.climateTempOffset = 16; // Relearned from the next car's climate range.
          }
          snapshot = null;
          capabilities = new JSONObject();
          vehicles = new JSONArray();
          permissionSummary = "공유 권한은 차량 선택 후 확인합니다";
          note("계정 정보를 암호화해 저장했습니다");
          vehicles = cloud.vehicles();
          if (!vin.isEmpty()) capabilities = cloud.capabilities(vin);
          note(
              vehicles.length() == 0
                  ? "공유된 차량이 없습니다. BYD AUTO에서 Sub 계정 공유를 승인하세요"
                  : !vin.isEmpty() ? "로그인 완료 · 기존 차량과 블루투스 선택 유지" : "로그인 완료 · 차량 선택 버튼을 누르세요");
        });
  }

  void refreshVehicles() {
    run(
        () -> {
          vehicles = cloud.vehicles();
          note("계정 차량 " + vehicles.length() + "대 확인 · 차량을 선택하세요");
        });
  }

  void selectVehicle(JSONObject vehicle) {
    stop();
    run(
        () -> {
          String selectedVin = vehicle.getString("vin");
          if (!selectedVin.equals(vin)) {
            vehicleLink.forget();
            settings
                .edit()
                .remove("address")
                .remove("deviceName")
                .remove("beaconAddress")
                .remove("beaconName")
                .remove("beaconNear")
                .remove("beaconFar")
                .remove("climateTempOffset")
                .apply();
            forgetCharge();
            cloud.climateTempOffset = 16; // Relearned from the next car's climate range.
          }
          vin = selectedVin;
          vehicleName =
              vehicle.optString("autoAlias", vehicle.optString("modelName", "BYD Dolphin"));
          if (vehicleName.isEmpty()) vehicleName = "BYD Dolphin";
          snapshot = null;
          capabilities = new JSONObject();
          save();
          capabilities = cloud.capabilities(vin);
          requestStatusRefresh("vehicle_selected");
          boolean shared = vehicle.optInt("empowerType", 0) < 0;
          ArrayList<String> scopes = new ArrayList<>();
          collectScopes(vehicle.optJSONArray("rangeDetailList"), scopes);
          permissionSummary =
              (shared ? "공유 차량 · " : "계정 차량 · ")
                  + (scopes.isEmpty()
                      ? "세부 공유 범위 미제공 · 서버에서 제어 권한 확인"
                      : String.join(" / ", scopes));
          note(
              (shared ? "Sub 계정 공유 차량" : "차량")
                  + " 연결 · 도어 잠금 "
                  + feature(CloudClient.Command.LOCK)
                  + " / 해제 "
                  + feature(CloudClient.Command.UNLOCK)
                  + " / Stop "
                  + feature(CloudClient.Command.STOP)
                  + ". 실제 공유 권한은 서버가 명령마다 확인합니다");
        });
  }

  String feature(CloudClient.Command c) {
    return CloudClient.hasFeature(capabilities, c.feature) ? "지원 표시" : "미확인";
  }

  private void collectScopes(Object o, List<String> names) {
    if (o instanceof JSONArray) {
      JSONArray a = (JSONArray) o;
      for (int i = 0; i < a.length(); i++) collectScopes(a.opt(i), names);
    } else if (o instanceof JSONObject) {
      JSONObject j = (JSONObject) o;
      String name = j.optString("name");
      if (!name.isEmpty() && !names.contains(name)) names.add(name);
      collectScopes(j.opt("children"), names);
      collectScopes(j.opt("childList"), names);
    }
  }

  void refresh() {
    run(
        () -> {
          requireVehicle();
          capabilities = cloud.capabilities(vin);
          observeSnapshot(cloud.snapshot(vin), true);
          note(
              snapshot.fresh(System.currentTimeMillis())
                  ? "차량 상태 업데이트 완료"
                  : "최신 상태를 확인하지 못했습니다 · 자동 제어 보류");
        });
  }

  /** Debounced display refresh. Uses the same cloud worker as controls, never a parallel login. */
  void requestStatusRefresh(String reason) {
    statusRefreshRequested = true;
    statusRequestedAt = System.currentTimeMillis();
    statusRevision.incrementAndGet();
    diagnostics.record("STATUS_REFRESH_REQUEST", reason);
    main.removeCallbacks(statusRefreshTask);
    main.postDelayed(statusRefreshTask, 750);
  }

  void refreshStatusWhenNeeded() {
    if (initializing || vin.isEmpty() || (!cloud.protocol.isLoggedIn() && !hasSavedLogin())) return;
    VehicleSnapshot current = snapshot;
    long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
    if (current != null
        && current.fresh(wall)
        && capabilities.length() > 0
        && (!statusRefreshRequested || current.receivedAt >= statusRequestedAt)) {
      statusRefreshRequested = false;
      return; // A command/readback/prefetch already provided newer information.
    }
    if (busy() || cloud.backoffMillis() > 0 || elapsed < nextStatusRead) {
      if (statusRefreshRequested) {
        main.removeCallbacks(statusRefreshTask);
        main.postDelayed(
            statusRefreshTask,
            Math.max(750, Math.max(cloud.backoffMillis(), nextStatusRead - elapsed)));
      }
      return;
    }
    readStatus("dashboard_refresh");
  }

  /** Manual refresh: always reads Cloud, but keeps the single queue, BYD backoff and read spacing. */
  void refreshNow() {
    if (initializing) return;
    if (vin.isEmpty() || (!cloud.protocol.isLoggedIn() && !hasSavedLogin())) {
      note("설정에서 BYD 계정과 차량을 먼저 연결하세요");
      return;
    }
    if (statusReading) return; // The running read already shows the spinner.
    if (busy()) {
      note("차량 요청 처리 중 · 완료 후 다시 새로고침하세요");
      return;
    }
    long wait = Math.max(cloud.backoffMillis(), nextStatusRead - SystemClock.elapsedRealtime());
    if (wait > 0) {
      note("BYD 요청 간격 보호 · " + ((wait + 999) / 1000) + "초 후 다시 새로고침하세요");
      return;
    }
    diagnostics.record("STATUS_REFRESH_REQUEST", "manual");
    readStatus("manual_refresh");
  }

  /** One read-only status read on the worker; false if another request held the worker. */
  private boolean readStatus(String source) {
    boolean manual = source.equals("manual_refresh");
    int ticket = generation.get(), refreshTicket = statusRevision.get();
    String target = vin;
    statusReading = true;
    boolean started =
        run(
        () -> {
          nextStatusRead = SystemClock.elapsedRealtime() + 5000;
          try {
            if (!validSession(ticket, target)) return;
            requireVehicle();
            if (capabilities.length() == 0) capabilities = cloud.capabilities(target);
            VehicleSnapshot next = cloud.snapshot(target);
            if (!validSession(ticket, target)) return;
            observeSnapshot(next, true);
            // If approach begins during this query, do not force a second identical preflight.
            boolean reusable =
                autoEnabled
                    && monitoring
                    && unlockPreflight.offer(
                        unlockPreflight.revision(),
                        ticket,
                        target,
                        next,
                        SystemClock.elapsedRealtime(),
                        System.currentTimeMillis(),
                        source);
            if (refreshTicket == statusRevision.get()) statusRefreshRequested = false;
            diagnostics.record(
                "STATUS_REFRESH_RESULT",
                "source="
                    + source
                    + " reusable="
                    + reusable
                    + " "
                    + next.diagnostic(System.currentTimeMillis()));
            if (manual)
              note(
                  next.fresh(System.currentTimeMillis())
                      ? "차량 상태 새로고침 완료"
                      : "최신 차량 상태를 받지 못했습니다 · 잠시 후 다시 시도하세요");
          } catch (Exception e) {
            nextStatusRead = SystemClock.elapsedRealtime() + 30000;
            if (refreshTicket == statusRevision.get()) statusRefreshRequested = false;
            // Say it was only a read: on the main screen a bare "HTTP 502" right after a widget or
            // notification tap looked like the door command had failed (real log 11-11 12:27).
            throw new Exception(
                "차량 상태 새로고침 실패 · "
                    + (e.getMessage() == null ? "요청을 처리하지 못했습니다" : e.getMessage()),
                e);
          }
        },
        () -> {
          statusReading = false;
          if (statusRefreshRequested) main.postDelayed(statusRefreshTask, 1000);
        },
        true);
    if (!started) {
      statusReading = false;
      if (manual) note("차량 요청 처리 중 · 완료 후 다시 새로고침하세요");
    }
    return started;
  }

  void logout() {
    stop();
    run(
        () -> {
          store.clear();
          vehicleLink.forget();
          cloud = configure(clients.get());
          loginUser = "";
          loginPassword = "";
          vin = "";
          pinHash = "";
          vehicleName = "차량을 연결하세요";
          snapshot = null;
          vehicles = new JSONArray();
          capabilities = new JSONObject();
          permissionSummary = "공유 권한은 차량 선택 후 확인합니다";
          settings
              .edit()
              .remove("address")
              .remove("deviceName")
              .remove("beaconAddress")
              .remove("beaconName")
              .remove("beaconNear")
              .remove("beaconFar")
              .remove("climateTempOffset")
              .apply();
          forgetCharge();
          note("저장된 ID·비밀번호·PIN과 차량 연결 정보를 삭제했습니다");
        });
  }

  private void requireVehicle() throws Exception {
    if (vin.isEmpty()) throw new Exception("계정 로그인 후 차량을 선택하세요");
    cloud.ensureAuthenticated();
  }

  static final long RECENT_POWER_MS = 5 * 60 * 1000;
  // Turning the ignition off briefly cycles the car's Bluetooth, so both the BLE advert and the
  // Bridge drop for ~15 s right after a power-off. Long enough to cover that transient (observed
  // ~60 s after the key-off) without holding a real departure's lock for long.
  static final long IGNITION_OFF_GRACE_MS = 90 * 1000;
  private volatile long powerOnSeenAt, powerOffAt;
  // A 시동 끄기 sent as CLOSEAIR (no live Bridge P): when it was acknowledged, for which car.
  private volatile long remoteShutdownAt = -1, remoteShutdownSince;
  private volatile String remoteShutdownVin = "";
  /** Power read-backs after a remote 시동 끄기; BYD powered a remote start down ~40 s after. */
  static final long[] REMOTE_SHUTDOWN_CHECKS_MS = {15000, 35000, 60000};
  static final long REMOTE_SHUTDOWN_GIVE_UP_MS = 55000;

  /** Power ON was observed in the last 5 minutes: an opening door now is someone leaving. */
  boolean recentlyPowered() {
    long at = powerOnSeenAt;
    return at > 0 && System.currentTimeMillis() - at < RECENT_POWER_MS;
  }

  /**
   * The ignition turned off within the grace window. A signal loss right after this is the car's
   * Bluetooth cycling, not the phone leaving, so a lost-signal lock is held off until it passes.
   */
  boolean ignitionOffRecently() {
    long at = powerOffAt;
    return at > 0 && System.currentTimeMillis() - at < IGNITION_OFF_GRACE_MS;
  }

  /** Where the last power-off came from, for the skip log: "status" (a read) or "car_audio". */
  String ignitionOffSource() {
    return powerOffSource;
  }

  private volatile String powerOffSource = "status";

  /**
   * The car's audio/call Bluetooth just dropped: the car was switched off (or the phone left it).
   * Starts the same grace as a power-off seen in a read. Needed because a drive may have no read at
   * all (real log 11-10 21:29–21:34: no read during the drive, so the OFF went unseen and a
   * lost-signal lock started 5 s after switching off — held only by an open door; sitting on with the
   * doors shut would have been locked in). Free: no network, the broadcast is already received.
   */
  void carAudioDropped() {
    powerOffAt = System.currentTimeMillis();
    powerOffSource = "car_audio";
    diagnostics.record("IGNITION_OFF", "source=car_audio graceMs=" + IGNITION_OFF_GRACE_MS);
  }

  private void observeSnapshot(VehicleSnapshot next, boolean announce) {
    unlockPreflight.observed();
    VehicleSnapshot previous = snapshot;
    snapshot = next;
    if (Integer.valueOf(3).equals(next.power) && next.fresh(System.currentTimeMillis()))
      powerOnSeenAt = System.currentTimeMillis();
    // Mark the ON->OFF transition so a signal loss in the next grace window (the Bluetooth cycling
    // as the car powers down) is not mistaken for a departure.
    if (previous != null
        && Integer.valueOf(3).equals(previous.power)
        && Integer.valueOf(1).equals(next.power)
        && next.fresh(System.currentTimeMillis())) {
      powerOffAt = System.currentTimeMillis();
      powerOffSource = "status";
    }
    if (securityAlert != null
        && next.fresh(System.currentTimeMillis())
        && Boolean.TRUE.equals(next.locked)
        && Integer.valueOf(1).equals(next.power)) clearSecurityAlert("locked_and_off");
    if (announce
        && previous != null
        && next.fresh(System.currentTimeMillis())
        && next.measuredAt > previous.measuredAt
        && previous.locked != null
        && next.locked != null
        && !previous.locked.equals(next.locked)) {
      diagnostics.record("DOOR_STATE_CHANGE", "locked=" + next.locked + " source=cloud");
      // A parked, powered-OFF car unlocked by someone else (key, another phone/app) is not this
      // phone's trip any more. Unlocking from inside a powered car is just getting out (real log:
      // that wrongly ended the trip right before the Stop).
      if (!next.locked && Integer.valueOf(1).equals(next.power)) endTrip("unlocked_elsewhere");
      DoorNotifications.result(
          context, next.locked ? "도어 잠김 확인" : "도어 열림 확인", "BYD Cloud 조회에서 상태 변경을 확인했습니다");
    }
    chargeWatch(next);
    remoteShutdownWatch(next);
    parkedOnWatch(next);
  }

  // "Left ON in P" notice. The 5 minutes are counted from the Bridge's live gear; one status read at
  // the 5-minute mark confirms the power is still ON (a live Bridge alone does not prove it, and the
  // phone may have walked away from a car left running). No polling.
  static final long PARKED_ON_RETRY_MS = 30000;
  static final int PARKED_ON_MAX_TRIES = 6, PARKED_ON_MAX_READS = 3;
  private volatile long parkedOnSinceWall, parkedOnAskedAt = -1;
  private volatile int parkedOnAskedRun = -1;

  boolean parkedOnNotify() {
    return settings.getBoolean("parkedOnNotify", true);
  }

  /** Called with each new P run from the Bridge thread: check once when it has lasted 5 minutes. */
  private void parkedOnStarted() {
    int run = parkedOn.run();
    parkedOnSinceWall = System.currentTimeMillis();
    diagnostics.record("PARKED_ON_START", "run=" + run);
    main.postDelayed(() -> parkedOnCheck(run, 0, 0), ParkedOnWatch.HOLD_MS);
  }

  /** A read since P began that shows the car OFF or moving ends the run without another read. */
  private boolean parkedOnOver(VehicleSnapshot s) {
    return s != null
        && s.receivedAt >= parkedOnSinceWall
        && s.measuredAt >= parkedOnSinceWall
        && (Integer.valueOf(1).equals(s.power) || (s.speed != null && s.speed > 0));
  }

  private void parkedOnCheck(int run, int tries, int reads) {
    if (!parkedOn.due(run, SystemClock.elapsedRealtime())) return; // Ended, newer run, or reported.
    if (!parkedOnNotify() || vin.isEmpty()) return;
    VehicleSnapshot seen = snapshot;
    if (parkedOnOver(seen)) {
      parkedOn.end();
      diagnostics.record(
          "PARKED_ON_SKIP", "reason=off_seen power=" + seen.power + " speed=" + seen.speed);
      return;
    }
    // A read asked earlier came back without a usable answer, or none could be asked: try again a
    // few times, then give up quietly rather than keep reading.
    if (tries >= PARKED_ON_MAX_TRIES || reads >= PARKED_ON_MAX_READS) {
      parkedOnAskedAt = -1;
      diagnostics.record("PARKED_ON_SKIP", "reason=no_answer tries=" + tries + " reads=" + reads);
      return;
    }
    boolean free =
        !initializing
            && !busy()
            && !statusReading
            && cloud.backoffMillis() <= 0
            && (cloud.protocol.isLoggedIn() || hasSavedLogin());
    int nextReads = reads;
    if (free) {
      parkedOnAskedRun = run;
      parkedOnAskedAt = System.currentTimeMillis();
      com.dautolock.link.LinkProtocol.Sample live =
          vehicleLink.state.fresh(SystemClock.elapsedRealtime());
      diagnostics.record(
          "PARKED_ON_CHECK",
          "run=" + run + " bridge=" + (live == null ? "lost" : "live_" + live.gearLabel()));
      if (readStatus("parked_on_check")) nextReads++;
      else parkedOnAskedAt = -1;
    }
    int nextTries = tries + 1, readsSoFar = nextReads;
    main.postDelayed(() -> parkedOnCheck(run, nextTries, readsSoFar), PARKED_ON_RETRY_MS);
  }

  private void parkedOnWatch(VehicleSnapshot next) {
    long now = System.currentTimeMillis();
    if (!next.fresh(now) || !parkedOn.active()) return;
    if (parkedOnOver(next)) {
      boolean reported = parkedOn.end();
      if (parkedOnAskedAt > 0 || reported)
        diagnostics.record(
            "PARKED_ON_END", "power=" + next.power + " speed=" + next.speed + " reported=" + reported);
      parkedOnAskedAt = -1;
      if (reported) DoorNotifications.cancelParkedOn(context);
      return;
    }
    long asked = parkedOnAskedAt;
    int run = parkedOnAskedRun;
    if (asked < 0 || next.receivedAt < asked || run != parkedOn.run()) return;
    parkedOnAskedAt = -1;
    boolean on = Integer.valueOf(3).equals(next.power);
    diagnostics.record(
        "PARKED_ON_RESULT", "power=" + next.power + " locked=" + next.locked + " notify=" + on);
    if (!on || !parkedOnNotify() || !parkedOn.markNotified(run)) return;
    DoorNotifications.parkedOn(
        context,
        "주차(P)에서 시동이 켜진 지 5분이 지났습니다"
            + (next.locked == null ? "" : next.locked ? " · 도어 잠김" : " · 도어 열림")
            + (next.battery == null ? "" : " · 배터리 " + Math.round(next.battery) + "%"));
  }

  /** Reports how a remote 시동 끄기 (CLOSEAIR) ended, from the status reads it scheduled. */
  private void remoteShutdownWatch(VehicleSnapshot next) {
    long at = remoteShutdownAt, now = System.currentTimeMillis();
    long elapsed = SystemClock.elapsedRealtime() - remoteShutdownSince;
    if (at < 0) return;
    if (!remoteShutdownVin.equals(vin)) {
      remoteShutdownAt = -1;
      return;
    }
    if (!next.fresh(now) || next.measuredAt < at) return;
    boolean off = Integer.valueOf(1).equals(next.power);
    if (!off && elapsed < REMOTE_SHUTDOWN_GIVE_UP_MS) return;
    remoteShutdownAt = -1;
    diagnostics.record("REMOTE_SHUTDOWN_RESULT", "power=" + next.power + " elapsedMs=" + elapsed);
    stopStatus =
        lastControl =
            off
                ? "원격 시동 꺼짐 · 차량 전원 OFF 확인"
                : "원격 공조는 껐지만 차량 전원이 아직 켜져 있습니다 · 누가 차를 쓰는 중이면 꺼지지 않습니다";
    note(lastControl);
    DoorNotifications.result(
        context,
        off ? "시동 꺼짐 확인" : "시동 꺼짐 미확인",
        off
            ? "원격 시동 끄기 · BYD 조회상 차량 전원 OFF"
            : "원격 공조 끄기만 적용됐습니다. 차 옆에서 차량 보조 앱이 연결되면 시동 끄기를 쓸 수 있어요");
  }

  // Charge-complete notice. No polling: status reads that happen anyway track the session, and one
  // alarm is set for the car's own finish estimate (re-armed by every newer estimate).
  static final String CHARGE_WATCH = "com.dautolock.app.CHARGE_WATCH";
  static final long CHARGE_GRACE_MS = 3 * 60000, CHARGE_MIN_DELAY_MS = 10 * 60000;
  static final long CHARGE_UNKNOWN_MS = 60 * 60000, CHARGE_RETRY_MS = 30 * 60000;
  static final int CHARGE_MAX_CHECKS = 12;

  boolean chargeNotify() {
    return settings.getBoolean("chargeNotify", true);
  }

  /**
   * Charging stopped while the car stays locked and OFF means it finished (or the charger stopped)
   * with nobody at the car — the cable stays latched while locked — so that is the one case worth a
   * notice. Stopping with the car unlocked or ON is the driver unplugging: ended quietly.
   */
  private void chargeWatch(VehicleSnapshot next) {
    long now = System.currentTimeMillis();
    if (!next.fresh(now) || next.chargeState == null) return;
    boolean seen = settings.getBoolean("chargeSeen", false);
    if (next.charging()) {
      if (!seen) {
        settings.edit().putBoolean("chargeSeen", true).putInt("chargeChecks", 0).apply();
        diagnostics.record(
            "CHARGE_START", "soc=" + next.battery + " chargeMin=" + next.chargeMinutes);
      }
      if (chargeNotify()) scheduleChargeCheck(chargeCheckAt(next, now));
      return;
    }
    if (!seen) return;
    forgetCharge();
    boolean unattended =
        Boolean.TRUE.equals(next.locked) && Integer.valueOf(1).equals(next.power);
    boolean notify = unattended && chargeNotify();
    diagnostics.record(
        "CHARGE_END",
        "notify="
            + notify
            + " locked="
            + next.locked
            + " power="
            + next.power
            + " soc="
            + next.battery);
    if (notify)
      DoorNotifications.chargeDone(
          context,
          (next.battery == null ? "배터리 잔량 미확인" : "배터리 " + Math.round(next.battery) + "%")
              + (next.range == null ? "" : " · 주행 가능 " + Math.round(next.range) + " km"));
  }

  /** The car's finish estimate plus a little grace, never sooner than 10 minutes from now. */
  static long chargeCheckAt(VehicleSnapshot s, long now) {
    long base = s.measuredAt > 0 ? s.measuredAt : s.receivedAt;
    long expected =
        s.chargeUnderOneMinute
            ? base + 60000
            : s.chargeMinutes == null ? now + CHARGE_UNKNOWN_MS : base + s.chargeMinutes * 60000L;
    return Math.max(expected + CHARGE_GRACE_MS, now + CHARGE_MIN_DELAY_MS);
  }

  private android.app.PendingIntent chargeAlarm() {
    return android.app.PendingIntent.getBroadcast(
        context,
        11,
        new Intent(context, DoorActionReceiver.class).setAction(CHARGE_WATCH),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
  }

  /** Inexact, Doze-tolerant one-shot alarm; re-arming replaces the previous one. */
  private void scheduleChargeCheck(long at) {
    android.app.AlarmManager alarms = context.getSystemService(android.app.AlarmManager.class);
    if (alarms == null) return;
    alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, chargeAlarm());
    long previous = settings.getLong("chargeWatchAt", -1);
    settings.edit().putLong("chargeWatchAt", at).apply();
    if (Math.abs(previous - at) >= 60000)
      diagnostics.record(
          "CHARGE_CHECK_SCHEDULED",
          "inMin=" + Math.max(0, (at - System.currentTimeMillis()) / 60000));
  }

  /** Charge session over, notice turned off, or account/vehicle changed: no alarm left behind. */
  void forgetCharge() {
    settings.edit().remove("chargeSeen").remove("chargeChecks").remove("chargeWatchAt").apply();
    android.app.AlarmManager alarms = context.getSystemService(android.app.AlarmManager.class);
    if (alarms != null) alarms.cancel(chargeAlarm());
  }

  /** The alarm fired: one read-only status read; its result re-arms, ends or notifies. */
  void chargeWatchDue() {
    settings.edit().remove("chargeWatchAt").apply();
    if (!settings.getBoolean("chargeSeen", false) || !chargeNotify()) return;
    long now = System.currentTimeMillis();
    if (initializing || busy() || statusReading || cloud.backoffMillis() > 0) {
      scheduleChargeCheck(now + CHARGE_GRACE_MS); // Not counted: nothing was read.
      return;
    }
    if (vin.isEmpty() || (!cloud.protocol.isLoggedIn() && !hasSavedLogin())) return;
    int checks = settings.getInt("chargeChecks", 0);
    if (checks >= CHARGE_MAX_CHECKS) {
      diagnostics.record("CHARGE_CHECK_LIMIT", "checks=" + checks);
      return;
    }
    settings.edit().putInt("chargeChecks", checks + 1).apply();
    diagnostics.record("CHARGE_CHECK", "n=" + (checks + 1));
    // Fallback if this read fails; a successful read replaces or cancels it.
    scheduleChargeCheck(now + CHARGE_RETRY_MS);
    readStatus("charge_watch");
  }

  void command(CloudClient.Command command, boolean automatic, BooleanSupplier proximityValid) {
    executeCommand(
        command, automatic, proximityValid, () -> true, () -> {}, () -> {}, () -> false, -1);
  }

  /** Called only by this operation's P/parking-brake confirmation button; never persisted. */
  void manualStopAfterParkingConfirmation() {
    diagnostics.record("MANUAL_PARKING_CONFIRM", "oneUse=true expiresSeconds=30");
    executeCommand(
        CloudClient.Command.STOP,
        false,
        () -> true,
        () -> true,
        () -> {},
        () -> {},
        () -> false,
        SystemClock.elapsedRealtime());
  }

  boolean automaticCommand(
      CloudClient.Command command,
      BooleanSupplier valid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished) {
    return automaticCommand(command, valid, claim, alreadyDone, finished, true);
  }

  boolean automaticCommand(
      CloudClient.Command command,
      BooleanSupplier valid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished,
      boolean departureConfirmed) {
    return automaticCommand(command, valid, claim, alreadyDone, finished, () -> departureConfirmed);
  }

  boolean automaticCommand(
      CloudClient.Command command,
      BooleanSupplier valid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished,
      BooleanSupplier departureConfirmed) {
    return automaticCommand(
        command,
        valid,
        claim,
        alreadyDone,
        finished,
        departureConfirmed,
        SystemClock.elapsedRealtime());
  }

  boolean automaticCommand(
      CloudClient.Command command,
      BooleanSupplier valid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished,
      BooleanSupplier departureConfirmed,
      long readyAt) {
    return executeCommand(
        command, true, valid, claim, alreadyDone, finished, departureConfirmed, -1, readyAt, false);
  }

  void discardApproachPreflight() {
    unlockPreflight.invalidate();
  }

  /** Read-only preparation shares the serial cloud executor and its authentication/backoff. */
  boolean prefetchUnlock(BooleanSupplier approaching, Runnable finished) {
    long now = SystemClock.elapsedRealtime();
    int ticket = generation.get();
    String target = vin;
    BooleanSupplier valid =
        () ->
            validSession(ticket, target) && monitoring && autoEnabled && approaching.getAsBoolean();
    if (initializing
        || target.isEmpty()
        || !valid.getAsBoolean()
        || cloud.backoffMillis() > 0
        || now < nextApproachRead
        || unlockPreflight.young(ticket, target, now, System.currentTimeMillis())) return false;
    long revision = unlockPreflight.revision();
    return run(
        () -> {
          if (!valid.getAsBoolean()) return;
          nextApproachRead = SystemClock.elapsedRealtime() + UnlockPreflightCache.REFRESH_AFTER_MS;
          long started = SystemClock.elapsedRealtime();
          diagnostics.record("APPROACH_PREFETCH_START", "readOnly=true minIntervalMs=" + UnlockPreflightCache.REFRESH_AFTER_MS);
          try {
            requireVehicle();
            if (!valid.getAsBoolean()) return;
            VehicleSnapshot state = cloud.snapshot(target);
            if (!valid.getAsBoolean()) {
              diagnostics.record(
                  "APPROACH_PREFETCH_DISCARD", "reason=signal_or_configuration_changed");
              return;
            }
            observeSnapshot(state, false);
            long received = SystemClock.elapsedRealtime();
            boolean stored =
                unlockPreflight.offer(
                    revision, ticket, target, state, received, System.currentTimeMillis());
            diagnostics.record(
                "APPROACH_PREFETCH_RESULT",
                "durationMs="
                    + (received - started)
                    + " reusable="
                    + stored
                    + " "
                    + state.diagnostic(System.currentTimeMillis()));
          } catch (Exception e) {
            diagnostics.record(
                "APPROACH_PREFETCH_ERROR",
                "durationMs=" + (SystemClock.elapsedRealtime() - started) + " " + e.getMessage());
          }
        },
        finished,
        true);
  }

  private boolean parkingConfirmationCurrent(long confirmedAt) {
    long elapsed = SystemClock.elapsedRealtime() - confirmedAt;
    return confirmedAt >= 0 && elapsed >= 0 && elapsed <= 30000;
  }

  private String controlBlock(
      VehicleSnapshot state,
      CloudClient.Command command,
      boolean automatic,
      boolean departureConfirmed,
      boolean parkingConfirmed) {
    long now = System.currentTimeMillis();
    boolean lock = command == CloudClient.Command.LOCK;
    String block =
        command == CloudClient.Command.STOP
            ? state.manualStopBlock(now, !automatic && parkingConfirmed)
            : automatic && !(lock && Boolean.TRUE.equals(state.locked))
                ? state.automaticBlock(lock, now, departureConfirmed)
                : state.manualBlock(false, now);
    if (block != null) return block;
    if (command == CloudClient.Command.STOP
        && !Integer.valueOf(1).equals(state.power)
        && vehicleLink.required()) {
      String liveBlock = vehicleLink.block();
      if (liveBlock != null) return liveBlock;
    }
    return lock && !Boolean.TRUE.equals(state.doorsClosed) ? "모든 도어가 닫혔는지 확인하지 못했습니다" : null;
  }

  private boolean executeCommand(
      CloudClient.Command command,
      boolean automatic,
      BooleanSupplier proximityValid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished,
      BooleanSupplier departureConfirmed,
      long parkingConfirmedAt) {
    return executeCommand(
        command,
        automatic,
        proximityValid,
        claim,
        alreadyDone,
        finished,
        departureConfirmed,
        parkingConfirmedAt,
        SystemClock.elapsedRealtime(),
        false);
  }

  private boolean executeCommand(
      CloudClient.Command command,
      boolean automatic,
      BooleanSupplier proximityValid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished,
      BooleanSupplier departureConfirmed,
      long parkingConfirmedAt,
      long readyAt,
      boolean preserveTrip) {
    int ticket = generation.get();
    String target = vin;
    // Any new door/Stop request from this phone supersedes a pending automatic Stop.
    if (!(automatic && command == CloudClient.Command.LOCK)) cancelPendingStop("새 차량 명령 요청");
    BooleanSupplier valid =
        () ->
            ticket == generation.get()
                && target.equals(vin)
                && (parkingConfirmedAt < 0 || parkingConfirmationCurrent(parkingConfirmedAt))
                && (!automatic || (monitoring && autoEnabled && proximityValid.getAsBoolean()));
    return run(
        () -> {
          AtomicBoolean dispatched = new AtomicBoolean();
          try {
            if (!automatic || command != CloudClient.Command.UNLOCK) discardApproachPreflight();
            requireVehicle();
            if (pinHash.isEmpty()) throw new Exception("제어 PIN이 없습니다. 다시 로그인하세요");
            if (!valid.getAsBoolean()) return;
            if (!CloudClient.hasFeature(capabilities, command.feature) && automatic)
              capabilities = cloud.capabilities(target);
            if (!CloudClient.hasFeature(capabilities, command.feature))
              throw new Exception("이 차량의 " + command.label + " 지원을 확인하지 못했습니다. 상태를 새로고침하세요");
            long preflightAt = SystemClock.elapsedRealtime();
            UnlockPreflightCache.Entry prepared =
                automatic && command == CloudClient.Command.UNLOCK
                    ? unlockPreflight.take(ticket, target, preflightAt, System.currentTimeMillis())
                    : null;
            boolean reused = prepared != null;
            note(command.label + (reused ? " · 접근 중 조회한 최신 상태 사용" : " 전 차량 상태 확인 중…"));
            lastControl = (automatic ? "자동 " : "수동 ") + command.label + " · 상태 확인 중";
            observeSnapshot(reused ? prepared.state : cloud.snapshot(target), true);
            diagnostics.record(
                "PREFLIGHT_TIMING",
                command
                    + " source="
                    + (reused ? prepared.source : "fresh_query")
                    + " durationMs="
                    + (SystemClock.elapsedRealtime() - preflightAt)
                    + " readyToPreflightMs="
                    + (SystemClock.elapsedRealtime() - readyAt));
            diagnostics.record(
                "PREFLIGHT",
                (automatic ? "AUTO " : "MANUAL ")
                    + command
                    + " "
                    + snapshot.diagnostic(System.currentTimeMillis()));
            boolean lock = command == CloudClient.Command.LOCK,
                stop = command == CloudClient.Command.STOP;
            VehicleSnapshot checked = snapshot;
            String block =
                controlBlock(
                    checked,
                    command,
                    automatic,
                    departureConfirmed.getAsBoolean(),
                    parkingConfirmationCurrent(parkingConfirmedAt));
            if (automatic && lock)
              lockDepartureBlocks =
                  VehicleSnapshot.LOCK_NEEDS_DEPARTURE.equals(block) ? lockDepartureBlocks + 1 : 0;
            // Automatic unlock refused while the car is in use but with nothing left to do — the
            // doors are already open, or it is driving with the phone aboard: this approach is
            // over, so retire it instead of re-checking (real log: a beacon unlock after the vehicle
            // BLE's re-queried the cloud every ≤5 min through a whole 40 min drive). Nothing is
            // sent; a locked, stationary, powered car still backs off and re-checks as before.
            if (block != null
                && automatic
                && command == CloudClient.Command.UNLOCK
                && checked.fresh(System.currentTimeMillis())
                && (Boolean.FALSE.equals(checked.locked)
                    || (checked.speed != null && checked.speed > 0))) {
              boolean moving = checked.speed != null && checked.speed > 0;
              unlockInUseBlocks = 0;
              alreadyDone.run(); // The engine decides whether the zone still counts (see below).
              diagnostics.record(
                  "AUTO_UNLOCK_RETIRED", "reason=" + (moving ? "moving" : "already_unlocked"));
              lastControl =
                  moving
                      ? "주행 중 · 자동 잠금 해제 생략"
                      : "BYD 조회상 이미 잠금 해제 상태 · 명령 생략";
              note(lastControl);
              return;
            }
            // Automatic unlock blocked because the car is powered or moving (phone most likely
            // inside): the service backs off so it does not retry every ~18 s. LOCK is not backed
            // off — a real departure must lock promptly.
            if (automatic && command == CloudClient.Command.UNLOCK)
              unlockInUseBlocks =
                  block != null
                          && (Integer.valueOf(3).equals(checked.power)
                              || (checked.speed != null && checked.speed > 0))
                      ? unlockInUseBlocks + 1
                      : 0;
            // Manual 시동 끄기 away from the car: without a live Bridge P, TURNOFFENGINE stays
            // refused. A remote-started car is shut down by ending its remote climate instead —
            // BYD powers it off once that climate stops, while CLOSEAIR in a car someone took over
            // only turns the A/C off. Everything but the Bridge must still pass.
            if (block != null
                && stop
                && !automatic
                && Integer.valueOf(3).equals(checked.power)
                && checked.manualStopBlock(
                        System.currentTimeMillis(), parkingConfirmationCurrent(parkingConfirmedAt))
                    == null
                && CloudClient.hasClimate(capabilities)) {
              remoteShutdown(ticket, target, valid, block);
              return;
            }
            if (block != null) throw new Exception("제어 보류: " + block);
            if ((stop && Integer.valueOf(1).equals(snapshot.power))
                || (!stop && Boolean.valueOf(lock).equals(snapshot.locked))) {
              // Not gated on valid(): the signal often wobbles during the ~2 s read, and an
              // unmarked engine asked again and reopened the car after its own relock (real log
              // 11-09 10:40). The engine itself refuses if the phone has turned around.
              if (automatic) alreadyDone.run();
              // Arriving together: another phone opened first while this phone also qualified
              // (automatic unlock is only evaluated with power OFF). Still needs this phone's
              // Bridge to see the drive, live P and the delayed re-check before any Stop.
              // Already unlocked with power OFF right after the car was ON = the occupant just
              // switched off and is getting out, not arriving (real log: the exit door opening
              // was taken as boarding, climate start powered the car back on).
              if (command == CloudClient.Command.UNLOCK) {
                if (recentlyPowered()) {
                  diagnostics.record("ENTRY_SKIP", "reason=recent_power_on alreadyUnlocked=true");
                } else if (!preserveTrip
                    && Integer.valueOf(1).equals(snapshot.power)
                    && validSession(ticket, target))
                  // (Kept under 32 characters with "reason=": the log masks longer runs as secrets.)
                  startTrip(automatic ? "approach_already_open" : "manual_already_open");
                // The boarding watch follows only this phone's own unlock command.
                if (automatic && settings.getBoolean("autoReady", false))
                  climateStatus = "공조 연동 · 이미 열린 차량이라 탑승 감시 안 함";
              }
              // Start authorization: a manual 도어 열기 on an open, switched-off car still goes out
              // over the BLE key (see startAuthorization). Not for the park unlock or automation.
              if (command == CloudClient.Command.UNLOCK
                  && !automatic
                  && !preserveTrip
                  && !Integer.valueOf(3).equals(snapshot.power)
                  && startAuthorization(false)) return;
              lastControl = "BYD 조회상 이미 " + command.label + " 상태 · 명령 생략";
              if (stop) stopStatus = "차량 종료 · BYD 조회상 이미 전원 OFF";
              diagnostics.record(
                  "CONTROL_SKIP_STATE",
                  (automatic ? "AUTO " : "MANUAL ") + command + " source=cloud");
              note(lastControl);
              if (!automatic) DoorNotifications.result(context, lastControl, "BYD에서 조회한 현재 상태입니다");
              if (lock)
                afterLock(
                    () ->
                        validSession(ticket, target) && (!automatic || (monitoring && autoEnabled)),
                    automatic,
                    departureConfirmed,
                    true);
              return;
            }
            if (!valid.getAsBoolean()) {
              note("거리 또는 설정이 바뀌어 제어를 취소했습니다");
              return;
            }
            long sentAt = System.currentTimeMillis();
            if (stop) cancelReadinessWatch("차량 종료 요청");
            boolean wasClosed = Boolean.TRUE.equals(snapshot.doorsClosed);
            // Consume the engine proposal at most once across a BLE-first then cloud-fallback send:
            // claim() latches the engine as handled, so the second channel must reuse the result.
            final AtomicInteger claimState = new AtomicInteger(0); // 0 unknown, 1 claimed, 2 refused
            BooleanSupplier claimOnce =
                () -> {
                  int s = claimState.get();
                  if (s != 0) return s == 1;
                  boolean ok = claim.getAsBoolean();
                  claimState.set(ok ? 1 : 2);
                  return ok;
                };
            // Final safety recheck, re-evaluated fresh for whichever channel sends (no claim here).
            BooleanSupplier dispatchReady =
                () -> {
                  if (!valid.getAsBoolean()) return false;
                  if (prepared != null
                      && !prepared.usable(
                          SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                    diagnostics.record("CONTROL_RECHECK_BLOCK", "접근 사전 조회 만료 · 새 조회 필요");
                    return false;
                  }
                  String dispatchBlock =
                      controlBlock(
                          checked,
                          command,
                          automatic,
                          departureConfirmed.getAsBoolean(),
                          parkingConfirmationCurrent(parkingConfirmedAt));
                  if (dispatchBlock != null) {
                    diagnostics.record("CONTROL_RECHECK_BLOCK", dispatchBlock);
                    return false;
                  }
                  return true;
                };
            // Fast path: send over the issued watch BLE key (local, ≈1.6 s). Keeps every gate above —
            // the same safety recheck and engine claim — and falls back to the cloud if the key is out
            // of range or the handshake fails. STOP has no BLE code, so it always uses the cloud.
            boolean sentByBle = false;
            VehicleSnapshot bleProbe = null;
            Byte bleCode = watchBleCode(command);
            long keySilent = keySilentMs(SystemClock.elapsedRealtime());
            if (bleCode != null && keySilent >= KEY_SILENT_SKIP_MS && ble.ready()) {
              diagnostics.record(
                  "WATCHBLE_SKIP",
                  command + " reason=no_advert silentMs=" + keySilent + " linked=" + keyLinked());
              bleCode = null;
            }
            if (bleCode != null && ble.ready()) {
              if (dispatchReady.getAsBoolean() && (!automatic || claimOnce.getAsBoolean())) {
                long bleStartedWall = System.currentTimeMillis();
                com.dautolock.app.watchkey.WatchKey.BleResult r =
                    ble.send(bleCode, WATCH_BLE_AUTO_TIMEOUT_MS);
                if (r != null && !r.success && r.controlSent) {
                  // The control frame went out but no answer came back: the car may already have
                  // acted on it. Read the state first and only fall back if it plainly did not.
                  diagnostics.record(
                      "WATCHBLE_FALLBACK",
                      command + " reason=" + r.message + " controlSent=true · 상태 확인 후 판단");
                  dispatched.set(true); // The frame went out; a failed read below is "미완료", not silent.
                  bleProbe = cloud.snapshot(target);
                  observeSnapshot(bleProbe, false);
                  long probedAt = System.currentTimeMillis();
                  boolean afterCommand =
                      bleProbe.fresh(probedAt)
                          && bleProbe.measuredAt >= bleStartedWall - BLE_PROBE_CLOCK_SLACK_MS
                          && bleProbe.locked != null;
                  diagnostics.record(
                      "WATCHBLE_UNCONFIRMED",
                      command
                          + " applied="
                          + (!afterCommand ? "unknown" : Boolean.valueOf(lock).equals(bleProbe.locked))
                          + " "
                          + bleProbe.diagnostic(probedAt));
                  if (!afterCommand) {
                    throw new Exception(
                        "블루투스 키 명령 결과 미확인 · 재전송하지 않았습니다. 차량에서 확인하세요");
                  }
                  if (Boolean.valueOf(lock).equals(bleProbe.locked)) {
                    // Applied without an answer: count it as the BLE send, no second command.
                    r =
                        new com.dautolock.app.watchkey.WatchKey.BleResult(
                            true, r.message, r.doors, true);
                  } else bleProbe = null; // Plainly not applied: the cloud sends it below.
                }
                if (r != null && r.success) {
                  sentByBle = true;
                  dispatched.set(true);
                  lastKeyAuthAt = SystemClock.elapsedRealtime();
                  diagnostics.record(
                      "CONTROL_SEND",
                      (automatic ? "AUTO " : "MANUAL ")
                          + command
                          + " source=ble doors="
                          + r.doors
                          + (bleProbe != null ? " answer=none stateConfirmed=true" : ""));
                  diagnostics.record(
                      "CONTROL_TIMING",
                      command
                          + " readyToSendMs="
                          + (SystemClock.elapsedRealtime() - readyAt)
                          + " channel=ble");
                  lastControl = command.label + " · 전송(BLE)";
                  changed();
                } else if (r == null || !r.controlSent) {
                  diagnostics.record(
                      "WATCHBLE_FALLBACK",
                      command + " reason=" + (r == null ? "unavailable" : r.message));
                }
              } else {
                note("거리 또는 설정이 바뀌어 제어를 취소했습니다");
                return;
              }
            }
            if (!sentByBle) {
              cloud.command(
                  target,
                  pinHash,
                  command,
                  () -> {
                    if (!dispatchReady.getAsBoolean()) return false;
                    if (automatic && !claimOnce.getAsBoolean()) return false;
                    if (lock
                        && automatic
                        && Integer.valueOf(3).equals(checked.power)
                        && "unavailable".equals(checked.epbStatus))
                      diagnostics.record(
                          "LOCK_GUARD",
                          "basis=stationary_closed_fresh_departure epb=unavailable"
                              + " stopAuthorized=false");
                    if (stop && "unavailable".equals(checked.epbStatus))
                      diagnostics.record(
                          "STOP_GUARD",
                          "basis=one_use_manual_parking_confirmation epb=unavailable");
                    diagnostics.record(
                        "CONTROL_SEND",
                        (automatic ? "AUTO " : "MANUAL ") + command + " source=cloud");
                    diagnostics.record(
                        "CONTROL_TIMING",
                        command
                            + " readyToSendMs="
                            + (SystemClock.elapsedRealtime() - readyAt)
                            + " preflightSource="
                            + (reused ? prepared.source : "fresh_query"));
                    dispatched.set(true);
                    lastControl = command.label + " · 전송 중";
                    changed();
                    return true;
                  });
            }
            VehicleSnapshot after;
            try {
              if (bleProbe != null) after = bleProbe; // Already read: it confirmed the BLE result.
              else {
                after = cloud.snapshot(target);
                observeSnapshot(after, false);
                // BYD sometimes answers the first read after a command without the lock (or power)
                // field, or with a measurement from before it (real log 11-09 21:08: LOCK
                // acknowledged, read-back locked=null, the result stayed "unconfirmed" for 46 min).
                // Ask again a couple of times. Reads only: the command itself is never resent.
                for (int retry = 1;
                    retry <= READBACK_RETRIES && readbackPending(after, stop, sentAt);
                    retry++) {
                  diagnostics.record(
                      "READBACK_RETRY",
                      command
                          + " attempt="
                          + retry
                          + " "
                          + after.diagnostic(System.currentTimeMillis()));
                  Thread.sleep(readbackRetryMs);
                  try {
                    VehicleSnapshot again = cloud.snapshot(target);
                    observeSnapshot(again, false);
                    after = again;
                  } catch (Exception retryError) {
                    diagnostics.record(
                        "READBACK_RETRY_FAILED", command + " " + retryError.getMessage());
                    break;
                  }
                }
              }
            } catch (Exception readbackError) {
              if (!sentByBle) throw readbackError;
              // BLE already actuated and the car confirmed it (result=1); a momentarily unavailable
              // cloud read-back must not undo that. Keep the preflight snapshot as a base so the
              // trip/arm/afterLock bookkeeping below still runs.
              after = checked;
              diagnostics.record(
                  "READBACK_BLE_ONLY", command + " cloudReadbackFailed=" + readbackError.getMessage());
            }
            diagnostics.record(
                "READBACK", command + " " + after.diagnostic(System.currentTimeMillis()));
            boolean verified =
                sentByBle
                    || (after.fresh(System.currentTimeMillis())
                        && after.measuredAt >= sentAt
                        && (stop
                            ? Integer.valueOf(1).equals(after.power)
                            : Boolean.valueOf(lock).equals(after.locked)));
            lastControl =
                (verified
                    ? command.label + " 완료 · 차량 상태 확인됨"
                    : "명령 응답 수신 · 실제 차량 상태는 미확인. 차량에서 확인하세요");
            note(lastControl);
            if (stop) stopStatus = "수동 종료 · " + lastControl;
            DoorNotifications.result(
                context,
                verified ? command.label + " 확인" : command.label + " 결과 미확인",
                (automatic ? "자동 제어 · " : "수동 제어 · ") + lastControl);
            if (lock && verified)
              afterLock(
                  () -> validSession(ticket, target) && (!automatic || (monitoring && autoEnabled)),
                  automatic,
                  departureConfirmed);
            if (stop && verified && Boolean.TRUE.equals(after.locked))
              closeWindowsAfterLock(() -> validSession(ticket, target));
            if (command == CloudClient.Command.UNLOCK && verified && validSession(ticket, target)) {
              if (!preserveTrip) startTrip(automatic ? "auto_unlock" : "manual_unlock");
              clearSecurityAlert("unlocked_by_this_phone");
            }
            if (automatic
                && command == CloudClient.Command.UNLOCK
                && verified
                && validSession(ticket, target)) {
              armEntry(ticket, after);
              entrySawClosed = wasClosed;
              entryOpened = wasClosed && Boolean.FALSE.equals(after.doorsClosed);
            }
          } catch (Exception e) {
            lastControl = command.label + " · " + e.getMessage();
            if (command == CloudClient.Command.STOP) stopStatus = "차량 종료 미완료 · " + e.getMessage();
            diagnostics.record(
                "CONTROL_BLOCK_OR_ERROR", (automatic ? "AUTO " : "MANUAL ") + lastControl);
            if (automatic && command == CloudClient.Command.LOCK)
              lockFailedAlert(
                  e.getMessage() == null ? "알 수 없음" : e.getMessage(),
                  departureConfirmed.getAsBoolean());
            if (!automatic || dispatched.get())
              DoorNotifications.result(context, command.label + " 미완료", lastControl);
            throw e;
          }
        },
        finished,
        automatic);
  }

  /** How long a start-authorization key connection stays open at most. */
  static final long START_AUTH_HOLD_MS = 120000;

  /**
   * The car was open but would not start until a key unlocked it again (real logs 11-10 21:29 and
   * 11-11 07:25): neither BYD's own unlock at switch-off nor our key unlock ten minutes earlier let it
   * start, and our 도어 열기 sent nothing because the car was already open. The official app's
   * unlock did — and its key stayed connected through the drive (CAR_KEY_SILENT linked=yes). So the
   * unlock goes out over our BLE key anyway (an open car stays open; nothing else changes) and the
   * key stays connected up to {@link #START_AUTH_HOLD_MS}, until the car comes on, the phone walks
   * away (the link drops, or the beacon settled far), or another key command needs it. The car
   * started 3 s after the first one (real log 11-11 11:10). Returns false only
   * when the key cannot be used (then the plain "already open" path runs). {@code auto}: from
   * {@link #boarding} (no pop-up notification; the activity log says it).
   */
  private boolean startAuthorization(boolean auto) {
    if (!ble.ready()) return false;
    long silent = keySilentMs(SystemClock.elapsedRealtime());
    if (silent >= KEY_SILENT_SKIP_MS) {
      // Another app holds the key module — then that key is present already.
      diagnostics.record(
          "START_AUTH_SKIP",
          (auto ? "auto=true " : "")
              + "reason=no_advert silentMs="
              + silent
              + " linked="
              + keyLinked());
      return false;
    }
    String head = auto ? "차에 탄 것을 감지 · " : "이미 열림 · ";
    diagnostics.record("START_AUTH", (auto ? "auto=true " : "") + "holdMs=" + START_AUTH_HOLD_MS);
    lastControl = head + "블루투스 키로 시동 인증 중…";
    changed();
    com.dautolock.app.watchkey.WatchKey.BleResult r =
        ble.sendAndHold(
            com.dautolock.app.watchble.WatchBleCodec.UNLOCK,
            WATCH_BLE_AUTO_TIMEOUT_MS,
            START_AUTH_HOLD_MS);
    if (r != null && r.success) {
      lastKeyAuthAt = SystemClock.elapsedRealtime();
      diagnostics.record(
          "CONTROL_SEND",
          (auto ? "AUTO" : "MANUAL") + " UNLOCK source=ble doors=" + r.doors + " purpose=start_auth");
      lastControl = head + "블루투스 키로 시동 인증 · 2분 동안 키 연결 유지";
      note(lastControl);
      if (!auto)
        DoorNotifications.result(
            context, "시동 인증 완료", "블루투스 키로 다시 열었습니다. 지금 시동을 걸어 보세요 (2분 동안 키 연결 유지)");
      changed();
      return true;
    }
    String reason = r == null ? "unavailable" : r.message;
    diagnostics.record(
        "START_AUTH_FAIL",
        (auto ? "auto=true " : "")
            + "reason="
            + reason
            + " controlSent="
            + (r != null && r.controlSent));
    lastControl = head + "블루투스 키 시동 인증 실패 (" + reason + ")";
    note(lastControl);
    if (!auto)
      DoorNotifications.result(context, lastControl, "공식 BYD 앱의 도어 열기로 시동을 걸어 보세요");
    changed();
    return true;
  }

  /**
   * Automatic start authorization (step 2): the same key unlock + hold when the phone settles in
   * the car. The car BLE and the trunk beacon both read {@link #IN_CAR_MARGIN_DB} above their open
   * thresholds for {@link #IN_CAR_SETTLE_MS} (real log 11-11: in the car the car BLE read −52…−61 and
   * the beacon −42…−58; standing by the car for 35 min the beacon stayed −56…−71). Once per
   * boarding: armed only after the phone has been out of the car for {@link #OUT_OF_CAR_MS} and
   * disarmed while the car is on — so not at each parking while still seated, but on getting back
   * in after standing by the open car (real logs 11-10 21:29, 11-11 07:25 and 11:10). Not while
   * this phone's key authorized within {@link #KEY_AUTH_FRESH_MS} (the normal approach unlock; it
   * waits, and authorizes only if the start has not come by then), and only after a fresh read
   * shows the car open, switched off and standing.
   */
  static final int IN_CAR_MARGIN_DB = 8;

  static final long IN_CAR_SETTLE_MS = 5000, OUT_OF_CAR_MS = 15000, KEY_AUTH_FRESH_MS = 60000;
  /** When this phone's key last authorized a command (elapsed clock; -1: not in this run). */
  volatile long lastKeyAuthAt = -1;

  private volatile boolean boardingArmed;
  private long inCarSince = -1, outOfCarSince = -1;

  /** Fed by the scanner about once a second; {@code inCar}: both signals read that strong. */
  void boarding(long now, boolean inCar) {
    if (carSeemsOn()) {
      if (boardingArmed) diagnostics.record("BOARDING", "armed=false reason=car_on");
      boardingArmed = false;
      inCarSince = outOfCarSince = -1;
      return;
    }
    // Our own key link silences the car BLE: that is neither getting in nor getting out.
    if (com.dautolock.app.watchble.BleAirtime.quiet(now)) return;
    if (!inCar) {
      inCarSince = -1;
      if (outOfCarSince < 0) outOfCarSince = now;
      if (!boardingArmed && now - outOfCarSince >= OUT_OF_CAR_MS) {
        boardingArmed = true;
        diagnostics.record("BOARDING", "armed=true");
      }
      return;
    }
    outOfCarSince = -1;
    if (inCarSince < 0) inCarSince = now;
    if (boardingArmed && now - inCarSince >= IN_CAR_SETTLE_MS && autoStartAuthorization(now))
      boardingArmed = false;
  }

  boolean boardingArmed() {
    return boardingArmed;
  }

  private boolean carSeemsOn() {
    if (carAudio.connected() || vehicleLink.connected()) return true;
    VehicleSnapshot s = snapshot;
    return s != null && Integer.valueOf(3).equals(s.power) && s.fresh(System.currentTimeMillis());
  }

  /** True when this boarding is dealt with (a check started, or nothing to do); false: try again. */
  private boolean autoStartAuthorization(long now) {
    if (!settings.getBoolean("autoStartAuth", true) || !ble.ready()) return true;
    if (!monitoring || !autoEnabled) return true;
    if (initializing || busy()) return false;
    if (lastKeyAuthAt >= 0 && now - lastKeyAuthAt < KEY_AUTH_FRESH_MS) return false;
    long silent = keySilentMs(now);
    if (silent >= KEY_SILENT_SKIP_MS) {
      diagnostics.record(
          "START_AUTH_SKIP",
          "auto=true reason=no_advert silentMs=" + silent + " linked=" + keyLinked());
      return true;
    }
    VehicleSnapshot known = snapshot;
    if (known != null
        && known.fresh(System.currentTimeMillis())
        && (Boolean.TRUE.equals(known.locked) || Integer.valueOf(3).equals(known.power))) {
      diagnostics.record(
          "START_AUTH_SKIP",
          "auto=true reason=car_state locked=" + known.locked + " power=" + known.power);
      return true;
    }
    int ticket = generation.get();
    String target = vin;
    diagnostics.record("START_AUTH_CHECK", "auto=true trigger=in_car");
    return run(
        () -> {
          if (!validSession(ticket, target)) return;
          requireVehicle();
          VehicleSnapshot s = cloud.snapshot(target);
          if (!validSession(ticket, target)) return;
          observeSnapshot(s, true);
          if (!s.fresh(System.currentTimeMillis())
              || !Boolean.FALSE.equals(s.locked)
              || !Integer.valueOf(1).equals(s.power)
              || (s.speed != null && s.speed > 0)) {
            diagnostics.record(
                "START_AUTH_SKIP",
                "auto=true reason=car_state fresh="
                    + s.fresh(System.currentTimeMillis())
                    + " locked="
                    + s.locked
                    + " power="
                    + s.power);
            return;
          }
          if (!monitoring || !autoEnabled || carAudio.connected()) return;
          startAuthorization(true);
        },
        () -> {},
        true);
  }

  /** Monitoring resumes with automation after a restart when the user had left it on. */
  boolean autoStartWanted() {
    return settings.getBoolean("autoStart", true) && !setupPending();
  }

  /**
   * 시동 끄기 while the Bridge cannot vouch for P (the phone is away from the car): one CLOSEAIR
   * ends the remote climate session, which powers a remote-started car down shortly after (real
   * log 11-07: the car was OFF ~40 s after its climate stopped). Sent once; the power read-backs
   * are spread over a minute through ordinary status reads, so the worker is not held.
   */
  private void remoteShutdown(int ticket, String target, BooleanSupplier valid, String bridgeBlock)
      throws Exception {
    if (!valid.getAsBoolean()) {
      note("거리 또는 설정이 바뀌어 제어를 취소했습니다");
      return;
    }
    diagnostics.record("STOP_VIA_CLIMATE_OFF", "reason=" + bridgeBlock);
    cancelReadinessWatch("원격 시동 끄기 요청");
    long sentAt = System.currentTimeMillis();
    // BLE-first: 시동 끄기 ends the remote climate (STOP_CLIMATE) over the watch key, else the cloud.
    boolean sentByBle = false;
    Byte bleCode = watchBleCode(CloudClient.Command.CLIMATE_OFF);
    if (bleCode != null && ble.ready() && valid.getAsBoolean()) {
      com.dautolock.app.watchkey.WatchKey.BleResult r = ble.send(bleCode, WATCH_BLE_AUTO_TIMEOUT_MS);
      if (r != null && r.success) {
        sentByBle = true;
        diagnostics.record(
            "CONTROL_SEND", "MANUAL CLIMATE_OFF purpose=remote_shutdown source=ble doors=" + r.doors);
        lastControl = "원격 시동 끄기 · 전송(BLE)";
        changed();
      } else {
        diagnostics.record(
            "WATCHBLE_FALLBACK",
            "CLIMATE_OFF purpose=remote_shutdown reason=" + (r == null ? "unavailable" : r.message));
      }
    }
    if (!sentByBle) {
      cloud.command(
          target,
          pinHash,
          CloudClient.Command.CLIMATE_OFF,
          () -> {
            if (!valid.getAsBoolean()) return false;
            diagnostics.record(
                "CONTROL_SEND", "MANUAL CLIMATE_OFF purpose=remote_shutdown source=cloud");
            lastControl = "원격 시동 끄기 · 전송 중";
            changed();
            return true;
          });
    }
    remoteShutdownVin = target;
    remoteShutdownSince = SystemClock.elapsedRealtime();
    remoteShutdownAt = sentAt;
    stopStatus = lastControl = "원격 시동 끄기 요청 완료 · 차량 전원 OFF 확인 중";
    note(lastControl);
    for (long delay : REMOTE_SHUTDOWN_CHECKS_MS)
      main.postDelayed(
          () -> {
            if (remoteShutdownAt != sentAt) return;
            if (validSession(ticket, target)) readStatus("remote_shutdown_check");
            else remoteShutdownAt = -1;
          },
          delay);
  }

  void prepareMonitoring() {
    diagnostics.record(
        "MONITOR_SETUP",
        "account="
            + cloud.protocol.isLoggedIn()
            + " vehicleSelected="
            + !vin.isEmpty()
            + " near="
            + settings.getInt("near", Controller.DEFAULT_NEAR)
            + " far="
            + settings.getInt("far", Controller.DEFAULT_FAR));
    if ((!cloud.protocol.isLoggedIn() && !hasSavedLogin()) || vin.isEmpty()) {
      note("계정 로그인과 차량 선택 후 자동 제어를 켤 수 있습니다");
      return;
    }
    run(
        () -> {
          capabilities = cloud.capabilities(vin);
          diagnostics.record(
              "CAPABILITIES",
              "lock="
                  + feature(CloudClient.Command.LOCK)
                  + " unlock="
                  + feature(CloudClient.Command.UNLOCK));
          note("거리 관찰 준비 · 차량 기능 확인 완료");
        });
  }

  String autoUnavailable() {
    if (initializing) return "계정 정보를 복원하고 있습니다";
    if (!monitoring) return "거리 관찰을 먼저 시작하세요";
    if (!cloud.protocol.isLoggedIn() && !hasSavedLogin()) return "BYD 계정 로그인이 필요합니다";
    if (vin.isEmpty()) return "공유 차량을 먼저 선택하세요";
    return null;
  }

  void stop() {
    cancelPendingStop("거리 관찰 종료");
    cancelReadinessWatch("거리 관찰 종료");
    statusRefreshRequested = false;
    main.removeCallbacks(statusRefreshTask);
    generation.incrementAndGet();
    discardApproachPreflight();
    autoEnabled = false;
    entryUntil = 0;
    monitoring = false;
    context.stopService(new Intent(context, ProximityService.class));
    vehicleLink.stop();
    context.stopService(new Intent(context, VehicleLinkService.class));
    changed();
  }

  void auto(boolean enabled) {
    if (enabled && autoUnavailable() != null) {
      note(autoUnavailable());
      return;
    }
    if (!enabled) cancelPendingStop("자동 제어 꺼짐");
    generation.incrementAndGet();
    discardApproachPreflight();
    autoEnabled = enabled;
    entryUntil = 0;
    note(
        enabled
            ? "자동 도어 켜짐 · 접근 시 해제 / 이탈·신호 " + settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS) + "초 끊김 시 잠금"
            : "관찰 모드 · 차량 명령을 보내지 않습니다");
  }

  void device(String name, String address) {
    stop();
    settings.edit().putString("deviceName", name).putString("address", address).apply();
    note("블루투스 기기 선택 완료 · 관찰에서 수신 여부를 확인하세요");
  }

  /** Register the auxiliary beacon. Observed only for now (does not change lock/unlock yet). */
  void beaconDevice(String name, String address) {
    stop();
    settings.edit().putString("beaconName", name).putString("beaconAddress", address).apply();
    note("보조 BLE(비콘) 선택 완료 · 메인에서 보조 신호 세기를 관찰하세요");
  }

  void clearBeacon() {
    stop();
    settings.edit().remove("beaconName").remove("beaconAddress").apply();
    beaconRssi = Double.NaN;
    beaconStrength = 0;
    beaconSignal = "보조 신호 없음";
    beaconDetail = "";
    note("보조 BLE 해제됨");
  }

  /** Save the beacon's own near/far thresholds and restart observation with them. */
  void beaconThresholds(int near, int far) {
    new ProximityEngine(near, far, 1000, 2000, 10000); // Validates the near/far gap; throws if bad.
    settings.edit().putInt("beaconNear", near).putInt("beaconFar", far).apply();
    diagnostics.record("BEACON_SETTINGS", "near=" + near + " far=" + far);
    boolean resume = monitoring || settings.getBoolean("autoStart", true);
    boolean automatic = autoEnabled || settings.getBoolean("autoStart", true);
    note("보조 BLE 작동 거리 저장 · 관찰을 다시 시작합니다");
    if (resume && setupReady()) startMonitoring(automatic, true);
  }

  ProximityEngine proximityEngine() {
    return new ProximityEngine(
        settings.getInt("near", Controller.DEFAULT_NEAR),
        settings.getInt("far", Controller.DEFAULT_FAR),
        settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT) * 1000L,
        settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT) * 1000L,
        settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS) * 1000L);
  }

  /**
   * Saves the thresholds found by the position measurement for both signals at once, so scanning
   * restarts a single time. Both pairs are validated before anything is written.
   */
  void calibratedThresholds(int near, int far, Integer beaconNear, Integer beaconFar) {
    new ProximityEngine(near, far, 1000, 2000, 10000); // Validates the near/far gap; throws if bad.
    boolean beacon = beaconNear != null && beaconFar != null;
    if (beacon) new ProximityEngine(beaconNear, beaconFar, 1000, 2000, 10000);
    if (beacon) {
      settings.edit().putInt("beaconNear", beaconNear).putInt("beaconFar", beaconFar).apply();
      diagnostics.record("BEACON_SETTINGS", "near=" + beaconNear + " far=" + beaconFar + " source=calibration");
    }
    diagnostics.record("PROXIMITY_CALIBRATED", "near=" + near + " far=" + far + " beacon=" + beacon);
    // The restart inside picks up the beacon values written above.
    thresholds(
        near,
        far,
        settings.getInt("nearWaitSeconds", DEFAULT_NEAR_WAIT),
        settings.getInt("farWaitSeconds", DEFAULT_FAR_WAIT),
        settings.getInt("lossLockSeconds", DEFAULT_LOSS));
  }

  boolean beaconConfigured() {
    return android.bluetooth.BluetoothAdapter.checkBluetoothAddress(
        settings.getString("beaconAddress", ""));
  }

  /** Auxiliary engine for the beacon. Observed only for now; shares the dwell/loss timings. */
  ProximityEngine beaconProximityEngine() {
    return new ProximityEngine(
        settings.getInt("beaconNear", Controller.DEFAULT_BEACON_NEAR),
        settings.getInt("beaconFar", Controller.DEFAULT_BEACON_FAR),
        settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT) * 1000L,
        settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT) * 1000L,
        settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS) * 1000L);
  }

  void thresholds(int near, int far, int nearSeconds, int farSeconds, int lossSeconds) {
    new ProximityEngine(near, far, nearSeconds * 1000L, farSeconds * 1000L, lossSeconds * 1000L);
    boolean resume = monitoring || settings.getBoolean("autoStart", true);
    boolean automatic = autoEnabled || settings.getBoolean("autoStart", true);
    generation.incrementAndGet();
    discardApproachPreflight();
    entryUntil = 0;
    settings
        .edit()
        .putInt("near", near)
        .putInt("far", far)
        .putInt("nearWaitSeconds", nearSeconds)
        .putInt("farWaitSeconds", farSeconds)
        .putInt("lossLockSeconds", lossSeconds)
        .apply();
    diagnostics.record(
        "PROXIMITY_SETTINGS",
        "near="
            + near
            + " far="
            + far
            + " nearWaitSeconds="
            + nearSeconds
            + " farWaitSeconds="
            + farSeconds
            + " lossLockSeconds="
            + lossSeconds);
    note("감도·대기 시간 저장 · 새 기준으로 관찰을 다시 시작합니다");
    if (resume && setupReady()) startMonitoring(automatic, true);
    else if (!setupReady()) stop();
    requestStatusRefresh("thresholds_saved");
  }

  void exportDiagnostics(android.net.Uri uri) {
    diagnostics.snapshot(
        data -> {
          boolean saved = false;
          try (java.io.OutputStream out =
              context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new java.io.IOException("output");
            out.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            saved = true;
          } catch (Exception e) {
            note("진단 로그 내보내기 실패: " + e.getClass().getSimpleName());
          }
          // Start a fresh log after a successful save so the file does not keep accumulating old
          // sessions. Cleared only when the save succeeded, so a failed export never loses the log.
          if (saved)
            diagnostics.clear(() -> note("진단 로그를 저장하고 기존 로그를 비웠습니다 · 새 로그를 기록합니다"));
        });
  }

  /**
   * Copies the log into a private cache file for the share sheet (served by
   * DiagnosticsShareProvider). Only the latest copy is kept; the on-device log is not cleared here
   * because sending cannot be confirmed — the screen offers that afterwards. Calls back on the main
   * thread with the file, or null on failure.
   */
  void shareDiagnostics(Consumer<java.io.File> done) {
    diagnostics.snapshot(
        data -> {
          java.io.File file = null;
          try {
            java.io.File dir = new java.io.File(context.getCacheDir(), DiagnosticsShareProvider.DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("share directory");
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            file =
                new java.io.File(
                    dir,
                    "D-Autolock-diagnostics-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.KOREA)
                            .format(new Date())
                        + ".txt");
            java.nio.file.Files.write(
                file.toPath(), data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          } catch (Exception e) {
            note("진단 로그 공유 파일을 만들지 못했습니다: " + e.getClass().getSimpleName());
            file = null;
          }
          java.io.File result = file;
          main.post(() -> done.accept(result));
        });
  }

  private boolean validSession(int ticket, String target) {
    return ticket == generation.get() && target.equals(vin);
  }

  private void afterLock(
      BooleanSupplier valid, boolean automatic, BooleanSupplier departureConfirmed) {
    afterLock(valid, automatic, departureConfirmed, false);
  }

  private void afterLock(
      BooleanSupplier valid,
      boolean automatic,
      BooleanSupplier departureConfirmed,
      boolean alreadyLocked) {
    cancelReadinessWatch("도어 잠금 처리");
    entryUntil = 0;
    String owner = ownershipBlock();
    boolean schedule = false;
    if (automatic && settings.getBoolean("autoStop", true)) {
      if (!departureConfirmed.getAsBoolean()) {
        stopStatus = "자동 종료 보류 · BLE 끊김 또는 이탈 신호 미확인";
        diagnostics.record(
            "AUTO_STOP_SKIP", "reason=departure_not_confirmed departureConfirmed=false");
        note(stopStatus);
      } else if (owner != null) {
        stopStatus = "자동 종료 안 함 · " + owner;
        diagnostics.record("AUTO_STOP_SKIP", "reason=ownership " + owner);
        note(stopStatus);
      } else schedule = true;
    }
    endTrip("locked");
    // Windows no longer wait for Stop: the delay or a cancellation must not leave them open.
    // A repeated automatic lock on an already locked car whose windows BYD reports closed does
    // not resend: BYD rejected those redundant requests (6042/6048) in real logs.
    // Also skipped while the car is still ON with all windows reported closed: BYD rejected
    // exactly that request with 6048 right after parking (real logs, 5 times).
    VehicleSnapshot s = snapshot;
    if (automatic
        && s != null
        && Boolean.TRUE.equals(s.windowsClosed)
        && (alreadyLocked || Integer.valueOf(3).equals(s.power))) {
      windowsStatus = "창문 닫기 생략 · BYD 조회상 모두 닫힘";
      diagnostics.record(
          "WINDOWS_SKIP", "alreadyLocked=" + alreadyLocked + " power=" + s.power + " cloudClosed=true");
    } else closeWindowsAfterLock(valid);
    if (schedule) scheduleStop(() -> valid.getAsBoolean() && departureConfirmed.getAsBoolean());
    // Warn about a locked-but-running car only after the user has actually left. While the phone is
    // still near (driving, or sitting in the running car) a locked + powered-on car is normal, not a
    // security problem — without this guard a red alert fired on every drive (real log:
    // locked_power_on right after AUTO_STOP_SKIP departure_not_confirmed, phone NEAR). Same departure
    // guard as lockFailedAlert (v0.3.14).
    else if (automatic
        && s != null
        && Integer.valueOf(3).equals(s.power)
        && departureConfirmed.getAsBoolean())
      raiseSecurityAlert(
          "locked_power_on",
          "도어는 잠겼지만 시동이 켜져 있습니다. 직접 시동을 끄거나 ‘시동 끄고 잠금’을 누르세요. 사유: " + stopStatus);
  }

  // ---- Automatic Stop safety: P only, this phone's own trip, delayed re-check ----

  int stopDelaySeconds() {
    return Math.max(5, Math.min(120, settings.getInt("stopDelaySeconds", 15)));
  }

  boolean sharedVehicle() {
    return settings.getBoolean("sharedVehicle", false);
  }

  /** Called after this phone's own verified unlock. */
  void startTrip(String reason) {
    tripUnlockedAt = SystemClock.elapsedRealtime();
    tripDriven = false;
    saveTrip();
    diagnostics.record("TRIP_START", "reason=" + reason);
  }

  private void endTrip(String reason) {
    if (tripUnlockedAt < 0) return;
    tripUnlockedAt = -1;
    tripDriven = false;
    saveTrip();
    diagnostics.record("TRIP_END", "reason=" + reason);
  }

  /** Survives app updates/restarts (real log: an update mid-trip lost ownership). Wall clock. */
  private void saveTrip() {
    long at = tripUnlockedAt;
    settings
        .edit()
        .putLong(
            "tripStartedWall",
            at < 0 ? 0 : System.currentTimeMillis() - (SystemClock.elapsedRealtime() - at))
        .putBoolean("tripDriven", at >= 0 && tripDriven)
        .apply();
  }

  private void restoreTrip() {
    long wall = settings.getLong("tripStartedWall", 0), age = System.currentTimeMillis() - wall;
    if (wall <= 0 || age < 0 || age > TRIP_MAX_MS) return;
    tripUnlockedAt = Math.max(0, SystemClock.elapsedRealtime() - age);
    tripDriven = settings.getBoolean("tripDriven", false);
    diagnostics.record("TRIP_RESTORE", "ageMs=" + age + " driven=" + tripDriven);
  }

  /** Null only when this phone opened the car and its own Bridge then saw the car leave P. */
  String ownershipBlock() {
    if (sharedVehicle()) return "공유 차량 모드";
    long at = tripUnlockedAt;
    if (at < 0) return "이 휴대폰이 연 운행이 아닙니다";
    if (SystemClock.elapsedRealtime() - at > TRIP_MAX_MS) return "운행 시작 후 12시간 초과";
    if (!tripDriven) return "이 휴대폰의 차량 보조 앱에서 주행(P 해제)을 확인하지 못했습니다";
    return null;
  }

  /** Every authenticated Bridge sample. Gear out of P or a released brake is vehicle activity. */
  void vehicleSample(com.dautolock.link.LinkProtocol.Sample s) {
    long now = SystemClock.elapsedRealtime();
    // Auto-unlock on parking: a live, stable P after driving (checked before the brake-hold return
    // below, since parking engages the brake). Default off; independent of the automation switch.
    if (settings.getBoolean("unlockOnPark", false) && parkUnlock.onSample(s.gear, s.quality, now)) {
      diagnostics.record("PARK_UNLOCK", "trigger=bridge_P gear=" + s.gearLabel());
      main.post(() -> unlockOnPark(now, 0));
    }
    int parked = parkedOn.onSample(s.gear, s.quality, now);
    if (parked == ParkedOnWatch.STARTED) parkedOnStarted();
    else if (parked == ParkedOnWatch.ENDED) DoorNotifications.cancelParkedOn(context);
    boolean moving =
        s.quality == 0
            && s.gear != com.dautolock.link.LinkProtocol.UNKNOWN
            && s.gear != com.dautolock.link.LinkProtocol.P;
    if (moving) outOfParkAt = now;
    if (!moving && s.brake != 0) return;
    vehicleActivityAt = SystemClock.elapsedRealtime();
    if (moving && tripUnlockedAt >= 0 && !tripDriven) {
      tripDriven = true;
      saveTrip();
      diagnostics.record("TRIP_DRIVEN", "gear=" + s.gearLabel());
    }
    if (stopDueAt >= 0)
      main.post(
          () ->
              cancelPendingStop(
                  "대기 중 차량 조작 감지 (" + (moving ? "기어 " + s.gearLabel() : "주차브레이크 해제") + ")"));
  }

  /**
   * Unlock the doors because the driver just shifted to P (feature {@code unlockOnPark}). Uses the
   * manual unlock path: parking usually leaves the engine ON (power=3), which the automatic unlock
   * gate blocks, whereas the manual gate allows unlock once the car is confirmed stopped — exactly
   * the parked state here. Skipped if a current reading already shows it unlocked; waits briefly
   * for a busy worker; dropped if the car leaves P meanwhile.
   */
  static final long PARK_UNLOCK_RETRY_MS = 3000;
  /** Last trusted Bridge gear other than P (elapsed). */
  private volatile long outOfParkAt = -1;
  static final int PARK_UNLOCK_MAX_WAITS = 5;

  private void unlockOnPark(long triggeredAt, int waits) {
    // Shifted out of P since (the trigger itself was a P sample): the moment has passed.
    if (outOfParkAt >= triggeredAt) {
      diagnostics.record("PARK_UNLOCK_SKIP", "reason=moved_again waits=" + waits);
      return;
    }
    // Another request held the worker (real log 11-10 21:23: the climate READY watch read): wait for
    // it a little instead of dropping the unlock.
    if (busy()) {
      if (waits >= PARK_UNLOCK_MAX_WAITS) {
        diagnostics.record("PARK_UNLOCK_SKIP", "reason=busy waits=" + waits);
        return;
      }
      if (waits == 0) diagnostics.record("PARK_UNLOCK_WAIT", "reason=busy retryMs=" + PARK_UNLOCK_RETRY_MS);
      main.postDelayed(() -> unlockOnPark(triggeredAt, waits + 1), PARK_UNLOCK_RETRY_MS);
      return;
    }
    if (vin.isEmpty() || pinHash.isEmpty()) {
      diagnostics.record("PARK_UNLOCK_SKIP", "reason=not_configured");
      return;
    }
    // Only a current reading may say "already open": the car locks itself once it drives off, so an
    // old "unlocked" says nothing (real log 11-10 21:34: skipped on an 11-minute-old reading). An
    // older one goes the normal way — that path reads first and skips if the car really is open.
    VehicleSnapshot known = snapshot;
    long wall = System.currentTimeMillis();
    if (known != null && Boolean.FALSE.equals(known.locked) && known.fresh(wall)) {
      diagnostics.record(
          "PARK_UNLOCK_SKIP", "reason=already_unlocked ageMs=" + (wall - known.measuredAt));
      return;
    }
    note("주차(P) 감지 · 도어 잠금 해제");
    // Preserve the in-progress driven trip: parking ends a drive, it does not start a new one.
    // Resetting ownership here would make the following departure lock skip the auto-Stop the user
    // enabled (a verified UNLOCK otherwise restarts the trip and clears tripDriven).
    executeParkUnlock();
  }

  private void executeParkUnlock() {
    executeCommand(
        CloudClient.Command.UNLOCK,
        false,
        () -> true,
        () -> true,
        () -> {},
        () -> {},
        () -> false,
        -1,
        SystemClock.elapsedRealtime(),
        true);
  }

  private void scheduleStop(BooleanSupplier valid) {
    int ticket = stopTicket.incrementAndGet();
    long delay = stopDelaySeconds() * 1000L, scheduledAt = SystemClock.elapsedRealtime();
    stopDueAt = scheduledAt + delay;
    stopStatus = "자동 종료 대기 · " + delay / 1000 + "초 후 P단·정차·잠금을 다시 확인합니다";
    diagnostics.record("AUTO_STOP_SCHEDULED", "delayMs=" + delay);
    note(stopStatus);
    DoorNotifications.stopPending(context, (int) (delay / 1000));
    main.postDelayed(() -> runScheduledStop(ticket, scheduledAt, valid, 0), delay);
  }

  private void runScheduledStop(int ticket, long scheduledAt, BooleanSupplier valid, int attempt) {
    if (ticket != stopTicket.get()) return;
    BooleanSupplier current =
        () ->
            ticket == stopTicket.get()
                && vehicleActivityAt < scheduledAt
                && valid.getAsBoolean();
    if (vehicleActivityAt >= scheduledAt) {
      cancelPendingStop("대기 중 차량 조작 감지");
      return;
    }
    if (inCarByAudio()) { // Back in the car (its audio reconnected): not a departure any more.
      cancelPendingStop("휴대폰이 차 블루투스에 연결됨 · 차 안");
      return;
    }
    if (!valid.getAsBoolean()) {
      cancelPendingStop("이탈 신호 또는 자동 제어 상태 변경");
      return;
    }
    boolean started =
        run(
            () -> {
              if (ticket != stopTicket.get()) return;
              stopDueAt = -1;
              DoorNotifications.cancelStopPending(context);
              if (!current.getAsBoolean()) {
                stopStatus = "자동 종료 취소 · 대기 중 조건 변경";
                diagnostics.record("AUTO_STOP_CANCEL", "condition_changed_before_check");
                note(stopStatus);
                return;
              }
              stopAfterLock(current);
            },
            () -> {},
            true);
    if (started) return;
    if (attempt < 20) main.postDelayed(() -> runScheduledStop(ticket, scheduledAt, valid, attempt + 1), 1000);
    else cancelPendingStop("다른 차량 요청 처리 중");
  }

  /** Safe to call from any thread; only cancels a Stop that has not started its final check. */
  void cancelPendingStop(String reason) {
    if (stopDueAt < 0) return;
    stopTicket.incrementAndGet();
    stopDueAt = -1;
    stopStatus = "자동 종료 취소 · " + reason;
    diagnostics.record("AUTO_STOP_CANCEL", reason);
    DoorNotifications.cancelStopPending(context);
    note(stopStatus);
  }

  private void stopAfterLock(BooleanSupplier valid) {
    try {
      if (!valid.getAsBoolean()) return;
      observeSnapshot(cloud.snapshot(vin), true);
      diagnostics.record("AUTO_STOP_PREFLIGHT", snapshot.diagnostic(System.currentTimeMillis()));
      if (snapshot.fresh(System.currentTimeMillis()) && Integer.valueOf(1).equals(snapshot.power)) {
        stopStatus = "자동 종료 · BYD 조회상 이미 전원 OFF";
        diagnostics.record("AUTO_STOP_SKIP", "power=OFF");
        changed();
        return;
      }
      String block = automaticStopBlock();
      if (block != null) throw new Exception(block);
      if (!CloudClient.hasFeature(capabilities, CloudClient.Command.STOP.feature))
        capabilities = cloud.capabilities(vin);
      if (!CloudClient.hasFeature(capabilities, CloudClient.Command.STOP.feature))
        throw new Exception("차량 종료 기능 지원 미확인");
      long sent = System.currentTimeMillis();
      cloud.command(
          vin,
          pinHash,
          CloudClient.Command.STOP,
          () -> {
            String latestBlock = automaticStopBlock();
            if (!valid.getAsBoolean() || latestBlock != null) {
              diagnostics.record(
                  "AUTO_STOP_RECHECK_BLOCK", latestBlock == null ? "이탈·세션 조건 변경" : latestBlock);
              return false;
            }
            diagnostics.record(
                "AUTO_STOP_SEND",
                "afterLock=true source=" + vehicleLink.lastStopBasis + " ownTrip=true delayed=true");
            return true;
          });
      observeSnapshot(cloud.snapshot(vin), true);
      diagnostics.record("AUTO_STOP_READBACK", snapshot.diagnostic(System.currentTimeMillis()));
      boolean verified =
          snapshot.fresh(System.currentTimeMillis())
              && snapshot.measuredAt >= sent
              && Integer.valueOf(1).equals(snapshot.power);
      stopStatus = verified ? "자동 종료 · 전원 OFF 확인" : "자동 종료 응답 수신 · 실제 전원 미확인";
      note(stopStatus);
      if (!verified)
        raiseSecurityAlert("stop_unverified", "시동 꺼짐을 확인하지 못했습니다. 차량을 확인하세요.");
      DoorNotifications.result(context, "도어 잠김 · " + stopStatus, "BYD에서 조회한 결과입니다");
    } catch (Exception e) {
      stopStatus = "자동 종료 보류 · " + e.getMessage();
      note(stopStatus);
      VehicleSnapshot left = snapshot;
      if (left != null && Integer.valueOf(3).equals(left.power) && !inCarByAudio())
        raiseSecurityAlert(
            "stop_blocked",
            "도어는 잠겼지만 시동이 켜져 있습니다. 직접 시동을 끄거나 ‘시동 끄고 잠금’을 누르세요. 사유: "
                + e.getMessage());
      diagnostics.record("AUTO_STOP_BLOCK_OR_ERROR", e.getMessage());
      DoorNotifications.result(context, "도어 잠김 · 자동 종료 미완료", e.getMessage());
    }
  }

  /** Automatic Stop only with a live, stable P from the paired Bridge. No Cloud-brake fallback. */
  private String automaticStopBlock() {
    if (inCarByAudio()) return "휴대폰이 차 블루투스(오디오·통화)에 연결됨 · 차 안으로 보고 종료 안 함";
    if (!vehicleLink.required() || !vehicleLink.configured())
      return "실제 P단 확인 불가 · 차량 보조 앱(QR) 연결이 필요합니다";
    return vehicleLink.automaticStopBlock(snapshot);
  }

  void startVehicleLink() {
    if (!vehicleLink.configured()) return;
    try {
      context.startForegroundService(new Intent(context, VehicleLinkService.class));
    } catch (Exception e) {
      vehicleLink.status = "차량 상태 연결 시작 보류 · 주변 기기 권한을 확인하세요";
      diagnostics.record("VEHICLE_LINK_START_ERROR", e.getClass().getSimpleName());
    }
  }

  /** Keeps 시동 켜기 at 23 °C on this car: its 1–17 scale offset comes from the HVAC range. */
  void learnClimateScale(HvacSnapshot hvac) {
    Double offset = hvac.tempOffset();
    if (offset == null || offset == cloud.climateTempOffset) return;
    cloud.climateTempOffset = offset;
    settings.edit().putFloat("climateTempOffset", offset.floatValue()).apply();
    diagnostics.record(
        "CLIMATE_TEMP_SCALE",
        "offset=" + offset + " range=" + hvac.rangeMin + "~" + hvac.rangeMax + " setTemp=" + hvac.setTemp);
  }

  /**
   * 창문 닫기 and 차량 찾기 from the main screen. Sent once; an unconfirmed result is never
   * resent. Closing windows first needs a fresh stationary state and is read back afterwards;
   * 차량 찾기 (horn and lights) has no state to read back.
   */
  void accessory(CloudClient.Command command) {
    int ticket = generation.get();
    String target = vin;
    boolean window = command != CloudClient.Command.FIND_CAR;
    run(
        () -> {
          try {
            requireVehicle();
            if (pinHash.isEmpty()) throw new Exception("제어 PIN이 없습니다. 다시 로그인하세요");
            if (!CloudClient.hasFeature(capabilities, command.feature))
              capabilities = cloud.capabilities(target);
            if (!CloudClient.hasFeature(capabilities, command.feature))
              throw new Exception("이 차량의 " + command.label + " 지원을 확인하지 못했습니다");
            long sentAt = System.currentTimeMillis();
            if (window) {
              note(command.label + " 전 차량 상태 확인 중…");
              observeSnapshot(cloud.snapshot(target), true);
              VehicleSnapshot checked = snapshot;
              long now = System.currentTimeMillis();
              diagnostics.record("PREFLIGHT", "MANUAL " + command + " " + checked.diagnostic(now));
              if (!checked.fresh(now) || checked.speed == null || checked.speed != 0d)
                throw new Exception("제어 보류: 최신 정차 상태를 확인하지 못했습니다");
              if (Boolean.TRUE.equals(checked.windowsClosed)) {
                windowsStatus = "BYD 조회상 이미 모든 창문 닫힘 · 명령 생략";
                lastControl = windowsStatus;
                diagnostics.record("CONTROL_SKIP_STATE", "MANUAL " + command + " source=cloud");
                note(lastControl);
                DoorNotifications.result(context, lastControl, "BYD에서 조회한 현재 상태입니다");
                return;
              }
              sentAt = System.currentTimeMillis();
            }
            lastControl = command.label + " · 전송 중";
            changed();
            cloud.command(
                target,
                pinHash,
                command,
                () -> {
                  if (!validSession(ticket, target)) return false;
                  diagnostics.record("CONTROL_SEND", "MANUAL " + command);
                  return true;
                });
            if (!window) {
              lastControl = "차량 찾기 요청 완료 · 경적과 라이트로 위치를 알립니다";
              note(lastControl);
              DoorNotifications.result(context, "차량 찾기 요청", "수동 제어 · " + lastControl);
              return;
            }
            VehicleSnapshot after = cloud.snapshot(target);
            observeSnapshot(after, false);
            long now = System.currentTimeMillis();
            diagnostics.record("READBACK", command + " " + after.diagnostic(now));
            boolean verified =
                after.fresh(now)
                    && after.measuredAt >= sentAt
                    && Boolean.TRUE.equals(after.windowsClosed);
            windowsStatus =
                verified ? "창문 닫기 완료 · BYD 조회상 모두 닫힘" : "창문 닫기 응답 수신 · 실제 창문 상태 미확인";
            lastControl = windowsStatus;
            note(lastControl);
            DoorNotifications.result(
                context,
                verified ? command.label + " 확인" : command.label + " 결과 미확인",
                "수동 제어 · " + lastControl);
          } catch (Exception e) {
            lastControl = command.label + " · " + e.getMessage();
            if (window) windowsStatus = command.label + " 미완료 · " + e.getMessage();
            diagnostics.record("CONTROL_BLOCK_OR_ERROR", "MANUAL " + lastControl);
            DoorNotifications.result(context, command.label + " 미완료", lastControl);
            throw e;
          }
        });
  }

  private void closeWindowsAfterLock(BooleanSupplier valid) {
    if (!settings.getBoolean("closeWindows", true)) {
      windowsStatus = "창문 닫기 · 설정 꺼짐";
      diagnostics.record("WINDOWS_SKIP", "option=false");
      return;
    }
    try {
      if (!valid.getAsBoolean()) return;
      if (!CloudClient.hasFeature(capabilities, CloudClient.Command.CLOSE_WINDOWS.feature))
        capabilities = cloud.capabilities(vin);
      if (!CloudClient.hasFeature(capabilities, CloudClient.Command.CLOSE_WINDOWS.feature))
        throw new Exception("차량의 창문 닫기 지원 미확인");
      if (snapshot == null
          || !snapshot.fresh(System.currentTimeMillis())
          || snapshot.speed == null
          || snapshot.speed != 0d
          || !Boolean.TRUE.equals(snapshot.locked)) throw new Exception("최신 정차·잠금 상태 미확인");
      // This read already shows the car locked, so it was taken after the lock: if it reports all
      // four windows shut there is nothing to close. Sending anyway only risked a scary "result
      // unknown · check the car" when BYD never answered the redundant request (real log 11-09
      // 20:59 and 21:57, both with every window reported closed). Open or unknown still sends once.
      if (Boolean.TRUE.equals(snapshot.windowsClosed)) {
        windowsStatus = "창문 닫기 생략 · BYD 조회상 모두 닫힘";
        diagnostics.record("WINDOWS_SKIP", "lockedRead=true cloudClosed=true");
        return;
      }
      diagnostics.record(
          "WINDOWS_PREFLIGHT", "cloudClosed=" + snapshot.windowsClosed + " sendOnce=true");
      long sent = System.currentTimeMillis();
      cloud.command(
          vin,
          pinHash,
          CloudClient.Command.CLOSE_WINDOWS,
          () -> {
            if (!valid.getAsBoolean()) return false;
            diagnostics.record("WINDOWS_SEND", "afterLock=true");
            return true;
          });
      observeSnapshot(cloud.snapshot(vin), true);
      diagnostics.record("WINDOWS_READBACK", snapshot.diagnostic(System.currentTimeMillis()));
      boolean confirmed =
          snapshot.fresh(System.currentTimeMillis())
              && snapshot.measuredAt >= sent
              && Boolean.TRUE.equals(snapshot.windowsClosed);
      windowsStatus = confirmed ? "창문 닫기 요청 완료 · BYD 조회상 모두 닫힘" : "창문 닫기 응답 수신 · 실제 창문 상태 미확인";
      note(windowsStatus);
    } catch (Exception e) {
      windowsStatus = "창문 닫기 미완료 · " + e.getMessage();
      note(windowsStatus);
      diagnostics.record("WINDOWS_ERROR", e.getMessage());
    }
  }

  void readyOption(boolean enabled) {
    settings.edit().putBoolean("autoReady", enabled).apply();
    entryUntil = 0;
    note(enabled ? "탑승 시 공조 시작 켜짐 · 자동 해제 후 문 열림을 기다립니다" : "탑승 시 공조 시작 꺼짐");
  }

  private void armEntry(int ticket, VehicleSnapshot s) {
    if (!settings.getBoolean("autoReady", false)) {
      climateStatus = "공조 연동 · 설정 꺼짐";
      return;
    }
    if (recentlyPowered()) {
      // The car was ON moments ago: a door opening now is most likely someone getting out.
      climateStatus = "공조 연동 · 최근 전원 ON 기록이 있어 탑승 감시 안 함";
      diagnostics.record("ENTRY_SKIP", "reason=recent_power_on");
      return;
    }
    entryTicket = ticket;
    entrySawClosed = Boolean.TRUE.equals(s.doorsClosed);
    entryOpened = false;
    entryUntil = SystemClock.elapsedRealtime() + 90000;
    nextEntryCheck = 0;
    climateStatus = "공조 연동 · 문 열림 대기 (90초)";
    diagnostics.record("ENTRY_WATCH", "armed=true closed=" + entrySawClosed + " expiresSeconds=90");
  }

  boolean pollEntry(BooleanSupplier nearby) {
    long now = SystemClock.elapsedRealtime();
    if (entryUntil == 0
        || now >= entryUntil
        || now < nextEntryCheck
        || cloud.backoffMillis() > 0
        || !autoEnabled
        || !monitoring
        || !settings.getBoolean("autoReady", false)
        || !nearby.getAsBoolean()) return false;
    String target = vin;
    int ticket = entryTicket;
    return run(
        () -> {
          nextEntryCheck = SystemClock.elapsedRealtime() + 5000;
          if (!validSession(ticket, target) || !nearby.getAsBoolean()) return;
          VehicleSnapshot s = cloud.snapshot(target);
          observeSnapshot(s, true);
          diagnostics.record("ENTRY_STATE", s.diagnostic(System.currentTimeMillis()));
          if (!s.fresh(System.currentTimeMillis())) return;
          if (Boolean.TRUE.equals(s.doorsClosed)) entrySawClosed = true;
          if (entrySawClosed && Boolean.FALSE.equals(s.doorsClosed)) entryOpened = true;
          if (!entryOpened || !Boolean.FALSE.equals(s.locked)) return;
          entryUntil = 0;
          try {
            startClimate(
                () ->
                    validSession(ticket, target)
                        && autoEnabled
                        && monitoring
                        && settings.getBoolean("autoReady", false)
                        && nearby.getAsBoolean(),
                s,
                true);
          } catch (Exception e) {
            climateStatus = "공조 연동 보류 · " + e.getMessage();
            diagnostics.record("CLIMATE_START_BLOCK_OR_ERROR", e.getMessage());
            throw e;
          }
        },
        () -> {},
        true);
  }

  /**
   * 주차 마무리: Stop with this tap's one-use parking confirmation, then lock only when power OFF is
   * confirmed. An unconfirmed Stop never leads to another command on its own.
   */
  void departureFinish() {
    diagnostics.record("MANUAL_PARKING_CONFIRM", "oneUse=true expiresSeconds=30 sequence=stop_lock");
    executeCommand(
        CloudClient.Command.STOP,
        false,
        () -> true,
        () -> true,
        () -> {},
        () ->
            main.post(
                () -> {
                  VehicleSnapshot s = snapshot;
                  if (s != null
                      && s.fresh(System.currentTimeMillis())
                      && Integer.valueOf(1).equals(s.power))
                    command(CloudClient.Command.LOCK, false, () -> true);
                  else note("주차 마무리 중단 · 시동 꺼짐을 확인하지 못해 잠그지 않았습니다. 차량을 확인하세요");
                }),
        () -> false,
        SystemClock.elapsedRealtime());
  }

  /** 자동화 끄기: every automatic function stops (door, Stop, boarding climate) until turned on. */
  void automationOff() {
    settings.edit().putBoolean("autoStart", false).apply();
    stop();
    note("자동화 꺼짐 · 자동 열기·잠금·종료·공조를 모두 중단했습니다");
  }

  void manualClimateStart() {
    int ticket = generation.get();
    String target = vin;
    run(
        () -> {
          try {
            startClimate(() -> validSession(ticket, target), null, false);
          } catch (Exception e) {
            climateStatus = "공조 동작 보류 · " + e.getMessage();
            diagnostics.record("CLIMATE_START_BLOCK_OR_ERROR", e.getMessage());
            throw e;
          }
        });
  }

  private void startClimate(BooleanSupplier valid, VehicleSnapshot entrySnapshot, boolean boarding)
      throws Exception {
    requireVehicle();
    if (pinHash.isEmpty()) throw new Exception("제어 PIN이 필요합니다");
    if (!CloudClient.hasClimate(capabilities)) capabilities = cloud.capabilities(vin);
    if (!CloudClient.hasClimate(capabilities)) throw new Exception("이 차량의 공조 기능 지원을 확인하지 못했습니다");
    String target = vin;
    int ticket = generation.get();
    long now = System.currentTimeMillis();
    boolean reused =
        entrySnapshot != null && entrySnapshot.fresh(now) && now - entrySnapshot.receivedAt <= 5000;
    VehicleSnapshot checked = reused ? entrySnapshot : cloud.snapshot(target);
    observeSnapshot(checked, true);
    String block = checked.climateBlock(System.currentTimeMillis());
    diagnostics.record(
        "CLIMATE_START_PREFLIGHT",
        "entrySnapshotReused=" + reused + " " + checked.diagnostic(System.currentTimeMillis()));
    if (block != null) throw new Exception("공조 동작 보류: " + block);
    AtomicBoolean sent = new AtomicBoolean();
    long[] sentAt = {0};
    note("공조 시작 요청 중 · 자동 OFF 없이 상태를 진단합니다");
    // BLE-first: 시동 켜기 starts the remote climate (START_CLIMATE) over the watch key, else the cloud.
    boolean sentByBle = false;
    Byte bleCode = watchBleCode(CloudClient.Command.CLIMATE_ON);
    if (bleCode != null
        && ble.ready()
        && valid.getAsBoolean()
        && checked.climateBlock(System.currentTimeMillis()) == null) {
      com.dautolock.app.watchkey.WatchKey.BleResult r = ble.send(bleCode, WATCH_BLE_AUTO_TIMEOUT_MS);
      if (r != null && r.success) {
        sentByBle = true;
        sentAt[0] = System.currentTimeMillis();
        sent.set(true);
        diagnostics.record(
            "CLIMATE_START_SEND",
            "command=OPENAIR targetTemperatureC=23 automaticOff=false source=ble doors=" + r.doors);
        climateStatus = "공조 시작 응답 수신(BLE) · 자동 OFF 없음 · READY 별도 확인";
        diagnostics.record("CLIMATE_START_RESULT", "ack=true physicalStateVerified=false source=ble");
        note(climateStatus);
      } else {
        diagnostics.record(
            "WATCHBLE_FALLBACK", "CLIMATE_ON reason=" + (r == null ? "unavailable" : r.message));
      }
    }
    try {
      if (!sentByBle) {
        cloud.command(
            target,
            pinHash,
            CloudClient.Command.CLIMATE_ON,
            () -> {
              if (!valid.getAsBoolean() || checked.climateBlock(System.currentTimeMillis()) != null)
                return false;
              sentAt[0] = System.currentTimeMillis();
              sent.set(true);
              diagnostics.record(
                  "CLIMATE_START_SEND",
                  "command=OPENAIR targetTemperatureC=23 automaticOff=false source=cloud");
              return true;
            });
        climateStatus = "공조 시작 응답 수신 · 자동 OFF 없음 · READY 별도 확인";
        diagnostics.record("CLIMATE_START_RESULT", "ack=true physicalStateVerified=false");
        note(climateStatus);
      }
    } catch (Exception e) {
      climateStatus =
          "공조 시작 " + (sent.get() ? "결과 미확인 · 자동 재전송·OFF 없음 · " : "보류 · ") + e.getMessage();
      diagnostics.record(
          "CLIMATE_START_RESULT", "dispatched=" + sent.get() + " ack=false automaticOff=false");
      note(climateStatus);
      throw e;
    } finally {
      if (sent.get() && validSession(ticket, target) && boarding && climateAutoOff())
        scheduleClimateOff(ticket, target);
      if (sent.get() && validSession(ticket, target)) {
        cancelReadinessWatch("새 공조 시작");
        ReadinessWatch watch = new ReadinessWatch(ticket, target, sentAt[0]);
        readinessWatch = watch;
        readyStatus = "READY 진단 대기 · 공조 시작 후 약 2분 동안 5회 조회";
        diagnostics.record(
            "READY_WATCH_START", "samples=5 offsetsSeconds=0,15,30,60,120 readOnly=true");
        main.post(watch);
      }
    }
  }

  boolean climateAutoOff() {
    return settings.getBoolean("climateAutoOff", false);
  }

  int climateOffSeconds() {
    return Math.max(5, Math.min(120, settings.getInt("climateOffSeconds", 10)));
  }

  /**
   * Boarding climate exists only to power the car on; the user does not want the A/C to keep
   * running. Turn it off once, after a delay long enough that it does not power the car down (the
   * 2 s version did; ~5 s+ was observed safe). Off-only: never re-powers or re-sends.
   */
  private void scheduleClimateOff(int ticket, String target) {
    long delay = climateOffSeconds() * 1000L;
    climateStatus = "공조 시작됨 · " + climateOffSeconds() + "초 뒤 자동 OFF 예정";
    diagnostics.record("CLIMATE_AUTO_OFF_SCHEDULED", "delayMs=" + delay);
    main.postDelayed(() -> runClimateOff(ticket, target, 0), delay);
  }

  /** The cloud worker may be busy (readiness reads); retry for a while instead of dropping it. */
  private void runClimateOff(int ticket, String target, int attempt) {
    if (!validSession(ticket, target)) return;
    boolean started =
        run(
            () -> {
              if (!validSession(ticket, target)) return;
              try {
                cloud.command(target, pinHash, CloudClient.Command.CLIMATE_OFF, () -> true);
                climateStatus = "탑승 공조 자동 OFF 요청 완료 · 차량 전원은 유지(실차 확인)";
                diagnostics.record("CLIMATE_AUTO_OFF_SENT", "boarding=true");
              } catch (Exception e) {
                climateStatus = "탑승 공조 자동 OFF 미완료 · " + e.getMessage();
                diagnostics.record("CLIMATE_AUTO_OFF_ERROR", e.getMessage());
              }
            },
            () -> {},
            true);
    if (!started && attempt < 30)
      main.postDelayed(() -> runClimateOff(ticket, target, attempt + 1), 1000);
  }

  private void cancelReadinessWatch(String reason) {
    ReadinessWatch watch = readinessWatch;
    if (watch != null) watch.finish(reason);
  }

  private final class ReadinessWatch implements Runnable {
    final int ticket;
    final String target;
    final long started = SystemClock.elapsedRealtime();
    final long[] offsets = {0, 15000, 30000, 60000, 120000};
    final ReadyObservation observation;
    int index, attempts, skipped;
    String lastState = "아직 조회한 상태 없음";
    boolean hvacUnavailable;

    ReadinessWatch(int ticket, String target, long sentAt) {
      this.ticket = ticket;
      this.target = target;
      observation = new ReadyObservation(sentAt);
    }

    boolean current() {
      return readinessWatch == this && validSession(ticket, target);
    }

    void finish(String reason) {
      if (readinessWatch != this) return;
      readinessWatch = null;
      main.removeCallbacks(this);
      readyStatus = "READY 진단 종료 · " + reason + "\n" + observation.summary() + "\n" + lastState;
      diagnostics.record(
          "READY_WATCH_END",
          "reason="
              + reason
              + " attempts="
              + attempts
              + " skipped="
              + skipped
              + " "
              + observation.diagnostic());
      changed();
    }

    @Override
    public void run() {
      if (readinessWatch != this) return;
      if (!current()) {
        finish("설정·계정 변경");
        return;
      }
      long age = SystemClock.elapsedRealtime() - started;
      if (index >= offsets.length || age > 150000) {
        finish(index >= offsets.length ? "조회 일정 종료 · 연속 유지 보장은 아님" : "조회 시간 초과·일부 미확인");
        return;
      }
      if (age < offsets[index]) {
        main.postDelayed(this, offsets[index] - age);
        return;
      }
      if (busy() || cloud.backoffMillis() > 0) {
        main.postDelayed(this, 5000);
        return;
      }
      boolean accepted =
          Controller.this.run(
              () -> {
                if (!current()) return;
                attempts++;
                VehicleSnapshot observed = null;
                String hvacLabel = "공조 상태 조회 미확인";
                try {
                  observed = cloud.snapshot(target);
                  if (!current()) return;
                  observeSnapshot(observed, true);
                  observation.accept(observed, System.currentTimeMillis());
                  diagnostics.record(
                      "READY_OBSERVATION",
                      "sample="
                          + (index + 1)
                          + " elapsedMs="
                          + (SystemClock.elapsedRealtime() - started)
                          + " "
                          + observed.diagnostic(System.currentTimeMillis())
                          + " "
                          + observation.diagnostic());
                } catch (Exception e) {
                  observation.unavailable();
                  diagnostics.record("READY_OBSERVATION_ERROR", e.getMessage());
                }
                if (!current()) return;
                if (!hvacUnavailable && cloud.backoffMillis() == 0) {
                  try {
                    HvacSnapshot hvac = cloud.hvacSnapshot(target);
                    if (!current()) return;
                    hvacLabel = hvac.label(System.currentTimeMillis());
                    learnClimateScale(hvac);
                    diagnostics.record(
                        "CLIMATE_OBSERVATION",
                        "sample="
                            + (index + 1)
                            + " "
                            + hvac.diagnostic(System.currentTimeMillis()));
                  } catch (Exception e) {
                    hvacUnavailable = true;
                    diagnostics.record(
                        "CLIMATE_OBSERVATION_ERROR", "furtherHvacQueries=false " + e.getMessage());
                  }
                }
                if (!current()) return;
                index++;
                // Slow requests skip expired slots instead of issuing several catch-up queries in a
                // burst.
                long elapsed = SystemClock.elapsedRealtime() - started;
                while (index < offsets.length && offsets[index] <= elapsed) {
                  diagnostics.record(
                      "READY_WATCH_SKIP", "slot=" + (index + 1) + " reason=delayed_previous_read");
                  observation.unavailable();
                  skipped++;
                  index++;
                }
                lastState =
                    (observed == null
                            ? "전원 미확인"
                            : "조회 전원 "
                                + observed.powerLabel()
                                + (observed.fresh(System.currentTimeMillis()) ? "" : " (오래된 값)"))
                        + " · "
                        + hvacLabel;
                readyStatus =
                    "READY 진단 " + attempts + "회 조회 · " + observation.summary() + "\n" + lastState;
              },
              () -> main.post(this),
              true);
      if (!accepted) main.postDelayed(this, 5000);
    }
  }
}
