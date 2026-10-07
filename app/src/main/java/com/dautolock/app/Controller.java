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
  private final SecureStore store;
  private final Supplier<CloudClient> clients;
  volatile String loginUser = "";
  private volatile String loginPassword = "";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicInteger generation = new AtomicInteger();
  private final AtomicBoolean busy = new AtomicBoolean();
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
  volatile int signalStrength = 0;
  volatile boolean initializing = true;
  private volatile long entryUntil, nextEntryCheck;
  private int entryTicket;
  private boolean entrySawClosed, entryOpened;
  private final LinkedList<String> events = new LinkedList<>();
  private final List<Runnable> observers = new CopyOnWriteArrayList<>();

  Controller(Context context) {
    this(context, new SecureStore(context), CloudClient::new);
  }

  Controller(Context context, SecureStore store, Supplier<CloudClient> clients) {
    this.context = context;
    this.store = store;
    this.clients = clients;
    settings = context.getSharedPreferences("settings", 0);
    diagnostics = new DiagnosticLog(new java.io.File(context.getFilesDir(), "diagnostics"));
    cloud = configure(clients.get());
    diagnostics.record(
        "APP_START", "version=0.2.2 sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);
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
    events.addFirst(
        new java.text.SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()) + "  " + value);
    while (events.size() > 30) events.removeLast();
    changed();
  }

  synchronized String log() {
    return String.join("\n\n", events);
  }

  boolean busy() {
    return busy.get();
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
          snapshot = cloud.snapshot(vin);
          note(
              snapshot.fresh(System.currentTimeMillis())
                  ? "차량 상태 업데이트 완료"
                  : "최신 상태를 확인하지 못했습니다 · 자동 제어 보류");
        });
  }

  void logout() {
    stop();
    run(
        () -> {
          store.clear();
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

  void command(CloudClient.Command command, boolean automatic, BooleanSupplier proximityValid) {
    executeCommand(command, automatic, proximityValid, () -> true, () -> {}, () -> {});
  }

  boolean automaticCommand(
      CloudClient.Command command,
      BooleanSupplier valid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished) {
    return executeCommand(command, true, valid, claim, alreadyDone, finished);
  }

  private boolean executeCommand(
      CloudClient.Command command,
      boolean automatic,
      BooleanSupplier proximityValid,
      BooleanSupplier claim,
      Runnable alreadyDone,
      Runnable finished) {
    int ticket = generation.get();
    String target = vin;
    BooleanSupplier valid =
        () ->
            ticket == generation.get()
                && target.equals(vin)
                && (!automatic || (monitoring && autoEnabled && proximityValid.getAsBoolean()));
    return run(
        () -> {
          try {
            requireVehicle();
            if (pinHash.isEmpty()) throw new Exception("제어 PIN이 없습니다. 다시 로그인하세요");
            if (!valid.getAsBoolean()) return;
            if (!CloudClient.hasFeature(capabilities, command.feature) && automatic)
              capabilities = cloud.capabilities(target);
            if (!CloudClient.hasFeature(capabilities, command.feature))
              throw new Exception("이 차량의 " + command.label + " 지원을 확인하지 못했습니다. 상태를 새로고침하세요");
            note(command.label + " 전 차량 상태 확인 중…");
            lastControl = (automatic ? "자동 " : "수동 ") + command.label + " · 상태 확인 중";
            snapshot = cloud.snapshot(vin);
            diagnostics.record(
                "PREFLIGHT",
                (automatic ? "AUTO " : "MANUAL ")
                    + command
                    + " "
                    + snapshot.diagnostic(System.currentTimeMillis()));
            boolean lock = command == CloudClient.Command.LOCK,
                stop = command == CloudClient.Command.STOP;
            String block =
                automatic
                    ? snapshot.automaticBlock(lock, System.currentTimeMillis())
                    : snapshot.manualBlock(stop, System.currentTimeMillis());
            if (block != null) throw new Exception("제어 보류: " + block);
            if (lock && !Boolean.TRUE.equals(snapshot.doorsClosed))
              throw new Exception("모든 도어가 닫혔는지 확인하지 못했습니다");
            if ((stop && Integer.valueOf(1).equals(snapshot.power))
                || (!stop && Boolean.valueOf(lock).equals(snapshot.locked))) {
              if (automatic && valid.getAsBoolean()) alreadyDone.run();
              if (automatic
                  && command == CloudClient.Command.UNLOCK
                  && validSession(ticket, target)) armEntry(ticket, snapshot);
              lastControl = command.label + " · 이미 요청한 상태";
              note("이미 요청한 상태입니다");
              if (lock)
                closeWindowsAfterLock(
                    () ->
                        validSession(ticket, target)
                            && (!automatic || (monitoring && autoEnabled)));
              return;
            }
            if (!valid.getAsBoolean()) {
              note("거리 또는 설정이 바뀌어 제어를 취소했습니다");
              return;
            }
            long sentAt = System.currentTimeMillis();
            boolean wasClosed = Boolean.TRUE.equals(snapshot.doorsClosed);
            cloud.command(
                target,
                pinHash,
                command,
                () -> {
                  if (!valid.getAsBoolean() || (automatic && !claim.getAsBoolean())) return false;
                  diagnostics.record("CONTROL_SEND", (automatic ? "AUTO " : "MANUAL ") + command);
                  lastControl = command.label + " · 전송 중";
                  changed();
                  return true;
                });
            VehicleSnapshot after = cloud.snapshot(target);
            snapshot = after;
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
            if (lock && verified)
              closeWindowsAfterLock(
                  () ->
                      validSession(ticket, target) && (!automatic || (monitoring && autoEnabled)));
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
            diagnostics.record(
                "CONTROL_BLOCK_OR_ERROR", (automatic ? "AUTO " : "MANUAL ") + lastControl);
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
            + settings.getInt("near", -65)
            + " far="
            + settings.getInt("far", -80));
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
    generation.incrementAndGet();
    autoEnabled = false;
    entryUntil = 0;
    monitoring = false;
    context.stopService(new Intent(context, ProximityService.class));
    changed();
  }

  void auto(boolean enabled) {
    if (enabled && autoUnavailable() != null) {
      note(autoUnavailable());
      return;
    }
    generation.incrementAndGet();
    autoEnabled = enabled;
    entryUntil = 0;
    note(
        enabled
            ? "자동 도어 켜짐 · 접근 시 해제 / 이탈·신호 " + settings.getInt("lossLockSeconds", 10) + "초 끊김 시 잠금"
            : "관찰 모드 · 차량 명령을 보내지 않습니다");
  }

  void device(String name, String address) {
    stop();
    settings.edit().putString("deviceName", name).putString("address", address).apply();
    note("블루투스 기기 선택 완료 · 관찰에서 수신 여부를 확인하세요");
  }

  ProximityEngine proximityEngine() {
    return new ProximityEngine(
        settings.getInt("near", -65),
        settings.getInt("far", -80),
        settings.getInt("nearWaitSeconds", 3) * 1000L,
        settings.getInt("farWaitSeconds", 8) * 1000L,
        settings.getInt("lossLockSeconds", 10) * 1000L);
  }

  void thresholds(int near, int far, int nearSeconds, int farSeconds, int lossSeconds) {
    new ProximityEngine(near, far, nearSeconds * 1000L, farSeconds * 1000L, lossSeconds * 1000L);
    stop();
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
    note("감도·대기 시간 저장 · 거리 관찰과 자동 제어를 다시 켜세요");
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

  private void closeWindowsAfterLock(BooleanSupplier valid) {
    if (!settings.getBoolean("closeWindows", true)) return;
    try {
      if (!valid.getAsBoolean()) return;
      if (!CloudClient.hasFeature(capabilities, CloudClient.Command.CLOSE_WINDOWS.feature))
        throw new Exception("차량의 창문 닫기 지원 미확인");
      if (snapshot == null
          || !snapshot.fresh(System.currentTimeMillis())
          || snapshot.speed == null
          || snapshot.speed != 0d
          || !Boolean.TRUE.equals(snapshot.locked)) throw new Exception("최신 정차·잠금 상태 미확인");
      if (Boolean.TRUE.equals(snapshot.windowsClosed)) {
        lastControl = "도어 잠김 · 전체 창문 닫힘 확인";
        note(lastControl);
        return;
      }
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
      snapshot = cloud.snapshot(vin);
      diagnostics.record("WINDOWS_READBACK", snapshot.diagnostic(System.currentTimeMillis()));
      boolean confirmed =
          snapshot.fresh(System.currentTimeMillis())
              && snapshot.measuredAt >= sent
              && Boolean.TRUE.equals(snapshot.windowsClosed);
      lastControl = confirmed ? "도어 잠금 + 전체 창문 닫힘 확인" : "도어 잠김 · 창문 닫기 응답 수신, 실제 창문 상태 미확인";
      note(lastControl);
    } catch (Exception e) {
      lastControl = "도어 잠김 · 창문 닫기 미완료: " + e.getMessage();
      note(lastControl);
      diagnostics.record("WINDOWS_ERROR", e.getMessage());
    }
  }

  void readyOption(boolean enabled) {
    settings.edit().putBoolean("autoReady", enabled).apply();
    entryUntil = 0;
    note(enabled ? "문 열림 시 공조 2초 동작 켜짐 · 자동 해제 후 문 열림을 기다립니다" : "문 열림 공조 동작 꺼짐");
  }

  private void armEntry(int ticket, VehicleSnapshot s) {
    if (!settings.getBoolean("autoReady", false)) return;
    entryTicket = ticket;
    entrySawClosed = Boolean.TRUE.equals(s.doorsClosed);
    entryOpened = false;
    entryUntil = SystemClock.elapsedRealtime() + 90000;
    nextEntryCheck = 0;
    diagnostics.record("ENTRY_WATCH", "armed=true closed=" + entrySawClosed + " expiresSeconds=90");
  }

  boolean pollEntry(BooleanSupplier nearby) {
    long now = SystemClock.elapsedRealtime();
    if (entryUntil == 0
        || now >= entryUntil
        || now < nextEntryCheck
        || !autoEnabled
        || !monitoring
        || !settings.getBoolean("autoReady", false)
        || !nearby.getAsBoolean()) return false;
    String target = vin;
    int ticket = entryTicket;
    return run(
        () -> {
          nextEntryCheck = SystemClock.elapsedRealtime() + 10000;
          if (!validSession(ticket, target) || !nearby.getAsBoolean()) return;
          VehicleSnapshot s = cloud.snapshot(target);
          snapshot = s;
          diagnostics.record("ENTRY_STATE", s.diagnostic(System.currentTimeMillis()));
          if (!s.fresh(System.currentTimeMillis())) return;
          if (Boolean.TRUE.equals(s.doorsClosed)) entrySawClosed = true;
          if (entrySawClosed && Boolean.FALSE.equals(s.doorsClosed)) entryOpened = true;
          if (!entryOpened || !Boolean.FALSE.equals(s.locked)) return;
          entryUntil = 0;
          pulse(
              () ->
                  validSession(ticket, target)
                      && autoEnabled
                      && monitoring
                      && settings.getBoolean("autoReady", false)
                      && nearby.getAsBoolean());
        },
        () -> {},
        true);
  }

  void manualPulse() {
    int ticket = generation.get();
    String target = vin;
    run(() -> pulse(() -> validSession(ticket, target)));
  }

  private void pulse(BooleanSupplier valid) throws Exception {
    requireVehicle();
    if (pinHash.isEmpty()) throw new Exception("제어 PIN이 필요합니다");
    if (!CloudClient.hasClimate(capabilities)) capabilities = cloud.capabilities(vin);
    if (!CloudClient.hasClimate(capabilities)) throw new Exception("이 차량의 공조 기능 지원을 확인하지 못했습니다");
    snapshot = cloud.snapshot(vin);
    String block = snapshot.climateBlock(System.currentTimeMillis());
    diagnostics.record("READY_PREFLIGHT", snapshot.diagnostic(System.currentTimeMillis()));
    if (block != null) throw new Exception("공조 동작 보류: " + block);
    diagnostics.record(
        "READY_GUARD",
        snapshot.epb == null
            ? "basis=power_off_and_stationary epb=unknown"
            : "basis=parking_brake_and_stationary");
    if (snapshot.epb == null) note("주차브레이크 정보 미제공 · 전원 OFF·정차 확인 후 공조 요청");
    final CloudClient client = cloud;
    final String target = vin, code = pinHash;
    note("공조 ON → 응답 확인 후 2초 대기 → OFF 진행 중");
    try {
      ClimatePulse.run(
          dispatched ->
              client.command(
                  target,
                  code,
                  CloudClient.Command.CLIMATE_ON,
                  () -> {
                    if (!valid.getAsBoolean()) return false;
                    dispatched.run();
                    diagnostics.record("READY_ON_SEND", "targetTemperatureC=23");
                    return true;
                  }),
          () ->
              client.command(
                  target,
                  code,
                  CloudClient.Command.CLIMATE_OFF,
                  () -> {
                    diagnostics.record("READY_OFF_SEND", "cleanup=true");
                    return true;
                  }),
          milliseconds -> {
            diagnostics.record("READY_DELAY", "milliseconds=" + milliseconds);
            Thread.sleep(milliseconds);
          });
      lastControl = "공조 2초 ON/OFF 응답 완료 · READY/OK는 계기판에서 확인";
      note(lastControl);
    } catch (Exception e) {
      lastControl = "공조 2초 동작: " + e.getMessage();
      note(lastControl);
      throw e;
    }
  }
}
