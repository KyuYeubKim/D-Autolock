package com.dautolock.app.watchkey;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Encrypted at-rest storage for the Bluetooth (watch) key, isolated from the app's main {@code
 * SecureStore}: its own SharedPreferences file ("watch_vault") and its own AndroidKeyStore alias
 * ("d-autolock-watch-v1"). This keeps the whole feature self-contained — {@link #clearAll()} wipes
 * it, and deleting this package removes it entirely without touching the account vault.
 *
 * <p>Stores only what the BLE key path needs: the issued dkey / keyNumber / MAC, the watch session
 * token, and the QR uuid for re-issue. It never stores the owner's BYD password or PIN — approval
 * is done with the official BYD app, so those secrets never reach this app.
 */
public final class WatchKeyStore {

  interface KeyProvider {
    SecretKey get() throws Exception;
  }

  private static final String PREFS = "watch_vault";
  private static final String ALIAS = "d-autolock-watch-v1";

  private final Context context;
  private final KeyProvider keys;

  public WatchKeyStore(Context context) {
    this.context = context;
    this.keys = this::key;
  }

  WatchKeyStore(Context context, KeyProvider keys) {
    this.context = context;
    this.keys = keys;
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) ks.getKey(ALIAS, null);
  }

  private synchronized JSONObject read() {
    try {
      String saved = context.getSharedPreferences(PREFS, 0).getString("data", "");
      if (saved.isEmpty()) return new JSONObject();
      JSONObject envelope = new JSONObject(saved);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          keys.get(),
          new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
      return new JSONObject(
          new String(
              c.doFinal(Base64.decode(envelope.getString("cipher"), Base64.NO_WRAP)),
              StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  private synchronized void write(JSONObject data) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, keys.get());
    String cipher =
        Base64.encodeToString(
            c.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    JSONObject envelope =
        new JSONObject()
            .put("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
            .put("cipher", cipher);
    if (!context
        .getSharedPreferences(PREFS, 0)
        .edit()
        .putString("data", envelope.toString())
        .commit()) throw new Exception("워치 키 저장소에 저장하지 못했습니다");
  }

  synchronized String get(String field) {
    return read().optString(field, "");
  }

  synchronized void put(String field, String value) throws Exception {
    JSONObject data = read();
    data.put(field, value == null ? "" : value);
    write(data);
  }

  /** Save the issued key material and session in one write. */
  public synchronized void saveKey(
      String dkey, String keyNumber, String macAddress, WatchTokens tokens, String vin)
      throws Exception {
    JSONObject data = read();
    data.put("dkey", dkey == null ? "" : dkey);
    data.put("keyNumber", keyNumber == null ? "" : keyNumber);
    data.put("macAddress", macAddress == null ? "" : macAddress);
    if (tokens != null) {
      data.put("signToken", nz(tokens.signToken));
      data.put("encryToken", nz(tokens.encryToken));
      data.put("identifier", nz(tokens.identifier));
      data.put("userType", nz(tokens.userType));
    }
    data.put("vin", vin == null ? "" : vin);
    write(data);
  }

  public synchronized void saveQrUuid(String uuid) throws Exception {
    put("qrUuid", uuid);
  }

  public String dkey() {
    return get("dkey");
  }

  public String keyNumber() {
    return get("keyNumber");
  }

  public String macAddress() {
    return get("macAddress");
  }

  public String qrUuid() {
    return get("qrUuid");
  }

  public String vin() {
    return get("vin");
  }

  public WatchTokens tokens() {
    return new WatchTokens(
        get("signToken"), get("encryToken"), get("identifier"), get("userType"));
  }

  public boolean hasBleKey() {
    return !dkey().isEmpty() && !macAddress().isEmpty();
  }

  public boolean hasWatchToken() {
    return !get("signToken").isEmpty();
  }

  /** Wipe everything this feature stored. Used by the "키 삭제 / 초기화" button and on rollback. */
  public synchronized void clearAll() {
    context.getSharedPreferences(PREFS, 0).edit().remove("data").commit();
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
