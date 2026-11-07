package com.dautolock.app.watchkey;

import static org.junit.Assert.*;

import android.content.Context;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WatchKeyStoreTest {

  private Context context;
  private WatchKeyStore store;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    SecretKeySpec key = new SecretKeySpec(new byte[16], "AES");
    store = new WatchKeyStore(context, () -> key);
  }

  @Test
  public void savesAndReadsBackTheKeyThenClears() throws Exception {
    assertFalse(store.hasBleKey());
    WatchTokens tokens = new WatchTokens("sign", "encry", "id", "owner");
    store.saveKey("DEADBEEF", "7", "AA:BB:CC:DD:EE:FF", tokens, "LVIN123");

    assertTrue(store.hasBleKey());
    assertEquals("DEADBEEF", store.dkey());
    assertEquals("7", store.keyNumber());
    assertEquals("AA:BB:CC:DD:EE:FF", store.macAddress());
    assertEquals("LVIN123", store.vin());
    assertTrue(store.hasWatchToken());
    assertEquals("encry", store.tokens().encryToken);

    store.clearAll();
    assertFalse(store.hasBleKey());
    assertFalse(store.hasWatchToken());
    assertEquals("", store.dkey());
  }

  @Test
  public void usesItsOwnVaultSeparateFromTheAccountStore() throws Exception {
    store.saveKey("DK", "1", "11:22:33:44:55:66", new WatchTokens("s", "e", "i", "u"), "V");
    // The feature's own prefs file holds the data; the account vault is untouched.
    assertFalse(context.getSharedPreferences("watch_vault", 0).getString("data", "").isEmpty());
    assertEquals("", context.getSharedPreferences("vault", 0).getString("data", ""));
  }
}
