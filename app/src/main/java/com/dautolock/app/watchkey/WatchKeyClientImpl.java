package com.dautolock.app.watchkey;

import android.content.Context;
import android.provider.Settings;
import com.dautolock.app.api.BydConfig;
import com.dautolock.app.api.CryptoUtils;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/**
 * Real watch provisioning REST client against the BYD region host, built on the app's existing
 * {@link CryptoUtils} and {@link BydConfig}. This talks to live BYD servers, so it is driven only on
 * the user's device with their own account — never from tests (which use a fake {@link
 * WatchKeyClient}).
 *
 * <p>The exact per-step field set, the success code values and the response envelope are only partly
 * known from static analysis; several details here are marked as adjustable and are refined from the
 * diagnostics of the first real run (every step logs its HTTP + business code, never any secret).
 */
public final class WatchKeyClientImpl implements WatchKeyClient {

  // The watch identity this app presents to use the watch-key channel. The user owns this model
  // (SM-R925N). These are protocol fields, not secrets.
  private static final String WATCH_BRAND = "SAMSUNG";
  private static final String WATCH_MODEL = "SM-R925N";
  private static final String WATCH_NAME = "SAMSUNGSM-R925N";
  private static final String WATCH_OS = "0";
  private static final String APP_VERSION = "341"; // Watch-app version field; adjustable.

  private static final MediaType JSON = MediaType.parse("application/json; charset=UTF-8");

  private final Context context;
  private final BydConfig config;
  private final WatchKeyProvision.Diag diag;
  private final OkHttpClient http;
  private volatile long timeDifference;

  public WatchKeyClientImpl(Context context, BydConfig config, WatchKeyProvision.Diag diag) {
    this.context = context.getApplicationContext();
    this.config = config;
    this.diag = diag;
    this.http =
        new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
  }

  @Override
  public long serverTime() throws Exception {
    JSONObject body = postUnLogin("watch/login/getServerCurrentTime", null);
    String t = firstString(body, "serverTime", "currentTime", "time", "timeStamp");
    if (!t.isEmpty()) {
      try {
        timeDifference = Long.parseLong(t.trim()) - System.currentTimeMillis();
      } catch (NumberFormatException ignore) {
        // Keep the device clock.
      }
    }
    return System.currentTimeMillis() + timeDifference;
  }

  @Override
  public QrInfo createQrCode() throws Exception {
    JSONObject body = postUnLogin("watch/login/create/qrcode", null);
    String uuid = firstString(body, "uuid", "qrUuid", "qrCode");
    return new QrInfo(uuid, buildQrPayload(uuid));
  }

