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
    /** Whether each request went out declaring this account a guest of its car. */
    final List<Boolean> sentAsGuest = new ArrayList<>();

    FakeProtocol(String... outcomes) {
      super(BydConfig.fromRegion("KR"));
      setSignToken("old-session");
      this.outcomes.addAll(Arrays.asList(outcomes));
    }

    /** Answers at once: no need to wait out BYD's 1.5 s between request and result. */
    @Override
    protected long pollIntervalMs() {
      return 10;
    }

    @Override
    public void postTokenSecure(
        String endpoint, Map<String, Object> data, String vin, BydApiCallback<JSONObject> cb) {
      requests.add(new LinkedHashMap<>(data));
      boolean asGuest = Boolean.TRUE.equals(guest(vin));
      sentAsGuest.add(asGuest);
      String outcome = outcomes.isEmpty() ? "ok" : outcomes.remove();
      // The cloud since 2026-11-12: a guest must declare itself (1001), an owner must not (8003).
      String refusal =
          outcome.equals("guestOnly") && !asGuest
              ? "1001"
              : outcome.equals("ownerOnly") && asGuest
                  ? "8003"
                  : outcome.equals("reject1001") ? "1001" : null;
      if (refusal != null) {
        RejectedException error = new RejectedException(refusal, "refused");
        cb.onError(error.getMessage(), error);
        return;
      }
      if (outcome.equals("expired")) {
        setSignToken(null);
        cb.onError("expired", new SessionExpiredException());
      } else if (outcome.startsWith("http")) {
        HttpStatusException error = new HttpStatusException(Integer.parseInt(outcome.substring(4)));
        cb.onError(error.getMessage(), error);
      } else if (outcome.equals("busy"))
        cb.onError("BYD 요청 거부 (코드 1008)", new ServiceBusyException());
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
  public void aGatewayErrorOnAReadIsAskedOnceMore() throws Exception {
    // Real log 11-11 12:27: a status read got HTTP 502; the next read 30 s later was fine.
    FakeProtocol p = new FakeProtocol("http502", "ok");
    assertEquals(0, client(p, new AtomicInteger()).vehicles().length());
    assertEquals(2, p.requests.size());
    assertNotEquals(p.requests.get(0).get("random"), p.requests.get(1).get("random"));
  }

  @Test
  public void aSecondGatewayErrorOrAClientErrorIsReported() {
    FakeProtocol p = new FakeProtocol("http503", "http503", "ok");
    CloudClient c = client(p, new AtomicInteger());
    Exception error = assertThrows(Exception.class, c::vehicles);
    assertTrue(error.getMessage(), error.getMessage().contains("HTTP 503"));
    assertEquals(2, p.requests.size()); // Once more, not again and again.
    FakeProtocol q = new FakeProtocol("http404", "ok");
    assertThrows(Exception.class, () -> client(q, new AtomicInteger()).vehicles());
    assertEquals(1, q.requests.size()); // Not a gateway failure: not asked again.
  }

  @Test
  public void aControlIsNeverResentAfterAGatewayError() {
    FakeProtocol p = new FakeProtocol("http502", "ok");
    CloudClient c = client(p, new AtomicInteger());
    Exception error =
        assertThrows(
            Exception.class,
            () -> c.command("vehicle", "pin-hash", CloudClient.Command.LOCK, () -> true));
    assertTrue(error.getMessage(), error.getMessage().contains("HTTP 502"));
    assertEquals(1, p.requests.size());
  }

  private static final String REALTIME = "/vehicleInfo/vehicle/vehicleRealTimeRequest";

  @Test
  public void aSharedCarsReadsDeclareTheGuestAfterA1001AndKeepDoingSo() throws Exception {
    // Real log 11-18 07:02: status reads refused with 1001 while the official app worked.
    FakeProtocol p = new FakeProtocol("guestOnly", "guestOnly", "guestOnly");
    AtomicInteger saved = new AtomicInteger();
    CloudClient c = client(p, new AtomicInteger());
    c.setSessionChanged(saved::incrementAndGet);
    c.request(REALTIME, p.buildInnerBaseMap("VIN1", null), "VIN1");
    assertEquals(Arrays.asList(false, true), p.sentAsGuest);
    assertEquals(Boolean.TRUE, p.guest("VIN1"));
    assertEquals(1, saved.get()); // Remembered with the session.
    c.request(REALTIME, p.buildInnerBaseMap("VIN1", null), "VIN1");
    assertEquals(Arrays.asList(false, true, true), p.sentAsGuest); // Right the first time now.
  }

  @Test
  public void anOwnerToldItIsNotAGuestDropsTheFlag() throws Exception {
    FakeProtocol p = new FakeProtocol("ownerOnly", "ownerOnly");
    p.markGuest("VIN1", true); // A wrong guess.
    client(p, new AtomicInteger()).request(REALTIME, p.buildInnerBaseMap("VIN1", null), "VIN1");
    assertEquals(Arrays.asList(true, false), p.sentAsGuest);
    assertEquals(Boolean.FALSE, p.guest("VIN1"));
  }

  @Test
  public void aControlRefusedFor1001IsNotSentAgain() {
    FakeProtocol p = new FakeProtocol("guestOnly", "ok");
    CloudClient c = client(p, new AtomicInteger());
    Exception error =
        assertThrows(
            Exception.class,
            () -> c.command("VIN1", "pin-hash", CloudClient.Command.LOCK, () -> true));
    assertTrue(error.getMessage(), error.getMessage().contains("다시 보내지 않았"));
    assertEquals(1, p.requests.size());
    assertEquals(Boolean.TRUE, p.guest("VIN1")); // The next press carries the flag.
  }

  @Test
  public void otherEndpointsAndRealRefusalsAreNotAskedAgain() {
    FakeProtocol p = new FakeProtocol("guestOnly");
    assertThrows(Exception.class, () -> client(p, new AtomicInteger()).vehicles());
    assertEquals(1, p.requests.size()); // The vehicle list does not reach the car.
    assertNull(p.guest(null));
    FakeProtocol q = new FakeProtocol("reject1001", "reject1001", "ok");
    CloudClient c = client(q, new AtomicInteger());
    Exception error =
        assertThrows(
            Exception.class, () -> c.request(REALTIME, q.buildInnerBaseMap("VIN1", null), "VIN1"));
    assertTrue(error.getMessage(), error.getMessage().contains("1001"));
    assertEquals(2, q.requests.size()); // As owner, then as guest; then the refusal is reported.
  }

  @Test
  public void aGuestsCarRequestCarriesASignedUserTypeKeptAcrossARestart() throws Exception {
    CloudProtocol p = new CloudProtocol(BydConfig.fromRegion("KR"));
    p.restoreSession(
        new JSONObject()
            .put("userId", "test-user")
            .put("signToken", "test-sign")
            .put("encryToken", "test-encrypt")
            .put("imeiMD5", "00000000000000000000000000000000"));
    Map<String, Object> inner = p.buildInnerBaseMap("VIN1", null);
    Map<String, Object> owner = p.tokenEnvelope(REALTIME, inner, "VIN1", "1000");
    assertFalse(owner.containsKey("userType"));
    p.markGuest("VIN1", true);
    Map<String, Object> guest = p.tokenEnvelope(REALTIME, inner, "VIN1", "1000");
    assertEquals(CloudProtocol.USER_TYPE_GUEST, guest.get("userType"));
    assertNotEquals(owner.get("sign"), guest.get("sign")); // Signed, not just added.
    assertFalse(
        p.tokenEnvelope("/app/account/getAllListByUserId", inner, "VIN1", "1000")
            .containsKey("userType"));
    CloudProtocol restarted = new CloudProtocol(BydConfig.fromRegion("KR"));
    restarted.restoreSession(p.exportSession());
    assertEquals(Boolean.TRUE, restarted.guest("VIN1"));
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
