package com.dautolock.app.api;

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

  public CloudClient() {
    this(new CloudProtocol(BydConfig.fromRegion("KR")));
  }

  public CloudClient(CloudProtocol protocol) {
    this.protocol = protocol;
  }

  public void setSessionRecovery(SessionRecovery recovery) {
    this.recovery = recovery;
  }

  public void ensureAuthenticated() throws Exception {
    if (!protocol.isLoggedIn()) recoverSession();
  }

  private void recoverSession() throws Exception {
    if (recovery == null) throw new Exception("Sub 계정 로그인 / 변경에서 계정을 저장하세요");
    long now = System.nanoTime();
    if (now < nextRecovery) throw new Exception("계정 재연결 대기 중입니다. 잠시 후 다시 시도하세요. 저장 정보는 유지됩니다");
    nextRecovery = now + TimeUnit.SECONDS.toNanos(60);
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
                e instanceof CloudProtocol.SessionExpiredException ? e : new Exception(msg));
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
    boolean read = isReadRequest(endpoint);
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
      diagnostics.accept(
          "API_OK", endpoint + " durationMs=" + (System.nanoTime() - started) / 1000000);
      return result;
    } catch (Exception e) {
      diagnostics.accept(
          "API_ERROR",
          endpoint
              + " durationMs="
              + (System.nanoTime() - started) / 1000000
              + " "
              + e.getMessage());
      throw e;
    }
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
    ensureAuthenticated();
    Map<String, Object> m = protocol.buildInnerBaseMap(vin, null);
    m.put("commandPwd", pinHash);
    m.put("commandType", command.wire);
    if (command == Command.CLIMATE_ON) m.put("controlParamsMap", climateParams().toString());
    if (!valid.getAsBoolean()) throw new Exception("설정 또는 거리 상태가 바뀌어 제어를 취소했습니다");
    JSONObject r = request("/control/remoteControl", m, vin);
    String serial = r.optString("requestSerial");
    for (int i = 0; i < 10; i++) {
      int state = resultState(r);
      if (state == 1) return;
      if (state == -1) throw new Exception("차량에서 명령을 거부했습니다. 공유 권한과 제어 PIN을 확인하세요");
      if (serial.isEmpty()) break;
      Thread.sleep(1500);
      m = protocol.buildInnerBaseMap(vin, serial);
      m.put("commandPwd", pinHash);
      m.put("commandType", command.wire);
      if (command == Command.CLIMATE_ON) m.put("controlParamsMap", climateParams().toString());
      r = request("/control/remoteControlResult", m, vin);
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