  @Override
  public int qrStatus(String uuid) throws Exception {
    JSONObject body = postUnLogin("watch/login/check/qrcode", uuid);
    String s = firstString(body, "codeStatus", "status", "state");
    if (s.isEmpty()) return 0;
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public WatchTokens gainToken(String uuid) throws Exception {
    TreeMap<String, String> extra = new TreeMap<>();
    extra.put("uuid", uuid);
    JSONObject body = postUnLogin("watch/login/gain/token", uuid);
    return new WatchTokens(
        firstString(body, "signToken", "watchSignToken"),
        firstString(body, "encryToken", "watchEncryToken"),
        firstString(body, "identifier", "watchIdentifier"),
        firstString(body, "userType", "watchUserType"));
  }

  @Override
  public String gainVehicleVin(WatchTokens tokens) throws Exception {
    JSONObject body = postLogin("watch/login/gain/vehicle", tokens, null);
    return firstString(body, "vin", "watchVin", "carVin");
  }

  @Override
  public BleKey gainBluetooth(WatchTokens tokens) throws Exception {
    JSONObject body = postLogin("watch/login/gain/bluetooth", tokens, null);
    return new BleKey(
        firstString(body, "dkey", "btBleDk", "bleDk"),
        firstString(body, "keyNumber", "bleKeyNo", "keyNo"),
        firstString(body, "macAddress", "bleMacAddress", "mac"));
  }

  // --- request builders -------------------------------------------------------------------------

  /** Pre-token steps (create/check/gettime/gain-token): envelope keyed by the country code. */
  private JSONObject postUnLogin(String endpoint, String uuid) throws Exception {
    String country = country();
    TreeMap<String, String> enc = new TreeMap<>();
    enc.put("timeStamp", String.valueOf(now()));
    enc.put("random", random());
    enc.put("networkType", "wifi");
    enc.put("version", APP_VERSION);
    if (uuid != null && !uuid.isEmpty()) enc.put("uuid", uuid);

    String encryData = WatchEnvelope.encryData(enc, country);

    TreeMap<String, String> signed = new TreeMap<>(enc);
    signed.put("identifier", country);
    signed.put("watchImei", imeiMd5());
    signed.put("watchModel", WATCH_MODEL);
    signed.put("watchName", WATCH_NAME);
    signed.put("watchBrand", WATCH_BRAND);
    signed.put("watchAppVersion", APP_VERSION);
    signed.put("watchOs", WATCH_OS);
    signed.put("reqTimestamp", String.valueOf(now()));
    signed.put("language", language());
    signed.put("countryCode", country);
    String sign = WatchEnvelope.sign(signed, country);

    JSONObject req = new JSONObject();
    req.put("countryCode", country);
    req.put("encryData", encryData);
    req.put("identifier", country);
    req.put("language", language());
    req.put("reqTimestamp", String.valueOf(now()));
    req.put("sign", sign);
    req.put("watchAppVersion", APP_VERSION);
    req.put("watchBrand", WATCH_BRAND);
    req.put("watchImei", imeiMd5());
    req.put("watchModel", WATCH_MODEL);
    req.put("watchName", WATCH_NAME);
    req.put("watchOs", WATCH_OS);
    return exchange(endpoint, req.toString(), country);
  }

  /** Post-token steps (gain vehicle/bluetooth): envelope keyed by the encry token. */
  private JSONObject postLogin(String endpoint, WatchTokens tokens, TreeMap<String, String> body)
      throws Exception {
    String country = country();
    TreeMap<String, String> enc = body != null ? body : new TreeMap<>();
    enc.put("timeStamp", String.valueOf(now()));
    enc.put("random", random());
    enc.put("networkType", "wifi");
    enc.put("deviceType", "0");

    String encryData = WatchEnvelope.encryData(enc, CryptoUtils.md5Hex(tokens.encryToken));

    String identifier = tokens.identifier.isEmpty() ? country : tokens.identifier;
    TreeMap<String, String> signed = new TreeMap<>(enc);
    signed.put("identifier", identifier);
    signed.put("watchModel", WATCH_MODEL);
    signed.put("watchName", WATCH_NAME);
    signed.put("watchBrand", WATCH_BRAND);
    signed.put("watchAppVersion", APP_VERSION);
    signed.put("watchOs", WATCH_OS);
    signed.put("reqTimestamp", String.valueOf(now()));
    signed.put("language", language());
    signed.put("countryCode", country);
    if (!tokens.userType.isEmpty()) signed.put("userType", tokens.userType);
    String sign = WatchEnvelope.sign(signed, tokens.signToken);

    JSONObject req = new JSONObject();
    req.put("countryCode", country);
    req.put("encryData", encryData);
    req.put("identifier", identifier);
    req.put("language", language());
    req.put("reqTimestamp", String.valueOf(now()));
    req.put("sign", sign);
    req.put("watchAppVersion", APP_VERSION);
    req.put("watchBrand", WATCH_BRAND);
    req.put("watchImei", imeiMd5());
    req.put("watchModel", WATCH_MODEL);
    req.put("watchName", WATCH_NAME);
    req.put("watchOs", WATCH_OS);
    if (!tokens.userType.isEmpty()) req.put("userType", tokens.userType);
    return exchange(endpoint, req.toString(), CryptoUtils.md5Hex(tokens.encryToken));
  }

  // --- transport & parsing ----------------------------------------------------------------------

  private JSONObject exchange(String endpoint, String requestJson, String respKeySource)
      throws Exception {
    Request request =
        new Request.Builder()
            .url(config.getBaseUrl() + "/" + endpoint)
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("User-Agent", "okhttp/4.12.0")
            .post(RequestBody.create(requestJson, JSON))
            .build();
    try (Response response = http.newCall(request).execute()) {
      int httpCode = response.code();
      String raw = response.body() != null ? response.body().string() : "";
      JSONObject top = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
      String biz =
          firstString(top, "code", "resultCode", "rspCode", "returnCode", "resCode", "status");
      diag("WATCHKEY_HTTP", endpoint + " http=" + httpCode + " code=" + (biz.isEmpty() ? "?" : biz));

      // For the pre-approval steps only (server time, QR uuid, QR status — no secrets), log the raw
      // body so the real field names and the QR uuid value can be confirmed from the first run. The
      // token/vehicle/bluetooth steps carry secrets and are never logged here.
      if (bodyLoggable(endpoint) && !raw.isEmpty()) {
        diag("WATCHKEY_BODY", endpoint + " " + (raw.length() > 1200 ? raw.substring(0, 1200) + "…" : raw));
      }

      // The business payload may be an encrypted hex string ("encryData"/"data") or a plain object.
      JSONObject merged = new JSONObject();
      copyStrings(top, merged);
      String enc = top.optString("encryData", top.optString("data", ""));
      if (!enc.isEmpty() && isHex(enc)) {
        try {
          String plain = CryptoUtils.aesDecryptUtf8(enc, CryptoUtils.md5Hex(respKeySource));
          if (plain != null && plain.trim().startsWith("{")) copyStrings(new JSONObject(plain), merged);
        } catch (Exception e) {
          diag("WATCHKEY_DECRYPT_ERR", endpoint + " " + e.getMessage());
        }
      } else {
        JSONObject data = top.optJSONObject("data");
        if (data != null) copyStrings(data, merged);
      }
      return merged;
    }
  }

  /** Flatten string/number leaves (one level into nested objects) so field lookup is envelope-agnostic. */
  private static void copyStrings(JSONObject src, JSONObject dst) throws org.json.JSONException {
    java.util.Iterator<String> it = src.keys();
    while (it.hasNext()) {
      String k = it.next();
      Object v = src.opt(k);
      if (v instanceof JSONObject) {
        copyStrings((JSONObject) v, dst);
      } else if (v != null && !(v instanceof org.json.JSONArray)) {
        if (!dst.has(k)) dst.put(k, String.valueOf(v));
      }
    }
  }

  private static String firstString(JSONObject obj, String... names) {
    for (String n : names) {
      String v = obj.optString(n, "");
      if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
    }
    return "";
  }

  /** Only the pre-approval steps carry no secret, so only their bodies may be logged. */
  private static boolean bodyLoggable(String endpoint) {
    return endpoint.endsWith("getServerCurrentTime")
        || endpoint.endsWith("create/qrcode")
        || endpoint.endsWith("check/qrcode");
  }

  private static boolean isHex(String s) {
    if (s.length() < 16 || s.length() % 2 != 0) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.digit(c, 16) < 0) return false;
    }
    return true;
  }

  private String buildQrPayload(String uuid) {
    return "watchQRCode://watch.bydautolink?uuid="
        + uuid
        + "&countryCode="
        + country()
        + "&watchBrand="
        + WATCH_BRAND
        + "&watchModel="
        + WATCH_MODEL
        + "&watchName="
        + WATCH_NAME
        + "&watchImei="
        + imeiMd5()
        + "&watchOs="
        + WATCH_OS
        + "&watchAppVersion="
        + APP_VERSION;
  }

  private long now() {
    return System.currentTimeMillis() + timeDifference;
  }

  private static String random() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  private String imeiMd5() {
    String androidId = "";
    try {
      androidId = Settings.Secure.getString(context.getContentResolver(), "android_id");
    } catch (Exception ignore) {
      // Fall through to a random id.
    }
    if (androidId == null || androidId.trim().isEmpty()) {
      androidId = UUID.randomUUID().toString().replace("-", "");
    }
    return CryptoUtils.md5Hex(androidId.trim().toUpperCase(Locale.US));
  }

  private String country() {
    String c = config.getCountryCode();
    return c == null || c.isEmpty() ? "KR" : c;
  }

  private String language() {
    String l = config.getLanguage();
    return l == null || l.isEmpty() ? "ko" : l;
  }

  private void diag(String event, String detail) {
    if (diag != null) diag.record(event, detail);
  }
}
