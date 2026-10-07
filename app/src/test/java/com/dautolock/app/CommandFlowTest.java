package com.dautolock.app;

import static org.junit.Assert.*;

import android.content.Context;
import com.dautolock.app.api.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CommandFlowTest {
  static class Protocol extends CloudProtocol {
    boolean locked;
    int power = 1;
    Object epb = "--";
    final List<String> commands = new ArrayList<>();

    Protocol() {
      super(BydConfig.fromRegion("KR"));
      setSignToken("test-session");
    }

    @Override
    public void postTokenSecure(
        String endpoint, Map<String, Object> data, String vin, BydApiCallback<JSONObject> cb) {
      try {
        if (endpoint.endsWith("vehicleRealTimeRequest"))
          cb.onSuccess(new JSONObject().put("requestSerial", "status"));
        else if (endpoint.endsWith("vehicleRealTimeResult")) {
          JSONObject s =
              new JSONObject()
                  .put("time", System.currentTimeMillis())
                  .put("speed", 0)
                  .put("powerGear", power)
                  .put("epb", epb);
          for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"}) {
            s.put(side + "Door", 0).put(side + "DoorLock", locked ? 2 : 1).put(side + "Window", 1);
          }
          cb.onSuccess(s);
        } else if (endpoint.endsWith("remoteControl")) {
          String command = String.valueOf(data.get("commandType"));
          commands.add(command);
          if (command.equals("LOCKDOOR")) locked = true;
          if (command.equals("OPENDOOR")) locked = false;
          if (command.equals("TURNOFFENGINE")) power = 1;
          cb.onSuccess(new JSONObject().put("controlState", 1));
        } else throw new AssertionError("Unexpected endpoint " + endpoint);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  private Controller create(Protocol protocol) throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Controller c =
        new Controller(
            context,
            new SecureStore(context, () -> new SecretKeySpec(new byte[32], "AES")),
            () -> new CloudClient(protocol));
    AccountPersistenceTest.await(c);
    c.vin = "test-car";
    c.pinHash = "test-pin";
    c.capabilities =
        new JSONObject()
            .put(
                "items",
                new JSONArray()
                    .put(new JSONObject().put("functionNo", "1005"))
                    .put(new JSONObject().put("functionNo", "1006"))
                    .put(new JSONObject().put("functionNo", "1001"))
                    .put(new JSONObject().put("functionNo", "1026"))
                    .put(new JSONObject().put("functionNo", "1031")));
    return c;
  }

  private void complete(Controller c) throws Exception {
    long limit = System.currentTimeMillis() + 20000;
    while (c.busy() && System.currentTimeMillis() < limit) Thread.sleep(20);
    assertFalse(c.busy());
  }

  @Test
  public void manualLockSendsCloseWindowsEvenIfCloudAlreadyReportsClosed() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
    assertTrue(c.windowsStatus.contains("요청 완료"));
  }

  @Test
  public void automaticLockClosesWindowsThenStopsOnlyWithKnownBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    c.monitoring = true;
    c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW", "TURNOFFENGINE"), p.commands);
    assertTrue(c.stopStatus.contains("전원 OFF 확인"));
  }

  @Test
  public void alreadyLockedOnVehicleWithUnavailableBrakeNeverReceivesStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.locked = true;
    Controller c = create(p);
    c.settings.edit().putBoolean("closeWindows", false).commit();
    c.monitoring = true;
    c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.stopStatus.contains("주차브레이크"));
  }

  @Test
  public void unavailableEpbOffVehicleReceivesOneClimateOnOffPair() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.manualPulse();
    complete(c);
    assertEquals(Arrays.asList("OPENAIR", "CLOSEAIR"), p.commands);
    assertTrue(c.climateStatus.contains("ON/OFF 응답 완료"));
  }
}
