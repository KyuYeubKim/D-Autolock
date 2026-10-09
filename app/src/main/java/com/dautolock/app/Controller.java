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
  static final int DEFAULT_NEAR = -75, DEFAULT_FAR = -85;
  static final int DEFAULT_NEAR_WAIT = 1, DEFAULT_FAR_WAIT = 5, DEFAULT_LOSS = 10;
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
    securityAlert = text;
    long now = System.currentTimeMillis();
    diagnostics.record("SECURITY_ALERT", "kind=" + kind);
    if (!text.equals(securityAlertLast) || now - securityAlertAt >= SECURITY_RENOTIFY_MS) {
      securityAlertAt = now;
      securityAlertLast = text;
      DoorNotifications.securityAlert(context, text);
    }
    changed();
  }

  void clearSecurityAlert(String reason) {
    if (securityAlert == null) return;
    securityAlert = null;
    securityAlertLast = "";
    diagnostics.record("SECURITY_ALERT_CLEAR", "reason=" + reason);
    DoorNotifications.cancelSecurityAlert(context);
    changed();
  }

  /** Automatic lock refused while the car stands still: tell the user to secure it manually. */
  private void lockFailedAlert(String reason) {
    VehicleSnapshot s = snapshot;
    if (s == null
        || !s.fresh(System.currentTimeMillis())
        || s.speed == null
        || s.speed != 0d) return; // Moving or unknown: the phone is most likely inside.
    boolean on = Integer.valueOf(3).equals(s.power);
    if (Boolean.TRUE.equals(s.locked) && !on) return;
    raiseSecurityAlert(
        "lock_failed",
        (Boolean.TRUE.equals(s.locked) ? "도어는 잠겨 있지만 " : "도어가 자동으로 잠기지 않았습니다. ")
            + (on ? "시동이 켜져 있습니다. " : "")
            + "직접 잠그고 시동을 끄거나 아래 버튼을 누르세요. 사유: "
            + reason);
  }

  /** Consecutive automatic unlock checks refused because the car was powered ON or moving. */
  volatile int unlockInUseBlocks;

  /** Re-check delay after an unretired automatic action: 15 s, or backoff up to 5 min in use. */
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
    diagnostics.record(
        "APP_START", "version=0.3.9 sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);
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
          if (!same) settings.edit().remove("address").remove("deviceName").apply();
          if (!same) vehicleLink.forget();
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
            settings.edit().remove("address").remove("deviceName").apply();
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

  private void readStatus(String source) {
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
            throw e;
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
          settings.edit().remove("address").remove("deviceName").apply();
          note("저장된 ID·비밀번호·PIN과 차량 연결 정보를 삭제했습니다");
        });
  }

  private void requireVehicle() throws Exception {
    if (vin.isEmpty()) throw new Exception("계정 로그인 후 차량을 선택하세요");
    cloud.ensureAuthenticated();
  }

  static final long RECENT_POWER_MS = 5 * 60 * 1000;
  private volatile long powerOnSeenAt;

  /** Power ON was observed in the last 5 minutes: an opening door now is someone leaving. */
  boolean recentlyPowered() {
    long at = powerOnSeenAt;
    return at > 0 && System.currentTimeMillis() - at < RECENT_POWER_MS;
  }

  private void observeSnapshot(VehicleSnapshot next, boolean announce) {
    unlockPreflight.observed();
    VehicleSnapshot previous = snapshot;
    snapshot = next;
    if (Integer.valueOf(3).equals(next.power) && next.fresh(System.currentTimeMillis()))
      powerOnSeenAt = System.currentTimeMillis();
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
          context, next.locked ? "도어 잠김 확인" : "도어 잠금 해제 확인", "BYD Cloud 조회에서 상태 변경을 확인했습니다");
    }
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
        command, true, valid, claim, alreadyDone, finished, departureConfirmed, -1, readyAt);
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
        SystemClock.elapsedRealtime());
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
      long readyAt) {
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
            // Automatic unlock blocked because the car is powered or moving: the service backs off.
            if (automatic && command == CloudClient.Command.UNLOCK)
              unlockInUseBlocks =
                  block != null
                          && (Integer.valueOf(3).equals(checked.power)
                              || (checked.speed != null && checked.speed > 0))
                      ? unlockInUseBlocks + 1
                      : 0;
            if (block != null) throw new Exception("제어 보류: " + block);
            if ((stop && Integer.valueOf(1).equals(snapshot.power))
                || (!stop && Boolean.valueOf(lock).equals(snapshot.locked))) {
              if (automatic && valid.getAsBoolean()) alreadyDone.run();
              // Arriving together: another phone opened first while this phone also qualified
              // (automatic unlock is only evaluated with power OFF). Still needs this phone's
              // Bridge to see the drive, live P and the delayed re-check before any Stop.
              // Already unlocked with power OFF right after the car was ON = the occupant just
              // switched off and is getting out, not arriving (real log: the exit door opening
              // was taken as boarding, climate start powered the car back on).
              if (command == CloudClient.Command.UNLOCK) {
                if (recentlyPowered()) {
                  diagnostics.record("ENTRY_SKIP", "reason=recent_power_on alreadyUnlocked=true");
                } else if (Integer.valueOf(1).equals(snapshot.power)
                    && validSession(ticket, target))
                  startTrip(automatic ? "approach_already_unlocked" : "manual_already_unlocked");
                // The boarding watch follows only this phone's own unlock command.
                if (automatic && settings.getBoolean("autoReady", false))
                  climateStatus = "공조 연동 · 이미 열린 차량이라 탑승 감시 안 함";
              }
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
            cloud.command(
                target,
                pinHash,
                command,
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
                  if (automatic && !claim.getAsBoolean()) return false;
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
                        "STOP_GUARD", "basis=one_use_manual_parking_confirmation epb=unavailable");
                  diagnostics.record("CONTROL_SEND", (automatic ? "AUTO " : "MANUAL ") + command);
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
            VehicleSnapshot after = cloud.snapshot(target);
            observeSnapshot(after, false);
            diagnostics.record(
                "READBACK", command + " " + after.diagnostic(System.currentTimeMillis()));
            boolean verified =
                after.fresh(System.currentTimeMillis())
                    && after.measuredAt >= sentAt
                    && (stop
                        ? Integer.valueOf(1).equals(after.power)
                        : Boolean.valueOf(lock).equals(after.locked));
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
              startTrip(automatic ? "auto_unlock" : "manual_unlock");
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
              lockFailedAlert(e.getMessage() == null ? "알 수 없음" : e.getMessage());
            if (!automatic || dispatched.get())
              DoorNotifications.result(context, command.label + " 미완료", lastControl);
            throw e;
          }
        },
        finished,
        automatic);
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

  ProximityEngine proximityEngine() {
    return new ProximityEngine(
        settings.getInt("near", Controller.DEFAULT_NEAR),
        settings.getInt("far", Controller.DEFAULT_FAR),
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
          try (java.io.OutputStream out =
              context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new java.io.IOException("output");
            out.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            note("진단 로그를 저장했습니다");
          } catch (Exception e) {
            note("진단 로그 내보내기 실패: " + e.getClass().getSimpleName());
          }
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
    else if (automatic && s != null && Integer.valueOf(3).equals(s.power))
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
    boolean moving =
        s.quality == 0
            && s.gear != com.dautolock.link.LinkProtocol.UNKNOWN
            && s.gear != com.dautolock.link.LinkProtocol.P;
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
      if (left != null && Integer.valueOf(3).equals(left.power))
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
                s);
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
   * 출차 준비: door unlock, then one parked remote climate start (which powers the car; this is not
   * driving READY). The climate step runs only after the unlock is confirmed by a fresh readback.
   */
  void departurePrepare() {
    executeCommand(
        CloudClient.Command.UNLOCK,
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
                      && Boolean.FALSE.equals(s.locked)) manualClimateStart();
                  else note("출차 준비 중단 · 도어 열림을 확인하지 못해 시동을 켜지 않았습니다");
                }),
        () -> false,
        -1);
  }

  /**
   * 하차 마무리: Stop with this tap's one-use parking confirmation, then lock only when power OFF is
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
                  else note("하차 마무리 중단 · 시동 꺼짐을 확인하지 못해 잠그지 않았습니다. 차량을 확인하세요");
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
            startClimate(() -> validSession(ticket, target), null);
          } catch (Exception e) {
            climateStatus = "공조 동작 보류 · " + e.getMessage();
            diagnostics.record("CLIMATE_START_BLOCK_OR_ERROR", e.getMessage());
            throw e;
          }
        });
  }

  private void startClimate(BooleanSupplier valid, VehicleSnapshot entrySnapshot) throws Exception {
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
    try {
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
                "CLIMATE_START_SEND", "command=OPENAIR targetTemperatureC=23 automaticOff=false");
            return true;
          });
      climateStatus = "공조 시작 응답 수신 · 자동 OFF 없음 · READY 별도 확인";
      diagnostics.record("CLIMATE_START_RESULT", "ack=true physicalStateVerified=false");
      note(climateStatus);
    } catch (Exception e) {
      climateStatus =
          "공조 시작 " + (sent.get() ? "결과 미확인 · 자동 재전송·OFF 없음 · " : "보류 · ") + e.getMessage();
      diagnostics.record(
          "CLIMATE_START_RESULT", "dispatched=" + sent.get() + " ack=false automaticOff=false");
      note(climateStatus);
      throw e;
    } finally {
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
