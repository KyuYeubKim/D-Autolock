package com.dautolock.app.api;

import com.dautolock.app.api.crypto.BangcleCodec;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** BYD 차량 제어 및 상태 조회를 담당하는 핵심 서비스 클래스입니다. */
public class CloudProtocol {

  private final OkHttpClient httpClient;
  private final BangcleCodec bangcleCodec;
  private final BydConfig config;

  private String userId;
  private String signToken;
  private String encryToken;

  private final Map<String, String> deviceProfile = new HashMap<>();
  private boolean debug = false;

  public CloudProtocol(BydConfig config) {
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build();
    this.bangcleCodec = new BangcleCodec();
    this.config = config;

    // 기기 정보 설정 (Python pyBYD 기본값과 일치시킴)
    deviceProfile.put("ostype", "and");
    deviceProfile.put("imei", "BANGCLE01234");
    deviceProfile.put("mac", "00:00:00:00:00:00");
    deviceProfile.put("model", "POCO F1");
    deviceProfile.put("sdk", "35");
    deviceProfile.put("mod", "Xiaomi");
    deviceProfile.put("imeiMD5", "00000000000000000000000000000000"); // login 시 username의 MD5로 쓰이는듯
    deviceProfile.put("mobileBrand", "XIAOMI");
    deviceProfile.put("mobileModel", "POCO F1");
    deviceProfile.put("deviceType", "0");
    deviceProfile.put("networkType", "wifi");
    deviceProfile.put("osType", "15");
    deviceProfile.put("osVersion", "35");
    deviceProfile.put("appInnerVersion", "322");
    deviceProfile.put("appVersion", "3.2.2");
  }

  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  private void logDebug(String tag, String message) {
    /* Never log credentials or vehicle data. */
  }

  public interface SessionListener {
    void onSessionUpdated(String userId, String signToken, String encryToken);

    void onSessionExpired();
  }

  private SessionListener sessionListener;

  public void setSessionListener(SessionListener listener) {
    this.sessionListener = listener;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getSignToken() {
    return signToken;
  }

  public void setSignToken(String signToken) {
    this.signToken = signToken;
  }

  public String getEncryToken() {
    return encryToken;
  }

  public void setEncryToken(String encryToken) {
    this.encryToken = encryToken;
  }

  /** Map의 순서가 보장된 컴팩트한 JSON 문자열로 변환합니다. (checkcode 계산용) */
  private String toSortedJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) sb.append(",");
      sb.append(JSONObject.quote(entry.getKey())).append(":");
      Object val = entry.getValue();
      if (val == null) {
        sb.append("null");
      } else if (val instanceof String) {
        sb.append(JSONObject.quote((String) val));
      } else {
        sb.append(val);
      }
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  public boolean isLoggedIn() {
    return signToken != null && !signToken.isEmpty();
  }

  public boolean matchesLogin(String user) {
    return CryptoUtils.md5Hex(user).equalsIgnoreCase(deviceProfile.get("imeiMD5"));
  }

  public static final class SessionExpiredException extends Exception {
    public SessionExpiredException() {
      super("BYD 로그인 세션이 만료되었습니다");
    }
  }

