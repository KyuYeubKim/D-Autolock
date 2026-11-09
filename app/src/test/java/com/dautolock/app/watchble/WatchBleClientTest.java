package com.dautolock.app.watchble;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WatchBleClientTest {
  private static final String DKEY = "00112233445566778899aabbccddeeff";

  @After
  public void cleanUp() {
    BleAirtime.reset();
  }

  private WatchBleClient client(List<String> diag) {
    Application app = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
    BluetoothAdapter adapter = app.getSystemService(BluetoothManager.class).getAdapter();
    Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
    Shadows.shadowOf(adapter).setEnabled(true);
    return new WatchBleClient(app, (event, detail) -> diag.add(event + " " + detail));
  }

  private static final class Result implements WatchBleClient.Listener {
    Boolean success;
    String message;

    @Override
    public void onProgress(String message) {}

    @Override
    public void onResult(boolean success, String message, int doorStates) {
      this.success = success;
      this.message = message;
    }
  }

  @Test
  public void aConnectionThatDoesNotComeUpGivesUpAtTheCap() {
    // Real log 11-09: out of range the stack reported status 133 only after 5–7 s, while every
    // connection that worked was up within 1 s. Nothing was sent yet, so the cloud can take over.
    List<String> diag = new ArrayList<>();
    WatchBleClient client = client(diag);
    Result result = new Result();
    client.start(DKEY, "AA:BB:CC:DD:EE:FF", "1", WatchBleCodec.LOCK, result, 8000, 3000);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2900));
    assertNull(result.success);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    assertEquals(Boolean.FALSE, result.success);
    assertTrue(result.message.contains("연결 시간 초과"));
    assertFalse(client.controlSent());
    assertTrue(diag.toString().contains("connect_timeout"));
    // The session was closed, so the car-signal grace runs from now rather than for 40 s.
    long now = SystemClock.elapsedRealtime();
    assertFalse(BleAirtime.quiet(now + BleAirtime.AFTER_MS));
  }

  @Test
  public void withoutACapOnlyTheOverallTimeoutApplies() {
    List<String> diag = new ArrayList<>();
    WatchBleClient client = client(diag);
    Result result = new Result();
    client.start(DKEY, "AA:BB:CC:DD:EE:FF", "1", WatchBleCodec.LOCK, result, 8000);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7900));
    assertNull(result.success);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    assertEquals(Boolean.FALSE, result.success);
    assertFalse(diag.toString().contains("connect_timeout"));
  }
}
