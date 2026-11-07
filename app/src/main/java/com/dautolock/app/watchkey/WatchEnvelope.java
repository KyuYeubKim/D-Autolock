package com.dautolock.app.watchkey;

import com.dautolock.app.api.CryptoUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    return sha1MixedWatch(sb.toString());
  }

  /**
   * The watch channel's own {@code sha1Mixed}: SHA-1, then each byte as {@code
   * Integer.toHexString(b & 0xFF)} (so no zero padding — {@code 0x05 -> "5"}, {@code 0x0a -> "a"}),
   * upper-casing even byte indices and lower-casing odd ones. This differs from the main cloud
   * API's variant (which zero-pads with {@code %02x} and then strips '0' at even positions); the
   * watch server rejects the signature if the main variant is used.
   */
  static String sha1MixedWatch(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < digest.length; i++) {
        String hex = Integer.toHexString(digest[i] & 0xFF);
        sb.append(i % 2 == 0 ? hex.toUpperCase(Locale.ROOT) : hex.toLowerCase(Locale.ROOT));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException("watch SHA1 mixed hashing failed", e);
    }
  }
}
