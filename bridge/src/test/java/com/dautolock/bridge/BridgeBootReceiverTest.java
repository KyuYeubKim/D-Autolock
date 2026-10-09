package com.dautolock.bridge;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 33})
public class BridgeBootReceiverTest {
  @Test
  public void bootStartsServerOnlyAfterPairing() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
    new BridgeBootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    // Encrypted pairing present (Keystore is not available under Robolectric, so store the blob).
    context.getSharedPreferences("vehicle_link_vault", 0).edit().putString("data", "{}").commit();
    new BridgeBootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
    Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
    assertNotNull(started);
    assertEquals(BridgeService.class.getName(), started.getComponent().getClassName());
  }
}
