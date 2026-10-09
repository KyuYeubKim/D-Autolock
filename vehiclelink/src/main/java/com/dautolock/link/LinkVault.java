package com.dautolock.link;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Independent of cloud account storage; never backed up to another phone. */
public final class LinkVault {
  private final Context context;

  public LinkVault(Context context) {
    this.context = context.getApplicationContext();
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    String alias = "d-autolock-vehicle-link-v1";
    if (!ks.containsAlias(alias)) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (SecretKey) ks.getKey(alias, null);
  }

  public synchronized void save(JSONObject data) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    JSONObject envelope =
        new JSONObject()
            .put("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
            .put(
                "data",
                Base64.encodeToString(
                    c.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
    if (!context
        .getSharedPreferences("vehicle_link_vault", 0)
        .edit()
        .putString("data", envelope.toString())
        .commit()) throw new Exception("연결키 저장 실패");
  }

  public synchronized JSONObject read() throws Exception {
    String saved = context.getSharedPreferences("vehicle_link_vault", 0).getString("data", "");
    if (saved.isEmpty()) return new JSONObject();
    JSONObject envelope = new JSONObject(saved);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
    return new JSONObject(
        new String(
            c.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)),
            StandardCharsets.UTF_8));
  }

  /** Whether an encrypted pairing is stored, without decrypting it (cheap, safe at boot). */
  public synchronized boolean exists() {
    return !context.getSharedPreferences("vehicle_link_vault", 0).getString("data", "").isEmpty();
  }

  public synchronized void clear() throws Exception {
    if (!context.getSharedPreferences("vehicle_link_vault", 0).edit().clear().commit())
      throw new Exception("연결키 삭제 실패");
  }
}
