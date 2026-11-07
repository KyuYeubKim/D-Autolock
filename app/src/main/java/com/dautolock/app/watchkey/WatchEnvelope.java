package com.dautolock.app.watchkey;

import com.dautolock.app.api.CryptoUtils;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;

/**
 * The watch REST request envelope, built from the app's existing {@link CryptoUtils} primitives
 * (the same md5/sha1/AES family the cloud path already uses). Pure and unit-testable — no network,
 * no device state. The per-step field set is assembled by {@link WatchKeyClientImpl}; this class
 * only does the two shared transforms:
 *
 * <ul>
 *   <li>{@link #encryData} — sort the params, serialize to JSON, AES-encrypt to a hex string.
 *   <li>{@link #sign} — sort the params as {@code k=v&...}, append the hashed secret, SHA1-mix.
 * </ul>
 */
public final class WatchEnvelope {

  private WatchEnvelope() {}

  /** JSON of the params in sorted (TreeMap) order, values JSON-escaped. */
  static String sortedJson(Map<String, String> params) {
    TreeMap<String, String> sorted =
        params instanceof TreeMap ? (TreeMap<String, String>) params : new TreeMap<>(params);
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> e : sorted.entrySet()) {
      if (!first) sb.append(',');
      first = false;
      sb.append(JSONObject.quote(e.getKey()))
          .append(':')
          .append(JSONObject.quote(e.getValue() == null ? "" : e.getValue()));
    }
    return sb.append('}').toString();
  }

  /**
   * {@code encryData}: AES-encrypt the sorted-JSON params. The AES key is the MD5 of {@code
   * keySource} (country code before a token is issued, the encry token afterwards), lower-cased.
   */
  public static String encryData(Map<String, String> params, String keySource) {
    String keyHex = CryptoUtils.md5Hex(keySource).toLowerCase(Locale.ROOT);
    return CryptoUtils.aesEncryptHex(sortedJson(params), keyHex);
  }

  /**
   * {@code sign}: sorted {@code key=value&...} over the non-empty keys, then {@code
   * password=<md5(secret)>}, then SHA1-mixed. The secret is the country code before a token is
   * issued, the sign token afterwards.
   */
  public static String sign(Map<String, String> params, String secret) {
    TreeMap<String, String> sorted =
        params instanceof TreeMap ? (TreeMap<String, String>) params : new TreeMap<>(params);
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : sorted.entrySet()) {
      String key = e.getKey();
      if (key == null || key.isEmpty()) continue;
      String value = e.getValue();
      if (value == null) value = "";
      sb.append(key).append('=').append(value).append('&');
    }
    sb.append("password=").append(CryptoUtils.md5Hex(secret));
    return CryptoUtils.sha1Mixed(sb.toString());
  }
}
