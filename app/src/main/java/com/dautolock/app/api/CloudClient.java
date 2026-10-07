package com.dautolock.app.api;

import com.dautolock.app.core.HvacSnapshot;
import com.dautolock.app.core.VehicleSnapshot;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Call only on the controller's serial executor. Never retries a command trigger. */
public final class CloudClient {
  public enum Command {
    UNLOCK("OPENDOOR", "1006", "잠금 해제"),
    LOCK("LOCKDOOR", "1005", "도어 잠금"),
    STOP("TURNOFFENGINE", "1031", "차량 종료"),
    CLIMATE_ON("OPENAIR", "1001", "공조 ON"),
    CLIMATE_OFF("CLOSEAIR", "1001", "공조 OFF"),
    CLOSE_WINDOWS("CLOSEWINDOW", "1026", "전체 창문 닫기");
    public final String wire, feature, label;

    Command(String wire, String feature, String label) {
      this.wire = wire;
      this.feature = feature;
      this.label = label;
    }
  }

  public final CloudProtocol protocol;
  private java.util.function.BiConsumer<String, String> diagnostics = (event, detail) -> {};

  public interface SessionRecovery {
    void reconnect() throws Exception;
  }

  private SessionRecovery recovery;
  private long nextRecovery;
  private final java.util.function.LongSupplier clock;
  private volatile long retryAt;
  private int busyFailures;
  private long firstBusyAt, nextBusyRecovery;
  private boolean busyRecoveryPending;

  public CloudClient() {
    this(new CloudProtocol(BydConfig.fromRegion("KR")));
  }

  public CloudClient(CloudProtocol protocol) {
    this(protocol, () -> System.nanoTime() / 1000000);
  }

  CloudClient(CloudProtocol protocol, java.util.function.LongSupplier clock) {
    this.protocol = protocol;
    this.clock = clock;
  }

  public void setSessionRecovery(SessionRecovery recovery) {
    this.recovery = recovery;
  }

  public void ensureAuthenticated() throws Exception {
    if (!protocol.isLoggedIn()) recoverSession();
  }

  public long backoffMillis() {
    return Math.max(0, retryAt - clock.getAsLong());
  }

  private void checkBackoff() throws Exception {
    long remaining = backoffMillis();
    if (remaining > 0)
      throw new Exception("BYD 1008 응답으로 " + (remaining + 999) / 1000 + "초 대기 중 · 로그인 정보는 유지됩니다");
  }

  private void recoverBusyIfDue() throws Exception {
    if (!busyRecoveryPending || recovery == null) return;
    busyRecoveryPending = false;
    nextBusyRecovery = clock.getAsLong() + 600000;
    diagnostics.accept("ACCOUNT_RECOVERY_1008", "boundedReconnect=true commandReplay=false");
    try {
      recoverSession();
    } catch (Exception e) {
      retryAt = clock.getAsLong() + 120000;
      throw e;
    }
  }

  private Exception busyResponse() {
    long now = clock.getAsLong();
    if (busyFailures++ == 0) firstBusyAt = now;
    long delay = busyFailures == 1 ? 30000 : busyFailures == 2 ? 60000 : 120000;
    retryAt = now + delay;
    // 1008 is not assumed to mean expired credentials. Back off first; one bounded
    // reconnect addresses this vehicle's observed recovery after a fresh login.
    if (busyFailures >= 2 && now - firstBusyAt >= 30000 && now >= nextBusyRecovery)
      busyRecoveryPending = true;
    diagnostics.accept(
        "API_BACKOFF",
        "code=1008 failures="
            + busyFailures
            + " waitSeconds="
            + delay / 1000
            + " reconnectPending="
            + busyRecoveryPending);
    return new Exception(
        "BYD 요청 거부 (1008) · " + delay / 1000 + "초 후 재확인합니다. 반복되면 저장 계정으로 제한적으로 재연결합니다");
  }

  private void recoverSession() throws Exception {
    if (recovery == null) throw new Exception("Sub 계정 로그인 / 변경에서 계정을 저장하세요");
    long now = clock.getAsLong();
    if (now < nextRecovery) throw new Exception("계정 재연결 대기 중입니다. 잠시 후 다시 시도하세요. 저장 정보는 유지됩니다");
    nextRecovery = now + 60000;
    diagnostics.accept("ACCOUNT_RECONNECT", "started=true");
    try {
      recovery.reconnect();
      if (!protocol.isLoggedIn()) throw new Exception("저장된 계정으로 연결하지 못했습니다");
      diagnostics.accept("ACCOUNT_RECONNECT", "success=true");
    } catch (Exception e) {
      diagnostics.accept("ACCOUNT_RECONNECT", "success=false savedAccountRetained=true");
      throw e;
    }
  }

  public void setDiagnostics(java.util.function.BiConsumer<String, String> diagnostics) {
    this.diagnostics = diagnostics;
  }