  public Map<String, Object> buildInnerBaseMap(String vin, String requestSerial) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("deviceType", deviceProfile.get("deviceType"));
    map.put("imeiMD5", deviceProfile.get("imeiMD5"));
    map.put("networkType", deviceProfile.get("networkType"));
    map.put(
        "random",
        CryptoUtils.md5Hex(String.valueOf(Math.random()))
            .toUpperCase(java.util.Locale.ROOT)
            .substring(0, 16));
    map.put("timeStamp", String.valueOf(System.currentTimeMillis()));
    map.put("version", deviceProfile.get("appInnerVersion"));
    if (vin != null) map.put("vin", vin);
    if (requestSerial != null) map.put("requestSerial", requestSerial);
    return map;
  }

  public void postTokenSecure(
      String endpoint,
      Map<String, Object> innerMap,
      String vin,
      BydApiCallback<JSONObject> callback) {
    try {
      long nowMs = System.currentTimeMillis();
      String reqTimestamp = String.valueOf(nowMs);
      String innerJson = toSortedJson(innerMap);
      String encryData = CryptoUtils.aesEncryptHex(innerJson, CryptoUtils.md5Hex(encryToken));

      Map<String, String> signFields = new HashMap<>();
      for (String key : innerMap.keySet()) signFields.put(key, innerMap.get(key).toString());
      signFields.put("countryCode", config.getCountryCode());
      signFields.put("identifier", userId);
      signFields.put("imeiMD5", deviceProfile.get("imeiMD5"));
      signFields.put("language", config.getLanguage());
      signFields.put("reqTimestamp", reqTimestamp);

      String sign =
          CryptoUtils.sha1Mixed(
              CryptoUtils.buildSignString(signFields, CryptoUtils.md5Hex(signToken)));

      Map<String, Object> outerMap = new LinkedHashMap<>();
      outerMap.put("countryCode", config.getCountryCode());
      outerMap.put("encryData", encryData);
      outerMap.put("identifier", userId);
      outerMap.put("imeiMD5", deviceProfile.get("imeiMD5"));
      outerMap.put("language", config.getLanguage());
      outerMap.put("reqTimestamp", reqTimestamp);
      outerMap.put("sign", sign);
      outerMap.put("ostype", deviceProfile.get("ostype"));
      outerMap.put("imei", deviceProfile.get("imei"));
      outerMap.put("mac", deviceProfile.get("mac"));
      outerMap.put("model", deviceProfile.get("model"));
      outerMap.put("sdk", deviceProfile.get("sdk"));
      outerMap.put("mod", deviceProfile.get("mod"));
      outerMap.put("serviceTime", reqTimestamp);

      String outerJsonNoCheck = toSortedJson(outerMap);
      String checkcode = CryptoUtils.computeCheckcode(outerJsonNoCheck); // 기존 인증 유지
      outerMap.put("checkcode", checkcode);
      String finalOuterJson = toSortedJson(outerMap);

      String encodedRequest = bangcleCodec.encodeEnvelope(finalOuterJson);
      JSONObject bangcleWrap = new JSONObject();
      bangcleWrap.put("request", encodedRequest);

      RequestBody body =
          RequestBody.create(
              bangcleWrap.toString(), MediaType.parse("application/json; charset=utf-8"));
      Request request =
          new Request.Builder().url(config.getBaseUrl() + endpoint).post(body).build();

      httpClient
          .newCall(request)
          .enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                  callback.onError("네트워크 오류", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                  try (Response closeable = response) {
                    if (!response.isSuccessful()) {
                      callback.onError("서버 연결 실패 (HTTP " + response.code() + ")", null);
                      return;
                    }
                    String rawResp = response.body().string();
                    JSONObject bodyJson = new JSONObject(rawResp);
                    String encodedResponse = bodyJson.getString("response");
                    String decodedText = bangcleCodec.decodeEnvelope(encodedResponse).trim();
                    if (decodedText.startsWith("F{") || decodedText.startsWith("F["))
                      decodedText = decodedText.substring(1);
                    logDebug("RESP_DECODED", decodedText);

                    JSONObject outerResp = new JSONObject(decodedText);
                    String resCode = outerResp.optString("code", "unknown");
                    if (!"0".equals(resCode)) {
                      if ("1002".equals(resCode)
                          || "1005".equals(resCode)
                          || "1010".equals(resCode)) {
                        expireSession(callback);
                        return;
                      }
                      callback.onError("BYD 요청 거부 (코드 " + resCode + "). 잠시 후 다시 시도하세요", null);
                      return;
                    }

                    String respondData = outerResp.optString("respondData");
                    if (respondData.isEmpty()) {
                      callback.onSuccess(outerResp);
                    } else {
                      String innerText =
                          CryptoUtils.aesDecryptUtf8(respondData, CryptoUtils.md5Hex(encryToken));
                      logDebug("RESP_INNER", innerText);
                      if (innerText.startsWith("[")) {
                        JSONObject wrapper = new JSONObject();
                        wrapper.put("list", new JSONArray(innerText));
                        callback.onSuccess(wrapper);
                      } else {
                        callback.onSuccess(new JSONObject(innerText));
                      }
                    }
                  } catch (Exception e) {
                    callback.onError("응답 처리 중 예외", e);
                  }
                }
              });
    } catch (Exception e) {
      callback.onError("요청 생성 오류", e);
    }
  }

  private void expireSession(BydApiCallback<JSONObject> callback) {
    signToken = null;
    encryToken = null;
    if (sessionListener != null) sessionListener.onSessionExpired();
    SessionExpiredException error = new SessionExpiredException();
    callback.onError(error.getMessage(), error);
  }

  public void login(String username, String password, BydApiCallback<String> callback) {
    try {
      // imeiMD5를 username의 MD5 해시로 동적 설정 (pyBYD 기본 동작)
      String derivedImeiMD5 = CryptoUtils.md5Hex(username);
      deviceProfile.put("imeiMD5", derivedImeiMD5);

      long nowMs = System.currentTimeMillis();
      String reqTimestamp = String.valueOf(nowMs);
      String randomHex =
          CryptoUtils.md5Hex(String.valueOf(Math.random()))
              .toUpperCase(java.util.Locale.ROOT)
              .substring(0, 32);

      Map<String, Object> innerMap = new LinkedHashMap<>();
      innerMap.put("agreeStatus", "0");
      innerMap.put("agreementType", "[1,2]");
      innerMap.put("appInnerVersion", deviceProfile.get("appInnerVersion"));
      innerMap.put("appVersion", deviceProfile.get("appVersion"));
      innerMap.put(
          "deviceName", deviceProfile.get("mobileBrand") + " " + deviceProfile.get("mobileModel"));
      innerMap.put("deviceType", deviceProfile.get("deviceType"));
      innerMap.put("imeiMD5", deviceProfile.get("imeiMD5"));
      innerMap.put("isAuto", "0");
      innerMap.put("mobileBrand", deviceProfile.get("mobileBrand"));
      innerMap.put("mobileModel", deviceProfile.get("mobileModel"));
      innerMap.put("networkType", deviceProfile.get("networkType"));
      innerMap.put("osType", deviceProfile.get("osType"));
      innerMap.put("osVersion", deviceProfile.get("osVersion"));
      innerMap.put("random", randomHex);
      innerMap.put("softType", "1");
      innerMap.put("timeStamp", reqTimestamp);
      innerMap.put("timeZone", config.getTimeZone());

      String innerJson = toSortedJson(innerMap);
      String loginKey = CryptoUtils.pwdLoginKey(password);
      String encryData = CryptoUtils.aesEncryptHex(innerJson, loginKey);

      Map<String, String> signFields = new HashMap<>();
      for (String key : innerMap.keySet()) signFields.put(key, innerMap.get(key).toString());
      signFields.put("appName", "pyBYD+0.1.dev2+ge0a1f5e27");
      signFields.put("countryCode", config.getCountryCode());
      signFields.put("functionType", "pwdLogin");
      signFields.put("identifier", username);
      signFields.put("identifierType", "0");
      signFields.put("language", config.getLanguage());
      signFields.put("reqTimestamp", reqTimestamp);

      String sign =
          CryptoUtils.sha1Mixed(
              CryptoUtils.buildSignString(signFields, CryptoUtils.md5Hex(password)));

      Map<String, Object> outerMap = new LinkedHashMap<>();
      outerMap.put("appName", "pyBYD+0.1.dev2+ge0a1f5e27");
      outerMap.put("countryCode", config.getCountryCode());
      outerMap.put("encryData", encryData);
      outerMap.put("functionType", "pwdLogin");
      outerMap.put("identifier", username);
      outerMap.put("identifierType", "0");
      outerMap.put("imeiMD5", deviceProfile.get("imeiMD5"));
      outerMap.put("isAuto", "0");
      outerMap.put("language", config.getLanguage());
      outerMap.put("reqTimestamp", reqTimestamp);
      outerMap.put("sign", sign);
      outerMap.put("signKey", password);
      outerMap.put("ostype", deviceProfile.get("ostype"));
      outerMap.put("imei", deviceProfile.get("imei"));
      outerMap.put("mac", deviceProfile.get("mac"));
      outerMap.put("model", deviceProfile.get("model"));
      outerMap.put("sdk", deviceProfile.get("sdk"));
      outerMap.put("mod", deviceProfile.get("mod"));
      outerMap.put("serviceTime", reqTimestamp);

      String outerJsonNoCheck = toSortedJson(outerMap);
      String checkcode = CryptoUtils.computeCheckcode(outerJsonNoCheck);
      outerMap.put("checkcode", checkcode);
      String finalOuterJson = toSortedJson(outerMap);

      String encodedRequest = bangcleCodec.encodeEnvelope(finalOuterJson);
      JSONObject bangcleWrap = new JSONObject();
      bangcleWrap.put("request", encodedRequest);

      RequestBody body =
          RequestBody.create(
              bangcleWrap.toString(), MediaType.parse("application/json; charset=utf-8"));
      Request request =
          new Request.Builder().url(config.getBaseUrl() + "/app/account/login").post(body).build();

      httpClient
          .newCall(request)
          .enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                  callback.onError("로그인 네트워크 오류", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                  try (Response closeable = response) {
                    if (!response.isSuccessful()) {
                      callback.onError("로그인 연결 실패 (HTTP " + response.code() + ")", null);
                      return;
                    }
                    JSONObject bodyJson = new JSONObject(response.body().string());
                    String decoded =
                        bangcleCodec.decodeEnvelope(bodyJson.getString("response")).trim();
                    if (decoded.startsWith("F{")) decoded = decoded.substring(1);
                    logDebug("LOGIN_RESP_DECODED", decoded);

                    JSONObject outerResp = new JSONObject(decoded);
                    String resCode = outerResp.optString("code", "unknown");
                    if (!"0".equals(resCode)) {
                      callback.onError("로그인 실패 (코드 " + resCode + "). 한국 계정 정보와 공유 승인을 확인하세요", null);
                      return;
                    }

                    String innerText =
                        CryptoUtils.aesDecryptUtf8(outerResp.getString("respondData"), loginKey);
                    logDebug("LOGIN_RESP_INNER", innerText);

                    JSONObject innerResp = new JSONObject(innerText);
                    JSONObject token = innerResp.getJSONObject("token");

                    userId = token.getString("userId");
                    signToken = token.getString("signToken");
                    encryToken = token.getString("encryToken");

                    if (sessionListener != null) {
                      sessionListener.onSessionUpdated(userId, signToken, encryToken);
                    }

                    callback.onSuccess(userId);
                  } catch (Exception e) {
                    callback.onError("로그인 응답 처리 중 예외", e);
                  }
                }
              });
    } catch (Exception e) {
      callback.onError("로그인 요청 생성 중 오류", e);
    }
  }

  public JSONObject exportSession() throws Exception {
    return new JSONObject()
        .put("userId", userId)
        .put("signToken", signToken)
        .put("encryToken", encryToken)
        .put("imeiMD5", deviceProfile.get("imeiMD5"));
  }

  public void restoreSession(JSONObject session) throws Exception {
    userId = session.getString("userId");
    signToken = session.getString("signToken");
    encryToken = session.getString("encryToken");
    deviceProfile.put("imeiMD5", session.getString("imeiMD5"));
  }
}
