package com.dautolock.app.api;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

public class SessionRecoveryTest {
  static class FakeProtocol extends CloudProtocol {
    final Queue<String> outcomes = new ArrayDeque<>();
    final List<Map<String, Object>> requests = new ArrayList<>();

    FakeProtocol(String... outcomes) {
      super(BydConfig.fromRegion("KR"));
      setSignToken("old-session");
      this.outcomes.addAll(Arrays.asList(outcomes));
    }

    @Override
    public void postTokenSecure(
        String endpoint, Map<String, Object> data, String vin, BydApiCallback<JSONObject> cb) {
      requests.add(new LinkedHashMap<>(data));
      String outcome = outcomes.isEmpty() ? "ok" : outcomes.remove();
      if (outcome.equals("expired")) {
        setSignToken(null);
        cb.onError("expired", new SessionExpiredException());
      } else if (outcome.equals("busy")) cb.onError("BYD 요청 거부 (코드 1008)", null);
      else {
        try {
          cb.onSuccess(new JSONObject().put("list", new JSONArray()).put("controlState", 1));
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    }
  }

  private CloudClient client(FakeProtocol p, AtomicInteger renewals) {
    CloudClient c = new CloudClient(p);
    c.setSessionRecovery(
        () -> {
          renewals.incrementAndGet();
          p.setSignToken("new-session");
        });
    return c;
  }

  @Test
  public void expiredReadReconnectsAndRetriesOnce() throws Exception {
    FakeProtocol p = new FakeProtocol("expired", "ok");
    AtomicInteger renewals = new AtomicInteger();
    assertEquals(0, client(p, renewals).vehicles().length());
    assertEquals(1, renewals.get());
    assertEquals(2, p.requests.size());
    assertNotEquals(p.requests.get(0).get("random"), p.requests.get(1).get("random"));
  }

  @Test
  public void repeatedExpiryDoesNotLoop() {
    FakeProtocol p = new FakeProtocol("expired", "expired");
    AtomicInteger renewals = new AtomicInteger();
    CloudClient c = client(p, renewals);
    assertThrows(CloudProtocol.SessionExpiredException.class, c::vehicles);
    assertThrows(Exception.class, c::vehicles);
    assertEquals(1, renewals.get());
    assertEquals(2, p.requests.size());
  }

  @Test
  public void serviceErrorDoesNotDiscardSessionOrRelogin() {
    FakeProtocol p = new FakeProtocol("busy");
    AtomicInteger renewals = new AtomicInteger();
    assertThrows(Exception.class, () -> client(p, renewals).vehicles());
    assertEquals(0, renewals.get());
    assertTrue(p.isLoggedIn());
    assertEquals(1, p.requests.size());
  }

  @Test
  public void commandIsNeverReplayedAfterExpiry() {
    FakeProtocol p = new FakeProtocol("expired", "ok");
    AtomicInteger renewals = new AtomicInteger();
    CloudClient c = client(p, renewals);
    Exception error =
        assertThrows(
            Exception.class,
            () -> c.command("vehicle", "pin-hash", CloudClient.Command.LOCK, () -> true));
    assertTrue(error.getMessage().contains("재전송하지 않았습니다"));
    assertEquals(1, renewals.get());
    assertEquals(1, p.requests.size());
  }

  @Test
  public void proximityIsRecheckedAfterAuthenticationBeforeCommand() {
    FakeProtocol p = new FakeProtocol();
    p.setSignToken(null);
    AtomicInteger renewals = new AtomicInteger();
    CloudClient c = client(p, renewals);
    assertThrows(
        Exception.class,
        () ->
            c.command(
                "vehicle", "pin-hash", CloudClient.Command.UNLOCK, () -> renewals.get() == 0));
    assertEquals(1, renewals.get());
    assertEquals(0, p.requests.size());
  }

  @Test
  public void resultRetryRetainsOriginalCommandSerial() throws Exception {
    FakeProtocol p = new FakeProtocol("expired", "ok");
    CloudClient c = client(p, new AtomicInteger());
    Map<String, Object> original = p.buildInnerBaseMap("vehicle", "original-serial");
    original.put("commandType", "LOCKDOOR");
    c.request("/control/remoteControlResult", original, "vehicle");
    assertEquals("original-serial", p.requests.get(1).get("requestSerial"));
    assertEquals("LOCKDOOR", p.requests.get(1).get("commandType"));
  }
}
