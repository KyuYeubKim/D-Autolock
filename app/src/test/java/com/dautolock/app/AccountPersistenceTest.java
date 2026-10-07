package com.dautolock.app;

import static org.junit.Assert.*;

import android.content.Context;
import com.dautolock.app.api.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AccountPersistenceTest {
  private final List<Controller> controllers = new ArrayList<>();
  private Context context;
  private SecureStore store;
  private boolean failStorage;

  static class FakeProtocol extends CloudProtocol {
    int logins;
    String usedPassword = "";
    boolean expireNext;

    FakeProtocol() {
      super(BydConfig.fromRegion("KR"));
    }

    @Override
    public void login(String user, String password, BydApiCallback<String> cb) {
      logins++;
      if (password.equals("rejected")) {
        cb.onError("로그인 실패", null);
        return;
      }
      usedPassword = password;
      try {
        restoreSession(
            new JSONObject()
                .put("userId", "fake-id")
                .put("signToken", "fake-token-" + logins)
                .put("encryToken", "fake-encryption")
                .put("imeiMD5", CryptoUtils.md5Hex(user)));
        cb.onSuccess("fake-id");
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public void postTokenSecure(
        String endpoint, Map<String, Object> data, String vin, BydApiCallback<JSONObject> cb) {
      if (expireNext) {
        expireNext = false;
        setSignToken(null);
        cb.onError("expired", new SessionExpiredException());
        return;
      }
      try {
        JSONObject car = new JSONObject().put("vin", "test-vehicle").put("modelName", "Dolphin");
        cb.onSuccess(
            new JSONObject()
                .put("list", new JSONArray().put(car))
                .put("test-vehicle", new JSONObject().put("functionNo", "1005")));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("vault", 0).edit().clear().commit();
    context.getSharedPreferences("settings", 0).edit().clear().commit();
    SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
    store =
        new SecureStore(
            context,
            () -> {
              if (failStorage) throw new Exception("test storage unavailable");
              return key;
            });
  }

  private Controller create() throws Exception {
    Controller c = new Controller(context, store, () -> new CloudClient(new FakeProtocol()));
    controllers.add(c);
    await(c);
    return c;
  }

  static void await(Controller c) throws Exception {
    long until = System.currentTimeMillis() + 5000;
    while ((c.initializing || c.busy()) && System.currentTimeMillis() < until) Thread.sleep(10);
    assertFalse(c.initializing);
    assertFalse(c.busy());
  }

  private Controller linked() throws Exception {
    Controller c = create();
    c.login("sub@example.com", "test-secret", "123456");
    await(c);
    c.selectVehicle(new JSONObject().put("vin", "test-vehicle").put("modelName", "Dolphin"));
    await(c);
    c.device("BYD BLE", "AA:BB:CC:DD:EE:FF");
    return c;
  }

  @After
  public void cleanup() throws Exception {
    for (Controller c : controllers) {
      java.lang.reflect.Field field = Controller.class.getDeclaredField("worker");
      field.setAccessible(true);
      ExecutorService executor = (ExecutorService) field.get(c);
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
      c.diagnostics.close();
    }
  }

  @Test
  public void encryptedAccountSurvivesRestartAndBlankReconnectKeepsVehicleAndBle()
      throws Exception {
    Controller first = linked();
    Controller restored = create();
    assertEquals("sub@example.com", restored.loginUser);
    assertTrue(restored.hasSavedLogin());
    restored.login(restored.loginUser, "", "");
    await(restored);
    assertEquals("test-vehicle", restored.vin);
    assertEquals("BYD BLE", restored.settings.getString("deviceName", ""));
    assertEquals(CryptoUtils.md5Hex("123456"), restored.pinHash);
    assertEquals("test-secret", ((FakeProtocol) restored.cloud.protocol).usedPassword);
    String encrypted = context.getSharedPreferences("vault", 0).getString("data", "");
    assertFalse(encrypted.contains("test-secret"));
    assertFalse(encrypted.contains("sub@example.com"));
    assertFalse(encrypted.contains("test-vehicle"));
    assertEquals("test-secret", store.read().getString("loginPassword"));
    assertFalse(first.log().contains("test-secret"));
    assertFalse(restored.log().contains("test-secret"));
  }

  @Test
  public void failedLoginAndFailedStorageRetainPreviousAccount() throws Exception {
    Controller c = linked();
    c.login("other@example.com", "rejected", "654321");
    await(c);
    assertEquals("sub@example.com", c.loginUser);
    failStorage = true;
    c.login("other@example.com", "other-secret", "654321");
    await(c);
    failStorage = false;
    assertEquals("sub@example.com", c.loginUser);
    assertEquals("test-secret", store.read().getString("loginPassword"));
    assertEquals("test-vehicle", c.vin);
    assertEquals("BYD BLE", c.settings.getString("deviceName", ""));
  }

  @Test
  public void differentAccountCannotReusePasswordPinOrBluetooth() throws Exception {
    Controller c = linked();
    assertFalse(c.savedPasswordFor("other@example.com"));
    assertFalse(c.savedPinFor("other@example.com"));
    c.login("other@example.com", "other-secret", "");
    await(c);
    assertEquals("sub@example.com", c.loginUser);
    c.login("other@example.com", "other-secret", "654321");
    await(c);
    assertEquals("other@example.com", c.loginUser);
    assertEquals("", c.vin);
    assertFalse(c.settings.contains("address"));
  }

  @Test
  public void logoutDeletesCredentialsAndDoesNotReconnectAfterRestart() throws Exception {
    Controller c = linked();
    c.logout();
    await(c);
    assertEquals(0, store.read().length());
    assertFalse(c.hasSavedLogin());
    assertEquals("", c.pinHash);
    Controller restored = create();
    assertFalse(restored.hasSavedLogin());
    assertFalse(restored.cloud.protocol.isLoggedIn());
    assertFalse(restored.settings.contains("address"));
  }

  @Test
  public void version020MigrationPreservesVehicleAndBluetooth() throws Exception {
    Controller first = linked();
    JSONObject legacy = store.read();
    legacy.remove("loginUser");
    legacy.remove("loginPassword");
    store.save(legacy);
    Controller migrated = create();
    assertFalse(migrated.hasSavedLogin());
    migrated.login("sub@example.com", "test-secret", "");
    await(migrated);
    assertTrue(migrated.hasSavedLogin());
    assertEquals(first.vin, migrated.vin);
    assertEquals("BYD BLE", migrated.settings.getString("deviceName", ""));
  }

  @Test
  public void expiredSessionAutomaticallyReconnectsWithoutClearingSelection() throws Exception {
    Controller c = linked();
    FakeProtocol p = (FakeProtocol) c.cloud.protocol;
    p.expireNext = true;
    c.refreshVehicles();
    await(c);
    assertEquals(2, p.logins);
    assertEquals("test-secret", p.usedPassword);
    assertEquals("test-vehicle", c.vin);
    assertEquals("BYD BLE", c.settings.getString("deviceName", ""));
    assertEquals("fake-token-2", store.read().getJSONObject("session").getString("signToken"));
  }
}
