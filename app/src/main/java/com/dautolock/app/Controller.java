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
  private final SecureStore store;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicInteger generation = new AtomicInteger();
  private final AtomicBoolean busy = new AtomicBoolean();
  volatile CloudClient cloud = new CloudClient();
  volatile JSONArray vehicles = new JSONArray();
  volatile JSONObject capabilities = new JSONObject();
  volatile String vin = "", pinHash = "", vehicleName = "차량을 연결하세요";
  volatile String permissionSummary = "공유 권한은 차량 선택 후 확인합니다";
  volatile VehicleSnapshot snapshot;
  volatile boolean monitoring = false, autoEnabled = false;
  volatile String signal = "신호 대기", message = "BYD 계정과 차량 블루투스를 연결하세요";
  private final LinkedList<String> events = new LinkedList<>();
  private final List<Runnable> observers = new CopyOnWriteArrayList<>();

  Controller(Context context) {
    this.context = context;
    settings = context.getSharedPreferences("settings", 0);
    store = new SecureStore(context);
    worker.execute(
        () -> {
          try {
            JSONObject saved = store.read();
            if (saved.has("session")) cloud.protocol.restoreSession(saved.getJSONObject("session"));
            vin = saved.optString("vin");
            pinHash = saved.optString("pinHash");
            vehicleName = saved.optString("vehicleName", "차량을 연결하세요");
            note(
                cloud.protocol.isLoggedIn()
                    ? "계정 복원 완료 · 차량 목록과 권한을 새로고침하세요"
                    : "BYD Sub 계정을 연결하세요");
          } catch (Exception e) {
            note("저장된 계정을 복원하지 못했습니다. 다시 로그인하세요");
          }
        });
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

  private void run(Work work) {
    if (!busy.compareAndSet(false, true)) {
      note("이전 요청을 처리 중입니다");
      return;
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
            changed();
          }
        });
  }

  private void save() throws Exception {
    store.save(
        new JSONObject()
            .put("session", cloud.protocol.exportSession())
            .put("vin", vin)
            .put("pinHash", pinHash)
            .put("vehicleName", vehicleName));
  }

  void login(String user, String password, String pin) {
    stop();
    run(
        () -> {
          CloudClient next = new CloudClient();
          note("한국 BYD 계정 연결 중…");
          next.login(user, password);
          cloud = next;
          vin = "";
          vehicleName = "차량을 선택하세요";
          snapshot = null;
          capabilities = new JSONObject();
          vehicles = new JSONArray();
          permissionSummary = "공유 권한은 차량 선택 후 확인합니다";
          pinHash = CryptoUtils.md5Hex(pin);
          save();
          vehicles = cloud.vehicles();
          note(
              vehicles.length() == 0
                  ? "공유된 차량이 없습니다. BYD AUTO에서 Sub 계정 공유를 승인하세요"
                  : "로그인 완료 · 차량 선택 버튼을 누르세요");
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
          cloud = new CloudClient();
          vin = "";
          pinHash = "";
          vehicleName = "차량을 연결하세요";
          snapshot = null;
          vehicles = new JSONArray();
          capabilities = new JSONObject();
          store.clear();
          note("계정과 저장된 차량 정보를 삭제했습니다");
        });
  }

  private void requireVehicle() throws Exception {
    if (vin.isEmpty() || !cloud.protocol.isLoggedIn()) throw new Exception("계정 로그인 후 차량을 선택하세요");
  }

  void command(CloudClient.Command command, boolean automatic, BooleanSupplier proximityValid) {
    int ticket = generation.get();
    String target = vin;
    BooleanSupplier valid =
        () ->
            ticket == generation.get()
                && target.equals(vin)
                && (!automatic || (monitoring && autoEnabled && proximityValid.getAsBoolean()));
    run(
        () -> {
          requireVehicle();
          if (pinHash.isEmpty()) throw new Exception("제어 PIN이 없습니다. 다시 로그인하세요");
          if (!valid.getAsBoolean()) return;
          if (!CloudClient.hasFeature(capabilities, command.feature))
            throw new Exception("이 차량의 " + command.label + " 지원을 확인하지 못했습니다. 상태를 새로고침하세요");
          note(command.label + " 전 차량 상태 확인 중…");
          snapshot = cloud.snapshot(vin);
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
            note("이미 요청한 상태입니다");
            return;
          }
          if (!valid.getAsBoolean()) {
            note("거리 또는 설정이 바뀌어 제어를 취소했습니다");
            return;
          }
          long sentAt = System.currentTimeMillis();
          cloud.command(target, pinHash, command, valid);
          VehicleSnapshot after = cloud.snapshot(target);
          snapshot = after;
          boolean verified =
              after.fresh(System.currentTimeMillis())
                  && after.measuredAt >= sentAt
                  && (stop
                      ? Integer.valueOf(1).equals(after.power)
                      : Boolean.valueOf(lock).equals(after.locked));
          note(
              verified
                  ? command.label + " 완료 · 차량 상태 확인됨"
                  : "명령 응답 수신 · 실제 차량 상태는 미확인. 차량에서 확인하세요");
        });
  }

  void stop() {
    generation.incrementAndGet();
    autoEnabled = false;
    monitoring = false;
    context.stopService(new Intent(context, ProximityService.class));
    changed();
  }

  void auto(boolean enabled) {
    generation.incrementAndGet();
    autoEnabled = enabled;
    note(enabled ? "자동 도어 제어 켜짐 · OFF·정차·최신 상태 확인 시 실행" : "관찰 모드 · 차량 명령을 보내지 않습니다");
  }

  void device(String name, String address) {
    stop();
    settings.edit().putString("deviceName", name).putString("address", address).apply();
    note("블루투스 기기 선택: " + name);
  }

  void thresholds(int near, int far) {
    new ProximityEngine(near, far);
    stop();
    settings.edit().putInt("near", near).putInt("far", far).apply();
    note("거리 기준 저장 · 다시 관찰을 시작하세요");
  }
}
