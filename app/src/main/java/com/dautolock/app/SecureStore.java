package com.dautolock.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SecureStore {
  private final Context context;

  SecureStore(Context context) {
    this.context = context;
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("d-autolock-v1")) {
      KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  "d-autolock-v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) ks.getKey("d-autolock-v1", null);
  }

  synchronized JSONObject read() throws Exception {
    String saved = context.getSharedPreferences("vault", 0).getString("data", "");
    if (saved.isEmpty()) return new JSONObject();
    JSONObject envelope = new JSONObject(saved);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
    return new JSONObject(
        new String(
            c.doFinal(Base64.decode(envelope.getString("cipher"), Base64.NO_WRAP)),
            StandardCharsets.UTF_8));
  }

  synchronized void save(JSONObject data) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    String encrypted =
        Base64.encodeToString(
            c.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    JSONObject envelope =
        new JSONObject()
            .put("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
            .put("cipher", encrypted);
    if (!context
        .getSharedPreferences("vault", 0)
        .edit()
        .putString("data", envelope.toString())
        .commit()) throw new Exception("보안 저장소에 저장하지 못했습니다");
  }

  void clear() {
    context.getSharedPreferences("vault", 0).edit().clear().commit();
  }
}