  private interface Starter<T> {
    void start(BydApiCallback<T> cb);
  }

  private <T> T await(Starter<T> start) throws Exception {
    CompletableFuture<T> future = new CompletableFuture<>();
    start.start(
        new BydApiCallback<T>() {
          public void onSuccess(T value) {
            future.complete(value);
          }

          public void onError(String msg, Exception e) {
            future.completeExceptionally(
                e instanceof CloudProtocol.SessionExpiredException
                        || e instanceof CloudProtocol.ServiceBusyException
                    ? e
                    : new Exception(msg));
          }
        });
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
      throw new Exception("요청을 처리하지 못했습니다");
    } catch (TimeoutException e) {
      throw new Exception("응답 시간 초과. 차량 상태를 확인한 후 다시 시도하세요");
    }
  }

  public void login(String user, String password) throws Exception {
    this.<String>await(cb -> protocol.login(user, password, cb));
  }

  public JSONObject request(String endpoint, Map<String, Object> data, String vin)
      throws Exception {
    return request(endpoint, data, vin, false);
  }

  private JSONObject request(String endpoint, Map<String, Object> data, String vin, boolean cleanup)
      throws Exception {
    boolean read = isReadRequest(endpoint);
    if (!cleanup) checkBackoff();
    if (read && !cleanup) recoverBusyIfDue();
    if (read) ensureAuthenticated();
    if (!protocol.isLoggedIn()) throw new CloudProtocol.SessionExpiredException();
    try {
      return requestOnce(endpoint, freshRequest(data, vin), vin);
    } catch (CloudProtocol.SessionExpiredException expired) {
      recoverSession();
      if (!read) throw new Exception("계정 재연결 완료 · 차량 제어는 재전송하지 않았습니다. 차량 상태를 확인하세요");
      return requestOnce(endpoint, freshRequest(data, vin), vin);
    }
  }

  private static boolean isReadRequest(String endpoint) {
    return endpoint.equals("/app/account/getAllListByUserId")
        || endpoint.equals("/vehicle/vehicleswitch/getLatestConfig")
        || endpoint.equals("/vehicleInfo/vehicle/vehicleRealTimeRequest")
        || endpoint.equals("/vehicleInfo/vehicle/vehicleRealTimeResult")
        || endpoint.equals("/control/getStatusNow")
        || endpoint.equals("/control/remoteControlResult");
  }

  private Map<String, Object> freshRequest(Map<String, Object> original, String vin) {
    Map<String, Object> refreshed = new LinkedHashMap<>(original);
    refreshed.putAll(protocol.buildInnerBaseMap(vin, null));
    return refreshed;
  }

  private JSONObject requestOnce(String endpoint, Map<String, Object> data, String vin)
      throws Exception {
    long started = System.nanoTime();
    try {
      JSONObject result = await(cb -> protocol.postTokenSecure(endpoint, data, vin, cb));
      // A request serial alone is not a successful status read. A repeated 1008
      // from the result endpoint must retain its backoff history.
      if (!endpoint.equals("/vehicleInfo/vehicle/vehicleRealTimeRequest")
          && !endpoint.equals("/control/remoteControl")) clearBackoff();
      diagnostics.accept(
          "API_OK",
          "op=" + operation(endpoint) + " durationMs=" + (System.nanoTime() - started) / 1000000);
      return result;
    } catch (Exception e) {
      diagnostics.accept(
          "API_ERROR",
          "op="
              + operation(endpoint)
              + " durationMs="
              + (System.nanoTime() - started) / 1000000
              + " "
              + e.getMessage());
      if (e instanceof CloudProtocol.ServiceBusyException) throw busyResponse();
      throw e;
    }
  }

  static String operation(String endpoint) {
    switch (endpoint) {
      case "/control/getStatusNow":
        return "hvac_status";
      case "/app/account/getAllListByUserId":
        return "vehicles";
      case "/vehicle/vehicleswitch/getLatestConfig":
        return "features";
      case "/vehicleInfo/vehicle/vehicleRealTimeRequest":
        return "status_request";
      case "/vehicleInfo/vehicle/vehicleRealTimeResult":
        return "status_result";
      case "/control/remoteControl":
        return "control_send";
      case "/control/remoteControlResult":
        return "control_result";
      default:
        return "other";
    }
  }

  private void clearBackoff() {
    busyFailures = 0;
    busyRecoveryPending = false;
    retryAt = 0;
  }

  public JSONArray vehicles() throws Exception {
    JSONObject r =
        request("/app/account/getAllListByUserId", protocol.buildInnerBaseMap(null, null), null);
    JSONArray list = r.optJSONArray("list");
    if (list == null) throw new Exception("차량 목록 형식을 확인하지 못했습니다");
    return list;
  }

  public JSONObject capabilities(String vin) throws Exception {
    Map<String, Object> m = protocol.buildInnerBaseMap(null, null);
    m.put("appConfigVersion", "2");
    m.put("terminalType", "0");
    m.put("vinList", new JSONArray().put(vin).toString());
    JSONObject all = request("/vehicle/vehicleswitch/getLatestConfig", m, null);
    JSONObject selected = all.optJSONObject(vin);
    return selected == null ? new JSONObject() : selected;
  }

  public static boolean hasFeature(Object value, String id) {
    if (value instanceof JSONObject) {
      JSONObject o = (JSONObject) value;
      if (id.equals(o.optString("functionNo"))) return true;
      Iterator<String> keys = o.keys();
      while (keys.hasNext()) if (hasFeature(o.opt(keys.next()), id)) return true;
    } else if (value instanceof JSONArray) {
      JSONArray a = (JSONArray) value;
      for (int i = 0; i < a.length(); i++) if (hasFeature(a.opt(i), id)) return true;
    }
    return false;
  }

  public HvacSnapshot hvacSnapshot(String vin) throws Exception {
    JSONObject data = request("/control/getStatusNow", protocol.buildInnerBaseMap(vin, null), vin);
    return new HvacSnapshot(data, System.currentTimeMillis());
  }

  public VehicleSnapshot snapshot(String vin) throws Exception {
    Map<String, Object> m = protocol.buildInnerBaseMap(vin, null);
    m.put("energyType", "0");
    m.put("tboxVersion", "3");
    JSONObject trigger = request("/vehicleInfo/vehicle/vehicleRealTimeRequest", m, vin);
    String serial = trigger.optString("requestSerial");
    if (serial.isEmpty()) throw new Exception("차량 상태 요청 번호가 없습니다");
    VehicleSnapshot last = null;
    for (int i = 0; i < 6; i++) {
      Thread.sleep(1500);
      m = protocol.buildInnerBaseMap(vin, serial);
      m.put("energyType", "0");
      m.put("tboxVersion", "3");
      JSONObject data = request("/vehicleInfo/vehicle/vehicleRealTimeResult", m, vin);
      last = new VehicleSnapshot(data, System.currentTimeMillis());
      if (last.fresh(System.currentTimeMillis())) return last;
    }
    if (last == null) throw new Exception("차량 상태를 수신하지 못했습니다");
    return last;
  }

  /** -1 failure, 0 pending/unknown, 1 acknowledged. ACK is not physical confirmation. */
  public static int resultState(JSONObject o) {
    if (o.has("controlState")) {
      int s = o.optInt("controlState", -99);
      if (s == 1) return 1;
      if (s == 2) return -1;
      return 0;
    }
    int res = o.optInt("res", -99);
    return res == 2 ? 1 : res > 2 ? -1 : 0;
  }

  public void command(String vin, String pinHash, Command command, BooleanSupplier valid)
      throws Exception {
    boolean cleanup = command == Command.CLIMATE_OFF;
    if (!cleanup) checkBackoff();
    ensureAuthenticated();
    Map<String, Object> m = protocol.buildInnerBaseMap(vin, null);
    m.put("commandPwd", pinHash);
    m.put("commandType", command.wire);
    if (command == Command.CLIMATE_ON) m.put("controlParamsMap", climateParams().toString());
    if (!valid.getAsBoolean()) throw new Exception("설정 또는 거리 상태가 바뀌어 제어를 취소했습니다");
    JSONObject r = request("/control/remoteControl", m, vin, cleanup);
    String serial = r.optString("requestSerial");
    for (int i = 0; i < 10; i++) {
      int state = resultState(r);
      if (state == 1) {
        clearBackoff();
        return;
      }
      if (state == -1) throw new Exception("차량에서 명령을 거부했습니다. 공유 권한과 제어 PIN을 확인하세요");
      if (serial.isEmpty()) break;
      Thread.sleep(1500);
      m = protocol.buildInnerBaseMap(vin, serial);
      m.put("commandPwd", pinHash);
      m.put("commandType", command.wire);
      if (command == Command.CLIMATE_ON) m.put("controlParamsMap", climateParams().toString());
      r = request("/control/remoteControlResult", m, vin, cleanup);
    }
    throw new Exception("명령 결과 미확인. 재전송하지 않았습니다. 차량에서 직접 확인하세요");
  }

  public static JSONObject climateParams() throws Exception {
    return new JSONObject()
        .put("airSet", JSONObject.NULL)
        .put("remoteMode", 4)
        .put("timeSpan", 1)
        .put("mainSettingTemp", 13)
        .put("copilotSettingTemp", 13)
        .put("cycleMode", 1)
        .put("airAccuracy", 2)
        .put("airConditioningMode", 1);
  }

  public static boolean hasClimate(JSONObject features) {
    return hasFeature(features, "1001")
        || hasFeature(features, "10300001")
        || hasFeature(features, "1015");
  }
}
