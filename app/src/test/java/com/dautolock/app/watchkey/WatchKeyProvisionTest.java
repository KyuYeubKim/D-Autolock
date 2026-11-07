package com.dautolock.app.watchkey;

import static org.junit.Assert.*;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WatchKeyProvisionTest {

  private WatchKeyStore store;
  private final List<String> events = new ArrayList<>();

  /** A scripted client: QR, then a status that reaches "approved", then the key. */
  static class FakeClient implements WatchKeyClient {
    int statusCalls;
    int approveAfter = 1;
    boolean failBluetooth;

    @Override
    public long serverTime() {
      return 1700000000000L;
    }

    @Override
    public QrInfo createQrCode() {
      return new QrInfo("uuid-1", "watchQRCode://watch.bydautolink?uuid=uuid-1");
    }

    @Override
    public int qrStatus(String uuid) {
      return ++statusCalls > approveAfter ? WatchKeyProvision.APPROVED_STATUS : 0;
    }

    @Override
    public WatchTokens gainToken(String uuid) {
      return new WatchTokens("sign-t", "encry-t", "id-1", "owner");
    }

    @Override
    public String gainVehicleVin(WatchTokens tokens) {
      return "LVIN0001";
    }

    @Override
    public BleKey gainBluetooth(WatchTokens tokens) {
      return failBluetooth ? new BleKey("", "", "") : new BleKey("DK", "9", "AA:BB:CC:DD:EE:01");
    }
  }

  private final WatchKeyProvision.Listener listener =
      new WatchKeyProvision.Listener() {
        @Override
        public void onStatus(String m) {
          events.add("status");
        }

        @Override
        public void onQrReady(QrInfo qr) {
          events.add("qr:" + qr.uuid);
        }

        @Override
        public void onApproved() {
          events.add("approved");
        }

        @Override
        public void onIssued(String s) {
          events.add("issued");
        }

        @Override
        public void onError(String m) {
          events.add("error:" + m);
        }
      };

  @Before
  public void setUp() {
    Context context = RuntimeEnvironment.getApplication();
    SecretKeySpec key = new SecretKeySpec(new byte[16], "AES");
    store = new WatchKeyStore(context, () -> key);
  }

  private WatchKeyProvision provision(WatchKeyClient client) {
    WatchKeyProvision p = new WatchKeyProvision(client, store, Runnable::run, (e, d) -> {});
    p.pollMs = 0;
    p.timeoutMs = 50;
    return p;
  }

  @Test
  public void runsTheWholeSequenceAndStoresTheKey() {
    provision(new FakeClient()).runBlocking(listener);
    assertTrue(events.toString(), events.contains("qr:uuid-1"));
    assertTrue(events.toString(), events.contains("approved"));
    assertTrue(events.toString(), events.contains("issued"));
    assertFalse(events.toString(), events.stream().anyMatch(e -> e.startsWith("error")));
    assertTrue(store.hasBleKey());
    assertEquals("AA:BB:CC:DD:EE:01", store.macAddress());
    assertEquals("LVIN0001", store.vin());
  }

  @Test
  public void reportsAnErrorWhenApprovalNeverComes() {
    FakeClient c = new FakeClient();
    c.approveAfter = Integer.MAX_VALUE; // Never reaches approved.
    provision(c).runBlocking(listener);
    assertTrue(events.toString(), events.stream().anyMatch(e -> e.startsWith("error")));
    assertFalse(store.hasBleKey());
  }

  @Test
  public void reportsAnErrorWhenTheBluetoothKeyIsMissing() {
    FakeClient c = new FakeClient();
    c.failBluetooth = true;
    provision(c).runBlocking(listener);
    assertTrue(events.toString(), events.stream().anyMatch(e -> e.startsWith("error")));
    assertFalse(store.hasBleKey());
  }
}
