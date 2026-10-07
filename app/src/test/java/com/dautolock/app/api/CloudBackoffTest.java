package com.dautolock.app.api;

import static org.junit.Assert.*;

import com.dautolock.app.api.SessionRecoveryTest.FakeProtocol;
import com.dautolock.app.core.DiagnosticLog;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class CloudBackoffTest {
  @Test
  public void busyWaitBlocksReadsAndControlsBeforeClaimAndSuccessResetsDelay() throws Exception {
    FakeProtocol p = new FakeProtocol("busy", "ok", "busy");
    AtomicLong now = new AtomicLong();
    CloudClient c = new CloudClient(p, now::get);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(30000, c.backoffMillis());
    AtomicInteger claims = new AtomicInteger();
    assertThrows(
        Exception.class,
        () ->
            c.command(
                "car",
                "pin",
                CloudClient.Command.UNLOCK,
                () -> {
                  claims.incrementAndGet();
                  return true;
                }));
    now.set(29999);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(0, claims.get());
    assertEquals(1, p.requests.size());
    assertTrue(p.isLoggedIn());
    now.set(30000);
    c.vehicles();
    assertEquals(0, c.backoffMillis());
    assertThrows(Exception.class, c::vehicles);
    assertEquals(30000, c.backoffMillis());
  }

  @Test
  public void repeatedBusyBacksOffAndReconnectsAtMostOncePerTenMinutes() throws Exception {
    FakeProtocol p = new FakeProtocol(Collections.nCopies(12, "busy").toArray(new String[0]));
    AtomicLong now = new AtomicLong();
    AtomicInteger reconnects = new AtomicInteger();
    CloudClient c = new CloudClient(p, now::get);
    c.setSessionRecovery(() -> reconnects.incrementAndGet());
    assertThrows(Exception.class, c::vehicles);
    now.set(30000);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(60000, c.backoffMillis());
    assertEquals(0, reconnects.get());
    now.set(90000);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(120000, c.backoffMillis());
    assertEquals(1, reconnects.get());
    for (long time = 210000; time <= 690000; time += 120000) {
      now.set(time);
      assertThrows(Exception.class, c::vehicles);
      assertEquals(120000, c.backoffMillis());
      assertEquals(1, reconnects.get());
    }
    now.set(810000);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(2, reconnects.get());
    assertEquals(9, p.requests.size());
  }

  @Test
  public void requestSerialSuccessDoesNotResetRepeatedStatusResultErrors() throws Exception {
    FakeProtocol p = new FakeProtocol("busy", "ok", "busy");
    AtomicLong now = new AtomicLong();
    CloudClient c = new CloudClient(p, now::get);
    String result = "/vehicleInfo/vehicle/vehicleRealTimeResult";
    assertThrows(Exception.class, () -> c.request(result, Collections.emptyMap(), "car"));
    now.set(30000);
    c.request("/vehicleInfo/vehicle/vehicleRealTimeRequest", Collections.emptyMap(), "car");
    assertThrows(Exception.class, () -> c.request(result, Collections.emptyMap(), "car"));
    assertEquals(60000, c.backoffMillis());
  }

  @Test
  public void busyOnIsNotReplayedButOffCleanupStillRunsOnce() throws Exception {
    FakeProtocol p = new FakeProtocol("busy", "ok");
    CloudClient c = new CloudClient(p, () -> 0);
    assertThrows(
        Exception.class, () -> c.command("car", "pin", CloudClient.Command.CLIMATE_ON, () -> true));
    c.command("car", "pin", CloudClient.Command.CLIMATE_OFF, () -> true);
    assertEquals(2, p.requests.size());
    assertEquals("OPENAIR", p.requests.get(0).get("commandType"));
    assertEquals("CLOSEAIR", p.requests.get(1).get("commandType"));
    assertEquals(0, c.backoffMillis());
  }

  @Test
  public void failedOffCleanupIsNotRepeated() {
    FakeProtocol p = new FakeProtocol("busy", "busy");
    CloudClient c = new CloudClient(p, () -> 0);
    assertThrows(
        Exception.class, () -> c.command("car", "pin", CloudClient.Command.CLIMATE_ON, () -> true));
    assertThrows(
        Exception.class,
        () -> c.command("car", "pin", CloudClient.Command.CLIMATE_OFF, () -> true));
    assertEquals(2, p.requests.size());
    assertEquals(60000, c.backoffMillis());
  }

  @Test
  public void failedFallbackReconnectWaitsAndDoesNotDiscardExistingSession() {
    FakeProtocol p = new FakeProtocol("busy", "busy", "ok");
    AtomicLong now = new AtomicLong();
    AtomicInteger reconnects = new AtomicInteger();
    CloudClient c = new CloudClient(p, now::get);
    c.setSessionRecovery(
        () -> {
          reconnects.incrementAndGet();
          throw new Exception("login denied");
        });
    assertThrows(Exception.class, c::vehicles);
    now.set(30000);
    assertThrows(Exception.class, c::vehicles);
    now.set(90000);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(120000, c.backoffMillis());
    assertThrows(Exception.class, c::vehicles);
    assertEquals(1, reconnects.get());
    assertEquals(2, p.requests.size());
    assertTrue(p.isLoggedIn());
  }

  @Test
  public void diagnosticsRetainOperationWithoutRawEndpointOrCredentials() throws Exception {
    FakeProtocol p = new FakeProtocol("ok", "busy");
    CloudClient c = new CloudClient(p, () -> 0);
    List<String> logs = new ArrayList<>();
    c.setDiagnostics((event, detail) -> logs.add(event + " " + DiagnosticLog.redact(detail)));
    c.request("/vehicleInfo/vehicle/vehicleRealTimeRequest", Collections.emptyMap(), "car");
    assertThrows(
        Exception.class,
        () ->
            c.request("/vehicleInfo/vehicle/vehicleRealTimeResult", Collections.emptyMap(), "car"));
    assertTrue(logs.get(0).contains("op=status_request"));
    assertTrue(logs.get(1).contains("op=status_result"));
    assertTrue(logs.get(2).contains("waitSeconds=30"));
    assertFalse(String.join(" ", logs).contains("[long-value]"));
    assertEquals("other", CloudClient.operation("/unknown/token-value"));
  }
}
