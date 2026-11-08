package com.dautolock.app.watchkey;

import android.content.Context;
import android.provider.Settings;
import com.dautolock.app.api.BydConfig;
import com.dautolock.app.api.CryptoUtils;
import java.util.Locale;
import java.util.TimeZone;
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
 * Real watch provisioning REST client against the BYD region host, matching the reference watch
 * client (BydLock's {@code WatchKeyApi}) field-for-field: the AES/MD5/SHA1 envelope, the per-step
 * field sets, the {@code {"response":"{...}"}} wrapper, and the response field names. Built on the
 * app's existing {@link CryptoUtils} (whose {@code aesEncryptHex}/{@code md5Hex} byte-match the watch
 * channel) plus {@link WatchEnvelope#sign} (which uses the watch channel's own {@code sha1Mixed}).
 *
 * <p>This talks to live BYD servers, so it is driven only on the user's device with their own
 * account — never from tests (which use a fake {@link WatchKeyClient}). Every step logs its HTTP and
 * business code; the three pre-approval steps also log their raw body (no secrets there). The token,
 * vehicle and bluetooth steps never log their body.
 */
public final class WatchKeyClientImpl implements WatchKeyClient {

  // The watch identity this app presents to use the watch-key channel. The user owns this model
  // (SM-R925N). These are protocol fields, not secrets.
  private static final String WATCH_BRAND = "SAMSUNG";
  private static final String WATCH_MODEL = "SM-R925N";
  private static final String WATCH_NAME = "SAMSUNGSM-R925N";
  private static final String WATCH_OS = "0";
  private static final String APP_VERSION = "341"; // Watch-app version field (reference value).

  // Fixed key seed for the QR payload cipher (reference: md5("watch.bydautolink"), lower-cased).
  private static final String QR_KEY_SEED = "watch.bydautolink";

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
    TreeMap<String, String> enc = new TreeMap<>();
    enc.put("timeStamp", String.valueOf(now()));
    enc.put("random", random());
    enc.put("networkType", "wifi");
    enc.put("version", APP_VERSION);
    JSONObject body = sendUnLogin("watch/login/getServerCurrentTime", enc);
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
    TreeMap<String, String> enc = new TreeMap<>();
    enc.put("timeStamp", String.valueOf(now()));
    enc.put("random", random());
    enc.put("networkType", "wifi");
    enc.put("version", APP_VERSION);
    JSONObject body = sendUnLogin("watch/login/create/qrcode", enc);
    String uuid = firstString(body, "uuid", "qrUuid", "qrCode");
    return new QrInfo(uuid, buildQrPayload(uuid));
  }

  @Override
  public int qrStatus(String uuid) throws Exception {
    TreeMap<String, String> enc = new TreeMap<>();
    enc.put("timeStamp", String.valueOf(now()));
    enc.put("random", random());
    enc.put("networkType", "wifi");
    enc.put("version", APP_VERSION);
    enc.put("uuid", uuid);
    JSONObject body = sendUnLogin("watch/login/check/qrcode", enc);
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
    TreeMap<String, String> enc = new TreeMap<>();
    enc.put("timeStamp", String.valueOf(now()));
    enc.put("uuid", uuid);
    enc.put("timeZone", TimeZone.getDefault().getID());
    JSONObject body = sendUnLogin("watch/login/gain/token", enc);
    return new WatchTokens(
        firstString(body, "signToken", "watchSignToken"),
        firstString(body, "encryToken", "watchEncryToken"),
        firstString(body, "identifier", "userId", "watchIdentifier"),
        firstString(body, "userType", "watchUserType"),
        firstString(body, "vin", "watchVin", "carVin"));
  }

  @Override
  public String gainVehicleVin(WatchTokens tokens) throws Exception {
    TreeMap<String, String> map = new TreeMap<>();
    map.put("appVersion", "2");
    map.put("vin", tokens.vin);
    JSONObject body = sendLogin("watch/login/gain/vehicle", tokens, map);
    String vin = firstString(body, "vin", "watchVin", "carVin");
    return vin.isEmpty() ? tokens.vin : vin;
  }

  @Override
  public BleKey gainBluetooth(WatchTokens tokens) throws Exception {
    TreeMap<String, String> map = new TreeMap<>();
    map.put("appVersion", "2");
    map.put("vin", tokens.vin);
    JSONObject body = sendLogin("watch/login/gain/bluetooth", tokens, map);
    return new BleKey(
        firstString(body, "dk", "dkey", "DK", "dK", "DKEY", "btBleDk", "bleDk"),
        firstString(body, "empowerBluetoothKeyNo", "keyNumber", "bleKeyNo", "keyNo"),
        firstString(body, "bluetoothMacAddress", "macAddress", "bleMacAddress", "mac"));
  }

  // --- request builders -------------------------------------------------------------------------

  /**
   * Pre-token steps (time / create / check / gain-token): the {@code encryData} and {@code sign} are
   * keyed by the country code. {@code enc} is the (already built) business map; its {@code timeStamp}
   * is reused as {@code reqTimestamp} so the signed value matches what the server reconstructs.
   */
  private JSONObject sendUnLogin(String endpoint, TreeMap<String, String> enc) throws Exception {
    String country = country();
    String ts = enc.get("timeStamp");
    String imei = imeiMd5();

    String encryData = WatchEnvelope.encryData(enc, country);

    TreeMap<String, String> signed = new TreeMap<>(enc);
    signed.put("identifier", country);
    signed.put("watchImei", imei);
    signed.put("watchModel", WATCH_MODEL);
    signed.put("watchName", WATCH_NAME);
    signed.put("watchBrand", WATCH_BRAND);
    signed.put("watchAppVersion", APP_VERSION);
    signed.put("watchOs", WATCH_OS);
    signed.put("reqTimestamp", ts);
    signed.put("language", language());
    signed.put("countryCode", country);
    String sign = WatchEnvelope.sign(signed, country);

    JSONObject req = new JSONObject();
    req.put("countryCode", country);
    req.put("encryData", encryData);
    req.put("identifier", country);
    req.put("language", language());
    req.put("reqTimestamp", ts);
    req.put("sign", sign);
    req.put("watchAppVersion", APP_VERSION);
    req.put("watchBrand", WATCH_BRAND);
    req.put("watchImei", imei);
    req.put("watchModel", WATCH_MODEL);
    req.put("watchName", WATCH_NAME);
    req.put("watchOs", WATCH_OS);
    return exchange(endpoint, req.toString(), CryptoUtils.md5Hex(country).toLowerCase(Locale.ROOT));
  }

  /** Post-token steps (gain vehicle/bluetooth): envelope keyed by the encry token; sign by the sign token. */
  private JSONObject sendLogin(String endpoint, WatchTokens tokens, TreeMap<String, String> bodyMap)
      throws Exception {
    String country = country();
    String ts = String.valueOf(now());
    String imei = imeiMd5();
    String userType = tokens.userType;
    String identifier = tokens.identifier.isEmpty() ? country : tokens.identifier;

    TreeMap<String, String> enc = new TreeMap<>();
    enc.put("timeStamp", ts);
    enc.put("random", random());
    enc.put("watchImei", imei);
    enc.put("deviceType", "0");
    enc.put("networkType", "wifi");
    if (bodyMap != null) enc.putAll(bodyMap);

    String encryData = WatchEnvelope.encryData(enc, tokens.encryToken);

    TreeMap<String, String> signed = new TreeMap<>(enc);
    signed.put("identifier", identifier);
    signed.put("watchModel", WATCH_MODEL);
    signed.put("watchName", WATCH_NAME);
    signed.put("watchBrand", WATCH_BRAND);
    signed.put("watchAppVersion", APP_VERSION);
    signed.put("watchOs", WATCH_OS);
    signed.put("reqTimestamp", ts);
    signed.put("language", language());
    signed.put("countryCode", country);
    signed.put("userType", userType);
    String sign = WatchEnvelope.sign(signed, tokens.signToken);

    JSONObject req = new JSONObject();
    req.put("identifier", identifier);
    req.put("watchImei", imei);
    req.put("watchModel", WATCH_MODEL);
    req.put("watchName", WATCH_NAME);
    req.put("watchBrand", WATCH_BRAND);
    req.put("watchAppVersion", APP_VERSION);
    req.put("watchOs", WATCH_OS);
    req.put("reqTimestamp", ts);
    req.put("language", language());
    req.put("countryCode", country);
    req.put("userType", userType);
    req.put("encryData", encryData);
    req.put("sign", sign);
    return exchange(
        endpoint, req.toString(), CryptoUtils.md5Hex(tokens.encryToken).toLowerCase(Locale.ROOT));
  }

  // --- transport & parsing ----------------------------------------------------------------------

  /**
   * POST the request, unwrap the {@code {"response":"{...}"}} envelope, raise the server's business
   * code when it is not {@code "0"}, and return a flattened business object. {@code respKeyHex} is
   * the AES key (already hex) used to decrypt an encrypted {@code respondData} payload.
   */
  private JSONObject exchange(String endpoint, String requestJson, String respKeyHex)
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
      String wrapped = top.optString("response", "");
      if (wrapped.isEmpty()) wrapped = top.optString("respondData", "");

      JSONObject biz = (!wrapped.isEmpty() && wrapped.trim().startsWith("{")) ? new JSONObject(wrapped) : null;
      String code = biz != null ? biz.optString("code", "") : "";
      String message = biz != null ? biz.optString("message", "") : "";
      diag("WATCHKEY_HTTP", endpoint + " http=" + httpCode + " code=" + (code.isEmpty() ? "?" : code));

      // Pre-approval steps only (server time, QR uuid, QR status — no secrets): log the raw body so
      // the real field names and the QR uuid value can be confirmed. Token/vehicle/bluetooth bodies
      // carry secrets and are never logged.
      if (bodyLoggable(endpoint) && !raw.isEmpty()) {
        diag("WATCHKEY_BODY", endpoint + " " + (raw.length() > 1200 ? raw.substring(0, 1200) + "…" : raw));
      }

      if (biz == null) {
        throw new java.io.IOException("응답 형식 오류 (response 없음)");
      }
      if (!code.isEmpty() && !"0".equals(code)) {
        throw new java.io.IOException(
            "워치 서버 오류 " + code + (message.isEmpty() ? "" : " · " + message));
      }

      JSONObject merged = new JSONObject();

      // Merge the decrypted business payload FIRST so it wins over the envelope. The envelope also
      // carries its own 'identifier' (="KR") and 'language' that must NOT mask the token's real
      // identifier; copyStrings is first-wins, so the decrypted fields have to go in first.
      JSONObject decrypted = null;
      String respondData = biz.optString("respondData", "");
      if (respondData.isEmpty()) respondData = biz.optString("response", "");
      if (!respondData.isEmpty() && isHex(respondData)) {
        try {
          String plain = CryptoUtils.aesDecryptUtf8(respondData, respKeyHex);
          if (plain != null) {
            String p = plain.trim();
            if (p.startsWith("{")) {
              decrypted = new JSONObject(p);
              copyStrings(decrypted, merged);
            } else {
              if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
                p = p.substring(1, p.length() - 1);
              }
              merged.put("serverTime", p);
            }
          }
        } catch (Exception e) {
          diag("WATCHKEY_DECRYPT_ERR", endpoint + " " + e.getMessage());
        }
      }

      // The envelope's own metadata (code / message / identifier="KR" / language) must NOT leak into
      // the business object: the token's real identity is its userId, and an envelope identifier
      // would mask it (the server then can't find the session → 9013). Pull only codeStatus as a
      // fallback, for responses that carry it at the envelope level with no encrypted respondData.
      if (!merged.has("codeStatus")) {
        String cs = biz.optString("codeStatus", "");
        if (!cs.isEmpty()) merged.put("codeStatus", cs);
      }

      // Structure-only diagnostic for the token step (no secret VALUES — only field names, presence
      // and lengths), mirroring the reference's GainRawDiag. This is how we confirm the token fields
      // we extract match what the server returns, since gain/vehicle rejecting the token as invalid
      // (9013) points at the token values, not the request shape.
      if (endpoint.endsWith("gain/token") && decrypted != null) {
        JSONObject info = decrypted.optJSONObject("watchTokenInfo");
        diag(
            "WATCHKEY_TOKEN_RAW",
            "topKeys=[" + keyNames(decrypted) + "]"
                + (info != null ? " infoKeys=[" + keyNames(info) + "]" : "")
                + " signLen=" + merged.optString("signToken", "").length()
                + " encryLen=" + merged.optString("encryToken", "").length()
                + " idLen=" + merged.optString("identifier", "").length()
                + " idIsKR=" + "KR".equals(merged.optString("identifier", ""))
                + " userType=" + merged.optString("userType", "")
                + " vinLen=" + merged.optString("vin", "").length()
                + " controlPwdLen=" + merged.optString("controlPwd", "").length());
      }
      return merged;
    }
  }

  /** Only the pre-approval steps carry no secret, so only their bodies may be logged. */
  private static boolean bodyLoggable(String endpoint) {
    return endpoint.endsWith("getServerCurrentTime")
        || endpoint.endsWith("create/qrcode")
        || endpoint.endsWith("check/qrcode");
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

  /** Comma-joined top-level key names of a JSON object (names only, never values). */
  private static String keyNames(JSONObject obj) {
    StringBuilder sb = new StringBuilder();
    java.util.Iterator<String> it = obj.keys();
    int i = 0;
    while (it.hasNext() && i < 30) {
      if (i > 0) sb.append(',');
      sb.append(it.next());
      i++;
    }
    return sb.toString();
  }

  private static String firstString(JSONObject obj, String... names) {
    for (String n : names) {
      String v = obj.optString(n, "");
      if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
    }
    return "";
  }

  private static boolean isHex(String s) {
    if (s.length() < 16 || s.length() % 2 != 0) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.digit(c, 16) < 0) return false;
    }
    return true;
  }

  /**
   * The QR the owner scans in the official BYD app: {@code watchQRCode://} followed by the AES-hex of
   * {@code watchImei=<imei>&uuid=<uuid>&countryCode=<cc>} (key = md5("watch.bydautolink"), upper-cased
   * hex). The official app decrypts this; a plaintext payload is rejected at scan as a region
   * mismatch.
   */
  private String buildQrPayload(String uuid) {
    String plain = "watchImei=" + imeiMd5() + "&uuid=" + uuid + "&countryCode=" + country();
    String keyHex = CryptoUtils.md5Hex(QR_KEY_SEED).toLowerCase(Locale.ROOT);
    return "watchQRCode://" + CryptoUtils.aesEncryptHex(plain, keyHex).toUpperCase(Locale.ROOT);
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
