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
  @Test
  public void savingSensitivityRefreshesAutomaticallyAndReusesNewerReadback() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.settings.edit().putBoolean("autoStart", false).commit();
    c.thresholds(-65, -80, 1, 2, 10);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(750));
    complete(c);
    assertEquals(1, p.statusRequests);
    assertNotNull(c.snapshot);
    c.refreshStatusWhenNeeded();
    assertEquals(1, p.statusRequests);
    assertTrue(p.commands.isEmpty());
  }

  @Test
  public void refreshQueuedDuringCommandUsesItsReadbackInsteadOfSendingAnotherQuery()
      throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    c.requestStatusRefresh("settings");
    complete(c);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(1));
    assertFalse(c.busy());
    assertEquals(2, p.statusRequests);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
  }

  @Test
  public void statusRefreshInProgressCanSupplyAutomaticUnlockPreflight() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.requestStatusRefresh("foreground");
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(750));
    complete(c);
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(2, p.statusRequests);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
  }

  @Test
  public void manualRefreshReadsDespiteFreshStateKeepsSpacingAndNeverSendsCommands()
      throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1);
    p.beforeStatus =
        () -> {
          inside.countDown();
          try {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        };
    c.refreshNow();
    assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(c.statusReading);
    c.refreshNow(); // Already reading: no second queued request.
    release.countDown();
    p.beforeStatus = () -> {};
    completeRead(c);
    assertEquals(1, p.statusRequests);
    assertEquals("차량 상태 새로고침 완료", c.message);
    assertTrue(c.snapshot.fresh(System.currentTimeMillis()));
    c.refreshNow(); // Inside the 5-second read spacing.
    assertEquals(1, p.statusRequests);
    assertTrue(c.message.contains("초 후 다시 새로고침"));
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
    c.refreshNow(); // A fresh snapshot does not suppress an explicit refresh.
    completeRead(c);
    assertEquals(2, p.statusRequests);
    assertTrue(p.commands.isEmpty());
  }

  private void completeRead(Controller c) throws Exception {
    long limit = System.currentTimeMillis() + 20000;
    while ((c.busy() || c.statusReading) && System.currentTimeMillis() < limit) Thread.sleep(20);
    assertFalse(c.busy());
    assertFalse(c.statusReading);
  }

  static class Protocol extends CloudProtocol {
    boolean locked;
    int power = 1;
    double speed = 0;
    Integer chargeState, fullHour, fullMinute;
    Double soc;
    Object epb = "--";
    boolean doorOpen, openOnUnlock, rejectClimate, rejectHvac, windowOpen, climateOffPowersDown;
    Object okLight = JSONObject.NULL;
    int statusRequests, hvacRequests, configRequests;
    /** Status reads after the first command that come back without the door-lock fields. */
    int lockGapReads;
    /** Added to the measurement time of status reads (negative: an old reading). */
    long measuredShiftMs;
    Runnable beforeStatus = () -> {};
    final List<String> commands = new ArrayList<>();

    Protocol() {
      super(BydConfig.fromRegion("KR"));
      setSignToken("test-session");
    }

    /** Answers at once: no need to wait out BYD's 1.5 s between request and result. */
    @Override
    protected long pollIntervalMs() {
      return 10;
    }

    @Override
    public void postTokenSecure(
        String endpoint, Map<String, Object> data, String vin, BydApiCallback<JSONObject> cb) {
      try {
        if (endpoint.endsWith("vehicleRealTimeRequest")) {
          statusRequests++;
          cb.onSuccess(new JSONObject().put("requestSerial", "status"));
        } else if (endpoint.endsWith("getStatusNow")) {
          hvacRequests++;
          if (rejectHvac) {
            cb.onError("이 차량에서 공조 조회 미지원", new Exception("unsupported"));
            return;
          }
          cb.onSuccess(new JSONObject().put("status", 1).put("time", System.currentTimeMillis()));
        } else if (endpoint.endsWith("vehicleRealTimeResult")) {
          beforeStatus.run();
          JSONObject s =
              new JSONObject()
                  .put("time", System.currentTimeMillis() + measuredShiftMs)
                  .put("speed", speed)
                  .put("powerGear", power)
                  .put("okLight", okLight)
                  .put("epb", epb);
          if (chargeState != null) s.put("chargeState", chargeState);
          if (fullHour != null) s.put("fullHour", fullHour).put("fullMinute", fullMinute);
          if (soc != null) s.put("soc", soc);
          boolean lockGap = !commands.isEmpty() && lockGapReads > 0;
          if (lockGap) lockGapReads--;
          for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"}) {
            s.put(side + "Door", doorOpen ? 1 : 0)
                .put(side + "Window", windowOpen && side.equals("leftFront") ? 2 : 1);
            if (!lockGap) s.put(side + "DoorLock", locked ? 2 : 1);
          }
          cb.onSuccess(s);
        } else if (endpoint.endsWith("getLatestConfig")) {
          configRequests++;
          JSONArray items = new JSONArray();
          for (String f : new String[] {"1005", "1006", "1001", "1026", "1031"})
            items.put(new JSONObject().put("functionNo", f));
          cb.onSuccess(new JSONObject().put("test-car", new JSONObject().put("items", items)));
        } else if (endpoint.endsWith("remoteControl")) {
          String command = String.valueOf(data.get("commandType"));
          commands.add(command);
          if (command.equals("LOCKDOOR")) locked = true;
          if (command.equals("OPENDOOR")) {
            locked = false;
            if (openOnUnlock) doorOpen = true;
          }
          if (command.equals("OPENAIR")) {
            power = 3;
            if (rejectClimate) {
              cb.onError("결과 미확인", new Exception("uncertain"));
              return;
            }
          }
          if (command.equals("TURNOFFENGINE")) power = 1;
          if (command.equals("CLOSEAIR") && climateOffPowersDown) power = 1;
          if (command.equals("CLOSEWINDOW")) windowOpen = false;
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

  private static String log(Controller c) throws Exception {
    java.util.concurrent.CompletableFuture<String> text = new java.util.concurrent.CompletableFuture<>();
    c.diagnostics.snapshot(text::complete);
    return text.get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  private void complete(Controller c) throws Exception {
    long limit = System.currentTimeMillis() + 20000;
    while (c.busy() && System.currentTimeMillis() < limit) Thread.sleep(20);
    assertFalse(c.busy());
  }

  // --- Stage C: the watch BLE key as a fast local actuator (no real BLE in tests) ----------------
  static final class FakeBle implements Controller.WatchBleSender {
    boolean ready = true;
    boolean succeed = true;
    /** A failure after the control frame went out (no answer), not before it (no connection). */
    boolean controlSent = false;
    /** What the car does on receiving the frame (e.g. it locks even though no answer arrives). */
    Runnable effect = () -> {};
    int calls = 0;
    byte lastCode = 0;
    /** The hold asked for by the last sendAndHold (-1: none), and the last releaseHold reason. */
    long holdMs = -1;
    int releaseBelow;
    String released;

    @Override
    public boolean ready() {
      return ready;
    }

    @Override
    public com.dautolock.app.watchkey.WatchKey.BleResult sendAndHold(
        byte controlByte, long timeoutMs, long holdMs, int releaseBelowRssi) {
      this.holdMs = holdMs;
      this.releaseBelow = releaseBelowRssi;
      return send(controlByte, timeoutMs);
    }

    @Override
    public void releaseHold(String reason) {
      released = reason;
    }

    @Override
    public com.dautolock.app.watchkey.WatchKey.BleResult send(byte controlByte, long timeoutMs) {
      calls++;
      lastCode = controlByte;
      effect.run();
      return new com.dautolock.app.watchkey.WatchKey.BleResult(
          succeed, succeed ? "ok" : "fail", succeed ? 255 : -1, succeed || controlSent);
    }
  }

  @Test
  public void watchBleCodeMapsOnlyTheDoors() {
    assertEquals(
        Byte.valueOf(com.dautolock.app.watchble.WatchBleCodec.UNLOCK),
        Controller.watchBleCode(CloudClient.Command.UNLOCK));
    assertEquals(
        Byte.valueOf(com.dautolock.app.watchble.WatchBleCodec.LOCK),
        Controller.watchBleCode(CloudClient.Command.LOCK));
    // The car never answered START_CLIMATE over the key (real log 11-08): climate stays on the cloud.
    assertNull(Controller.watchBleCode(CloudClient.Command.CLIMATE_ON));
    assertNull(Controller.watchBleCode(CloudClient.Command.CLIMATE_OFF));
    assertNull(Controller.watchBleCode(CloudClient.Command.STOP));
    assertNull(Controller.watchBleCode(CloudClient.Command.CLOSE_WINDOWS));
    assertNull(Controller.watchBleCode(CloudClient.Command.FIND_CAR));
  }

  @Test
  public void manualUnlockPrefersTheWatchBleKeyAndSkipsTheCloud() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    c.ble = fake;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertEquals(com.dautolock.app.watchble.WatchBleCodec.UNLOCK, fake.lastCode);
    assertTrue("cloud must not be used when the BLE key sends it", p.commands.isEmpty());
  }

  @Test
  public void watchBleFailureFallsBackToTheCloud() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    fake.succeed = false;
    c.ble = fake;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
  }

  @Test
  public void automaticLockClaimsOnceThenFallsBackToCloudWhenBleFails() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    bridgeParked(c); // Live P present but not this phone's trip: it locks and never stops.
    c.monitoring = c.autoEnabled = true;
    FakeBle fake = new FakeBle();
    fake.succeed = false; // BLE fails → the cloud must still send, with the claim consumed once.
    c.ble = fake;
    int[] claims = {0};
    c.automaticCommand(
        CloudClient.Command.LOCK,
        () -> true,
        () -> {
          claims[0]++;
          return claims[0] == 1;
        },
        () -> {},
        () -> {},
        true);
    complete(c);
    runDelayedStop(c, true);
    assertEquals(1, fake.calls);
    assertEquals("the engine claim must be consumed exactly once", 1, claims[0]);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
  }

  @Test
  public void alreadyOpenCarMarksTheEngineEvenIfTheSignalWobbledDuringTheRead() throws Exception {
    // Real log 11-09 10:40: the beacon's unlock found the car already open, but the signal had
    // dipped between the thresholds during the read, so valid() was false and the engine was never
    // told; back near, it reopened the car after the car's own 30 s relock.
    Protocol p = new Protocol();
    p.locked = false;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    int[] done = {0};
    boolean[] inZone = {true};
    p.beforeStatus = () -> inZone[0] = false; // Dipped out of the zone while the state was read.
    c.automaticCommand(
        CloudClient.Command.UNLOCK, () -> inZone[0], () -> true, () -> done[0]++, () -> {}, true);
    complete(c);
    assertEquals(1, done[0]);
    assertTrue(p.commands.isEmpty());
  }

  @Test
  public void automaticLockFallsBackToTheCloudWithTheEngineProposalAlreadyClaimed()
      throws Exception {
    // Real log 11-08 22:20: walking away, the BLE key was out of range (status=133) and the cloud
    // fallback was cancelled: it re-asked the engine for a proposal that the BLE attempt had already
    // claimed. The car stayed open until locked by hand.
    Protocol p = new Protocol();
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    FakeBle fake = new FakeBle();
    fake.succeed = false;
    c.ble = fake;
    com.dautolock.app.core.ProximityEngine e =
        new com.dautolock.app.core.ProximityEngine(-75, -85, 0, 2000, 5000);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(60)); // A clock well past zero for the samples below.
    long t0 = android.os.SystemClock.elapsedRealtime() - 20000;
    for (long t = t0; t <= t0 + 3000; t += 1000) e.sample(-50, t);
    e.alreadySatisfied(com.dautolock.app.core.ProximityEngine.Action.UNLOCK, t0 + 3000);
    // The phone left: no car sample for 17 s, so the engine proposes a loss lock.
    com.dautolock.app.core.ProximityEngine.Action lock =
        com.dautolock.app.core.ProximityEngine.Action.LOCK;
    assertEquals(lock, e.pending(android.os.SystemClock.elapsedRealtime()));
    c.automaticCommand(
        CloudClient.Command.LOCK,
        () -> e.canDispatch(lock, android.os.SystemClock.elapsedRealtime()),
        () -> e.claim(lock, android.os.SystemClock.elapsedRealtime()),
        () -> {},
        () -> {},
        true);
    complete(c);
    assertEquals(1, fake.calls);
    assertTrue(p.commands.contains("LOCKDOOR"));
    assertTrue(p.locked);
  }

  @Test
  public void unansweredBleLockThatTookEffectIsNotSentAgain() throws Exception {
    // The control frame went out but no answer came (log 11-08 19:13 "시간 초과 step=CONTROL", the
    // car read locked a minute later). Read first; a car already locked gets no second command.
    Protocol p = new Protocol();
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    fake.succeed = false;
    fake.controlSent = true;
    fake.effect = () -> p.locked = true;
    c.ble = fake;
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertFalse("no resend of a command the car carried out", p.commands.contains("LOCKDOOR"));
    assertTrue(log(c).contains("WATCHBLE_UNCONFIRMED | LOCK applied=true"));
  }

  @Test
  public void unansweredBleUnlockThatDidNotTakeEffectFallsBackToTheCloud() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    fake.succeed = false;
    fake.controlSent = true; // Sent, unanswered, and the car is still locked afterwards.
    c.ble = fake;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
    assertTrue(log(c).contains("WATCHBLE_UNCONFIRMED | UNLOCK applied=false"));
  }

  @Test
  public void climateStartGoesStraightToTheCloudEvenWithTheBleKeyReady() throws Exception {
    // Real log 11-08: three boarding climate starts over the key, three unanswered START_CLIMATE
    // frames, and the climate never came on.
    Protocol p = new Protocol();
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    c.ble = fake;
    c.manualClimateStart();
    complete(c);
    assertEquals(0, fake.calls);
    assertEquals(Collections.singletonList("OPENAIR"), p.commands);
    c.stop();
  }

  private static final com.dautolock.link.LinkProtocol.Sample PARK =
      new com.dautolock.link.LinkProtocol.Sample(0, -1, 1, 3, -1, 0);
  private static final com.dautolock.link.LinkProtocol.Sample DRIVE =
      new com.dautolock.link.LinkProtocol.Sample(3, -1, 4, 2, -1, 0);

  /** This phone opened the car and its own Bridge then saw the car leave P. */
  private void ownTrip(Controller c) {
    c.startTrip("test");
    c.vehicleSample(DRIVE);
  }

  private void feedPark(Controller c) {
    long now = android.os.SystemClock.elapsedRealtime();
    for (long offset : new long[] {2000, 1000, 0})
      c.vehicleLink.state.accept(PARK, now - offset, now - offset);
  }

  /** Wait out the 15 s default delay; optionally keep the Bridge reporting P meanwhile. */
  private void runDelayedStop(Controller c, boolean parked) throws Exception {
    org.robolectric.shadows.ShadowLooper main =
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
    main.idleFor(java.time.Duration.ofSeconds(14));
    if (parked) feedPark(c);
    main.idleFor(java.time.Duration.ofSeconds(2));
    complete(c);
  }

  private void bridgeParked(Controller c) throws Exception {
    c.settings.edit().putBoolean("vehicleLinkRequired", true).commit();
    java.lang.reflect.Field pair = VehicleLink.class.getDeclaredField("pairing");
    pair.setAccessible(true);
    pair.set(c.vehicleLink, com.dautolock.link.Pairing.create());
    java.lang.reflect.Field vehicle = VehicleLink.class.getDeclaredField("vehicle");
    vehicle.setAccessible(true);
    vehicle.set(c.vehicleLink, c.vin);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3));
    long now = android.os.SystemClock.elapsedRealtime();
    com.dautolock.link.LinkProtocol.Sample sample =
        new com.dautolock.link.LinkProtocol.Sample(0, -1, 1, 3, -1, 0);
    for (long offset : new long[] {2000, 1000, 0})
      c.vehicleLink.state.accept(sample, now - offset, now - offset);
  }

  @Test
  public void parkUnlockPreservesDrivenTripSoDepartureCanStillStop() throws Exception {
    Protocol p = new Protocol();
    p.locked = true; // The car auto-locked its doors while driving (normal BYD behaviour).
    Controller c = create(p);
    c.settings.edit().putBoolean("unlockOnPark", true).commit();
    ownTrip(c); // This phone opened the car and drove it: an owned, driven trip.
    assertNull(c.ownershipBlock());
    // A real park: the Bridge reports a stable P after driving, which triggers the park-unlock.
    c.vehicleSample(PARK);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3));
    c.vehicleSample(PARK);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    complete(c);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands); // It unlocked.
    // The driven trip must survive the park-unlock, so a later departure lock still auto-Stops.
    assertNull(c.ownershipBlock());
  }

  /** Two trusted P samples 3 s apart: the park-unlock trigger fires (the drive came before). */
  private void parkNow(Controller c) {
    c.vehicleSample(PARK);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3));
    c.vehicleSample(PARK);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  }

  @Test
  public void anOldUnlockedReadingDoesNotSkipTheParkUnlock() throws Exception {
    // Real log 11-10 21:34: skipped as "already unlocked" on an 11-minute-old reading, although the
    // car locks itself once it drives off.
    Protocol p = new Protocol(); // Unlocked when last read, two minutes ago.
    Controller c = create(p);
    c.settings.edit().putBoolean("unlockOnPark", true).commit();
    p.measuredShiftMs = -120000;
    c.refreshNow();
    completeRead(c);
    assertEquals(Boolean.FALSE, c.snapshot.locked);
    p.measuredShiftMs = 0;
    p.locked = true; // Locked itself while driving; nothing read since.
    ownTrip(c);
    parkNow(c);
    complete(c);
    assertEquals(log(c), Collections.singletonList("OPENDOOR"), p.commands);
    assertFalse(log(c).contains("reason=already_unlocked"));
  }

  @Test
  public void aBusyWorkerDelaysTheParkUnlockInsteadOfDroppingIt() throws Exception {
    // Real log 11-10 21:23: P came while the climate READY watch read was running; the unlock was
    // dropped (the car happened to open itself at power-off).
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.settings.edit().putBoolean("unlockOnPark", true).commit();
    ownTrip(c);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    p.beforeStatus =
        () -> {
          try {
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
        };
    c.refreshNow(); // Another read holds the worker.
    long limit = System.currentTimeMillis() + 5000;
    while (!c.busy() && System.currentTimeMillis() < limit) Thread.sleep(10);
    assertTrue(c.busy());
    parkNow(c);
    assertTrue(log(c).contains("PARK_UNLOCK_WAIT"));
    p.beforeStatus = () -> {};
    release.countDown();
    completeRead(c);
    assertTrue(p.commands.isEmpty());
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(4));
    complete(c);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
  }

  @Test
  public void parkUnlockOfAnAlreadyOpenCarKeepsTheDrivenTrip() throws Exception {
    // Nothing to send, but the "already open" path used to restart the trip — clearing the drive so
    // the departure lock afterwards would skip the auto-Stop.
    Protocol p = new Protocol(); // Open, power OFF.
    Controller c = create(p);
    c.settings.edit().putBoolean("unlockOnPark", true).commit();
    ownTrip(c);
    assertNull(c.ownershipBlock());
    parkNow(c);
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(log(c).contains("CONTROL_SKIP_STATE"));
    assertNull(c.ownershipBlock());
  }

  @Test
  public void parkUnlockIsDroppedOnceTheCarDrivesOffAgain() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.settings.edit().putBoolean("unlockOnPark", true).commit();
    ownTrip(c);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    p.beforeStatus =
        () -> {
          try {
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
        };
    c.refreshNow();
    long limit = System.currentTimeMillis() + 5000;
    while (!c.busy() && System.currentTimeMillis() < limit) Thread.sleep(10);
    parkNow(c); // Waits for the worker...
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
    c.vehicleSample(DRIVE); // ...and the driver pulls away meanwhile.
    p.beforeStatus = () -> {};
    release.countDown();
    completeRead(c);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(4));
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(log(c), log(c).contains("reason=moved_again"));
  }

  @Test
  public void theCarAudioDroppingStartsThePowerOffGrace() throws Exception {
    // Real log 11-10 21:29–21:34: no read during the drive, so the switch-off went unseen and the
    // lost-signal lock started 5 s later; the car's audio dropping marks that moment for free.
    Controller c = create(new Protocol());
    bridgeParked(c); // The head unit is known from the 차량 보조 앱 pairing.
    carAudio(c, HEAD_UNIT, true);
    assertFalse(c.ignitionOffRecently());
    carAudio(c, HEAD_UNIT, false);
    assertTrue(c.ignitionOffRecently());
    assertEquals("car_audio", c.ignitionOffSource());
    assertTrue(log(c).contains("IGNITION_OFF"));
  }

  @Test
  public void aSilentKeyModuleSendsStraightThroughTheCloud() throws Exception {
    // Real log 11-10 21:34: the key module had been silent for 5 minutes (another app held it); the
    // BLE attempt got a 34 ms "connection", no answer, and cost 6 s before the cloud.
    com.dautolock.app.watchble.BleAirtime.reset(); // No key session of ours from another test.
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    c.ble = fake;
    c.keyWatchStarted(android.os.SystemClock.elapsedRealtime());
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61));
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(0, fake.calls);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
    assertTrue(log(c).contains("reason=no_advert"));
    // It advertises again: the key is used again.
    c.keyAdvertSeen(android.os.SystemClock.elapsedRealtime());
    p.commands.clear();
    p.locked = true;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertTrue(p.commands.isEmpty());
    c.keyWatchStopped();
  }

  @Test
  public void manualUnlockOfAnOpenSwitchedOffCarAuthorizesTheStartOverTheKey() throws Exception {
    // Real logs 11-10 21:29 and 11-11 07:25: the car was open but would not start; our 도어 열기
    // sent nothing ("이미 잠금 해제") and only the official app's unlock let it start.
    Protocol p = new Protocol(); // Open, power OFF.
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    c.ble = fake;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertEquals(com.dautolock.app.watchble.WatchBleCodec.UNLOCK, fake.lastCode);
    assertEquals(Controller.START_AUTH_HOLD_MS, fake.holdMs); // The key stays connected meanwhile.
    assertEquals(Controller.DEFAULT_FAR, fake.releaseBelow);
    assertTrue(p.commands.isEmpty()); // No cloud command: the car stays as it was, open.
    assertTrue(log(c).contains("purpose=start_auth"));
    assertTrue(c.lastControl, c.lastControl.contains("시동 인증"));
  }

  @Test
  public void startAuthorizationIsOnlyForAManualUnlockOfASwitchedOffCar() throws Exception {
    Protocol p = new Protocol();
    p.power = 3; // Already on: nothing to authorize.
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    c.ble = fake;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(0, fake.calls);
    p.power = 1;
    c.monitoring = c.autoEnabled = true;
    assertTrue( // The automation never sends to an open car.
        c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {}));
    complete(c);
    assertEquals(0, fake.calls);
    c.settings.edit().putBoolean("unlockOnPark", true).commit();
    ownTrip(c);
    parkNow(c); // Nor the park unlock.
    complete(c);
    assertEquals(0, fake.calls);
    assertTrue(p.commands.isEmpty());
  }

  @Test
  public void aFailedStartAuthorizationSaysSoAndSendsNothingElse() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    fake.succeed = false;
    c.ble = fake;
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(1, fake.calls);
    assertTrue(p.commands.isEmpty());
    assertTrue(log(c).contains("START_AUTH_FAIL"));
    assertTrue(c.lastControl, c.lastControl.contains("실패"));
  }

  @Test
  public void aKeyHeldByAnotherAppIsLeftAlone() throws Exception {
    // The official app holding the module (CAR_KEY_SILENT linked=yes): that key is present already.
    com.dautolock.app.watchble.BleAirtime.reset();
    Protocol p = new Protocol();
    Controller c = create(p);
    FakeBle fake = new FakeBle();
    c.ble = fake;
    c.keyWatchStarted(android.os.SystemClock.elapsedRealtime());
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61));
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(0, fake.calls);
    assertTrue(log(c).contains("START_AUTH_SKIP"));
    assertTrue(log(c).contains("CONTROL_SKIP_STATE"));
    c.keyWatchStopped();
  }

  @Test
  public void theCarComingOnReleasesTheHeldKey() throws Exception {
    Controller c = create(new Protocol());
    FakeBle fake = new FakeBle();
    c.ble = fake;
    bridgeParked(c);
    carAudio(c, HEAD_UNIT, true);
    assertEquals("car_on", fake.released);
  }

  @Test
  public void keySilenceIsMeasuredOnlyWhileTheScannerWatchesForIt() throws Exception {
    com.dautolock.app.watchble.BleAirtime.reset();
    Controller c = create(new Protocol());
    long now = android.os.SystemClock.elapsedRealtime();
    assertEquals(-1, c.keySilentMs(now)); // Not watching: unknown, never a reason to skip the key.
    c.keyWatchStarted(now);
    assertEquals(0, c.keySilentMs(now));
    long later = now + Controller.KEY_SILENT_SKIP_MS + 1000;
    assertTrue(c.keySilentMs(later) >= Controller.KEY_SILENT_SKIP_MS);
    assertTrue(c.firstKeySilentReport());
    assertFalse(c.firstKeySilentReport()); // One diagnostic line per silent stretch.
    c.keyAdvertSeen(later);
    assertEquals(0, c.keySilentMs(later));
    assertTrue(c.firstKeySilentReport()); // A new stretch may report again.
    c.keyWatchStopped();
    assertEquals(-1, c.keySilentMs(later));
  }

  @Test
  public void savingDiagnosticsExportsThenClearsForAFreshStart() throws Exception {
    Controller c = create(new Protocol());
    c.diagnostics.record("TEST_EVENT_MARKER", "x");
    java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
    android.net.Uri uri = android.net.Uri.parse("content://test/diag.txt");
    org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
        .registerOutputStream(uri, sink);
    c.exportDiagnostics(uri);
    // The export and the follow-up clear both run on the diagnostics writer thread; poll a fresh
    // snapshot until the old entry is gone.
    String after = "";
    long limit = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < limit) {
      java.util.concurrent.CompletableFuture<String> snap = new java.util.concurrent.CompletableFuture<>();
      c.diagnostics.snapshot(snap::complete);
      after = snap.get(5, java.util.concurrent.TimeUnit.SECONDS);
      if (!after.contains("TEST_EVENT_MARKER")) break;
      Thread.sleep(20);
    }
    assertTrue(sink.toString("UTF-8").contains("TEST_EVENT_MARKER")); // Saved to the file first.
    assertFalse(after.contains("TEST_EVENT_MARKER")); // Then the on-device log is cleared.
  }

  @Test
  public void approachReadIsSharedWithPendingUnlockAndConsumedWithoutDuplicatePreflight()
      throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    assertTrue(c.prefetchUnlock(() -> true, () -> {}));
    assertFalse(
        c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {}));
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertFalse(c.prefetchUnlock(() -> true, () -> {}));
    assertTrue(
        c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {}));
    complete(c);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
    assertEquals(2, p.statusRequests); // Prefetch + readback; no extra query at qualification.
  }

  @Test
  public void expiredPrefetchIsRequeriedAndNewPoweredStateBlocksUnlock() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.prefetchUnlock(() -> true, () -> {});
    complete(c);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(21));
    p.power = 3;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(2, p.statusRequests);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.lastControl.contains("전원 OFF"));
  }

  @Test
  public void leavingDuringPreparationDiscardsLateResultAndDoesNotSend() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    java.util.concurrent.atomic.AtomicBoolean approaching =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    p.beforeStatus =
        () -> {
          approaching.set(false);
          c.discardApproachPreflight();
        };
    c.prefetchUnlock(approaching::get, () -> {});
    complete(c);
    assertTrue(p.commands.isEmpty());
    p.beforeStatus = () -> {};
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(3, p.statusRequests);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
  }

  @Test
  public void automaticOffOnInvalidatesPreparedState() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.prefetchUnlock(() -> true, () -> {});
    complete(c);
    c.auto(false);
    c.auto(true);
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(3, p.statusRequests);
  }

  @Test
  public void manualUnlockAlwaysReadsFreshStateEvenWithPreparedObservation() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.prefetchUnlock(() -> true, () -> {});
    complete(c);
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals(3, p.statusRequests);
  }

  @Test
  public void cachedObservationExpiringAtDispatchCannotSendUnlock() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.prefetchUnlock(() -> true, () -> {});
    complete(c);
    java.util.concurrent.atomic.AtomicInteger checks =
        new java.util.concurrent.atomic.AtomicInteger();
    c.automaticCommand(
        CloudClient.Command.UNLOCK,
        () -> {
          if (checks.incrementAndGet() == 3)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(21));
          return true;
        },
        () -> true,
        () -> {},
        () -> {});
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertEquals(1, p.statusRequests);
  }

  @Test
  public void pairedLivePAllowsMissingBrakeStopAfterLockAndVerifiesOff() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopDueAt >= 0);
    assertTrue(c.stopStatus.contains("15초 후"));
    runDelayedStop(c, true);
    assertEquals(Arrays.asList("LOCKDOOR", "TURNOFFENGINE"), p.commands);
    assertEquals("live_P", c.vehicleLink.lastStopBasis);
    assertTrue(c.stopStatus.contains("전원 OFF 확인"));
    assertEquals(-1, c.stopDueAt);
  }

  @Test
  public void notThisPhonesTripLocksButNeverStops() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    bridgeParked(c); // Live P is present, but someone else opened the car.
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, true);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("이 휴대폰이 연 운행이 아닙니다"));
  }

  @Test
  public void ownUnlockWithoutObservedDriveNeverStops() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.command(CloudClient.Command.UNLOCK, false, () -> true); // Manual unlock starts the trip.
    complete(c);
    assertTrue(c.ownershipBlock().contains("주행"));
    p.power = 3;
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, true);
    assertFalse(p.commands.contains("TURNOFFENGINE"));
    assertTrue(c.stopStatus.contains("주행(P 해제)"));
  }

  @Test
  public void arrivingTogetherWhenAnotherPhoneUnlockedFirstStillOwnsTrip() throws Exception {
    Protocol p = new Protocol(); // Already unlocked by the passenger's phone, power OFF.
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.ownershipBlock().contains("주행")); // Trip started; drive not yet seen.
    c.vehicleSample(DRIVE);
    p.power = 3;
    bridgeParked(c);
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, true);
    assertEquals(Arrays.asList("LOCKDOOR", "TURNOFFENGINE"), p.commands);
  }

  @Test
  public void manualUnlockOfAlreadyOpenPoweredCarDoesNotClaimTrip() throws Exception {
    Protocol p = new Protocol();
    p.power = 3; // Someone else is already sitting in the powered car.
    Controller c = create(p);
    c.command(CloudClient.Command.UNLOCK, false, () -> true);
    complete(c);
    assertEquals("이 휴대폰이 연 운행이 아닙니다", c.ownershipBlock());
  }

  @Test
  public void unlockRefusedWhileCarIsOnBacksOffAndSuccessResets() throws Exception {
    Protocol p = new Protocol();
    p.power = 3; // Phone sits inside the powered car.
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    assertEquals(15000, c.automaticRecheckMs(true));
    for (long expected : new long[] {30000, 60000, 120000, 240000, 300000, 300000}) {
      c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
      complete(c);
      assertEquals(expected, c.automaticRecheckMs(true));
    }
    assertEquals(15000, c.automaticRecheckMs(false)); // Locks are never delayed by it.
    p.power = 1;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
    assertEquals(15000, c.automaticRecheckMs(true));
  }

  private static android.app.AlarmManager alarms() {
    return RuntimeEnvironment.getApplication().getSystemService(android.app.AlarmManager.class);
  }

  private static android.app.Notification chargeNotice() {
    return org.robolectric.Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(android.app.NotificationManager.class))
        .getNotification(DoorNotifications.CHARGE_ID);
  }

  private static android.app.Notification parkedOnNotice() {
    return org.robolectric.Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(android.app.NotificationManager.class))
        .getNotification(DoorNotifications.PARKED_ON_ID);
  }

  /** The Bridge reports P on a powered car; nothing has been read yet. */
  private Controller parkedOn(Protocol p) throws Exception {
    org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    p.power = 3;
    Controller c = create(p);
    c.vehicleSample(PARK);
    return c;
  }

  private static void idleMinutes(long minutes) {
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMinutes(minutes));
  }

  @Test
  public void carLeftOnInPForFiveMinutesIsReadOnceAndNotified() throws Exception {
    Protocol p = new Protocol();
    p.soc = 62.0;
    Controller c = parkedOn(p);
    idleMinutes(4);
    assertEquals(0, p.statusRequests); // Nothing is read while the 5 minutes run.
    idleMinutes(1);
    completeRead(c);
    assertEquals(1, p.statusRequests); // One read at the 5-minute mark confirms the power.
    android.app.Notification notice = parkedOnNotice();
    assertNotNull(notice);
    String text =
        String.valueOf(notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
    assertTrue(text, text.contains("5분") && text.contains("62%"));
    idleMinutes(10);
    completeRead(c);
    assertEquals(1, p.statusRequests); // Reported once; no further reads while it stays ON.
    p.power = 1; // Switched off: the next read withdraws the notice.
    c.refreshNow();
    completeRead(c);
    assertNull(parkedOnNotice());
    assertTrue(log(c).contains("PARKED_ON_END"));
  }

  @Test
  public void aReadShowingTheCarOffBeforeFiveMinutesSavesTheCheck() throws Exception {
    Protocol p = new Protocol();
    Controller c = parkedOn(p);
    idleMinutes(1);
    p.power = 1; // Switched off; a read that happens anyway (e.g. the departure lock) sees it.
    c.refreshNow();
    completeRead(c);
    assertEquals(1, p.statusRequests);
    idleMinutes(5);
    completeRead(c);
    assertEquals(1, p.statusRequests); // No extra read at the 5-minute mark.
    assertNull(parkedOnNotice());
  }

  @Test
  public void carFoundOffAtTheCheckSaysNothing() throws Exception {
    Protocol p = new Protocol();
    Controller c = parkedOn(p);
    p.power = 1; // Switched off with no read in between.
    idleMinutes(5);
    completeRead(c);
    assertEquals(1, p.statusRequests);
    assertNull(parkedOnNotice());
    idleMinutes(5);
    completeRead(c);
    assertEquals(1, p.statusRequests); // The run is over: no more reads.
  }

  @Test
  public void shiftingOutOfPOrTurningTheNoticeOffSkipsTheCheck() throws Exception {
    Protocol p = new Protocol();
    Controller c = parkedOn(p);
    idleMinutes(3);
    c.vehicleSample(DRIVE); // Drove off before 5 minutes.
    idleMinutes(5);
    completeRead(c);
    assertEquals(0, p.statusRequests);
    c.settings.edit().putBoolean("parkedOnNotify", false).commit();
    c.vehicleSample(PARK);
    idleMinutes(6);
    completeRead(c);
    assertEquals(0, p.statusRequests);
    assertNull(parkedOnNotice());
  }

  /** A locked, OFF car charging with 2 h to go, read once by a normal status refresh. */
  private Controller charging(Protocol p) throws Exception {
    org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    p.locked = true;
    p.chargeState = 1;
    p.fullHour = 2;
    p.fullMinute = 0;
    p.soc = 55.0;
    Controller c = create(p);
    c.refreshNow();
    complete(c);
    return c;
  }

  @Test
  public void chargeFinishedWhileLockedIsReadOnceAtTheEstimateAndNotified() throws Exception {
    Protocol p = new Protocol();
    long before = System.currentTimeMillis();
    Controller c = charging(p);
    org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm =
        org.robolectric.Shadows.shadowOf(alarms()).getNextScheduledAlarm();
    assertNotNull(alarm); // One alarm at the car's own estimate, no polling meanwhile.
    long expected = before + 2 * 3600000L + Controller.CHARGE_GRACE_MS;
    assertTrue(Math.abs(alarm.triggerAtTime - expected) < 60000);
    assertEquals(1, p.statusRequests);
    p.chargeState = 15; // Finished by itself; still locked and OFF.
    p.soc = 100.0;
    c.chargeWatchDue();
    complete(c);
    assertEquals(2, p.statusRequests);
    android.app.Notification notice = chargeNotice();
    assertNotNull(notice);
    assertTrue(
        String.valueOf(notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
            .contains("100%"));
    assertFalse(c.settings.getBoolean("chargeSeen", false));
    assertNull(org.robolectric.Shadows.shadowOf(alarms()).getNextScheduledAlarm());
  }

  @Test
  public void unpluggingAtTheCarEndsTheChargeWatchQuietly() throws Exception {
    Protocol p = new Protocol();
    Controller c = charging(p);
    p.chargeState = 15; // The driver unlocked and took the cable out.
    p.locked = false;
    c.chargeWatchDue();
    complete(c);
    assertNull(chargeNotice());
    assertFalse(c.settings.getBoolean("chargeSeen", false));
    assertNull(org.robolectric.Shadows.shadowOf(alarms()).getNextScheduledAlarm());
  }

  @Test
  public void chargeNoticeOffSetsNoAlarm() throws Exception {
    Protocol p = new Protocol();
    p.chargeState = 1;
    p.fullHour = 1;
    p.fullMinute = 0;
    Controller c = create(p);
    c.settings.edit().putBoolean("chargeNotify", false).commit();
    c.refreshNow();
    complete(c);
    assertNull(org.robolectric.Shadows.shadowOf(alarms()).getNextScheduledAlarm());
  }

  @Test
  public void sharingDiagnosticsWritesOnePrivateCopyAndKeepsTheLog() throws Exception {
    Controller c = create(new Protocol());
    c.diagnostics.record("TEST_SHARE_MARKER", "x");
    java.io.File shared = null;
    for (int round = 0; round < 2; round++) {
      java.util.concurrent.atomic.AtomicReference<java.io.File> result =
          new java.util.concurrent.atomic.AtomicReference<>();
      c.shareDiagnostics(result::set);
      long limit = System.currentTimeMillis() + 5000;
      while (result.get() == null && System.currentTimeMillis() < limit) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Thread.sleep(20);
      }
      shared = result.get();
      assertNotNull(shared);
    }
    assertTrue(shared.getName().matches("D-Autolock-diagnostics-[0-9-]+\\.txt"));
    String text =
        new String(java.nio.file.Files.readAllBytes(shared.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(text.contains("TEST_SHARE_MARKER"));
    assertEquals(1, shared.getParentFile().listFiles().length); // Only the latest copy is kept.
    java.util.concurrent.CompletableFuture<String> log = new java.util.concurrent.CompletableFuture<>();
    c.diagnostics.snapshot(log::complete);
    assertTrue( // Not cleared: sending cannot be confirmed.
        log.get(5, java.util.concurrent.TimeUnit.SECONDS).contains("TEST_SHARE_MARKER"));
  }

  @Test
  public void automaticUnlockOfAnOpenOrMovingCarIsRetiredInsteadOfBackingOff() throws Exception {
    Protocol p = new Protocol();
    p.power = 3; // Opened by the other signal; the driver is in and has powered the car on.
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
    c.automaticCommand(
        CloudClient.Command.UNLOCK, () -> true, () -> true, done::incrementAndGet, () -> {});
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertEquals(1, done.get()); // The request is consumed, not re-checked.
    assertEquals(15000, c.automaticRecheckMs(true));
    p.locked = true; // Driving away: the car locked itself.
    p.speed = 40;
    c.automaticCommand(
        CloudClient.Command.UNLOCK, () -> true, () -> true, done::incrementAndGet, () -> {});
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertEquals(2, done.get());
    assertEquals(15000, c.automaticRecheckMs(true));
  }

  @Test
  public void repeatedAutomaticLockOnLockedCarWithClosedWindowsDoesNotResendWindows()
      throws Exception {
    Protocol p = new Protocol();
    p.locked = true; // Protocol reports all windows closed.
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, false);
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.windowsStatus.contains("생략"));
  }

  @Test
  public void boardingClimateTurnsOffAfterDelayOnlyWhenEnabled() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    p.openOnUnlock = true;
    Controller c = create(p);
    c.settings.edit().putBoolean("autoReady", true).putBoolean("climateAutoOff", true).commit();
    assertEquals(10, c.climateOffSeconds());
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertTrue(c.pollEntry(() -> true));
    complete(c);
    assertEquals(Arrays.asList("OPENDOOR", "OPENAIR"), p.commands); // Not off yet.
    for (int i = 0; i < 6 && !p.commands.contains("CLOSEAIR"); i++) {
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
          .idleFor(java.time.Duration.ofSeconds(3));
      complete(c);
    }
    assertEquals(Arrays.asList("OPENDOOR", "OPENAIR", "CLOSEAIR"), p.commands);
    c.stop();
  }

  @Test
  public void boardingClimateStaysOnWhenAutoOffDisabled() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    p.openOnUnlock = true;
    Controller c = create(p);
    c.settings.edit().putBoolean("autoReady", true).commit(); // climateAutoOff default false.
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertTrue(c.pollEntry(() -> true));
    complete(c);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(15));
    complete(c);
    assertFalse(p.commands.contains("CLOSEAIR"));
    c.stop();
  }

  @Test
  public void manualClimateStartNeverAutoOffsEvenWhenBoardingAutoOffEnabled() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.settings.edit().putBoolean("climateAutoOff", true).commit();
    c.manualClimateStart();
    complete(c);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(15));
    complete(c);
    assertEquals(Collections.singletonList("OPENAIR"), p.commands); // 시동 켜기 keeps A/C on.
    c.stop();
  }

  @Test
  public void gettingOutRightAfterPowerOffNeverStartsClimateOrClaimsTrip() throws Exception {
    // Real log 21:54: car ON, driver switches off (already unlocked), opens door to leave.
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.settings.edit().putBoolean("autoReady", true).commit();
    c.refreshNow();
    completeRead(c);
    assertTrue(c.recentlyPowered());
    p.power = 1;
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    p.doorOpen = true; // Exit door opens.
    assertFalse(c.pollEntry(() -> true));
    complete(c);
    assertTrue(p.commands.isEmpty()); // No OPENAIR: the car is not powered back on.
    assertEquals("이 휴대폰이 연 운행이 아닙니다", c.ownershipBlock());
  }

  private void settle(Controller c) throws Exception {
    for (int i = 0; i < 4; i++) {
      complete(c);
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    complete(c);
  }

  @Test
  public void departureFinishStopsThenLocksOnlyWhenPowerOffConfirmed() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    p.windowOpen = true;
    Controller c = create(p);
    c.departureFinish();
    settle(c);
    assertEquals(Arrays.asList("TURNOFFENGINE", "LOCKDOOR", "CLOSEWINDOW"), p.commands);
  }

  @Test
  public void departureFinishWithReleasedBrakeNeitherStopsNorLocks() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 0;
    Controller c = create(p);
    c.departureFinish();
    settle(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.message.contains("주차 마무리 중단"));
  }

  @Test
  public void stopAfterWalkingAwayUsesStablePAtBluetoothLoss() throws Exception {
    // Real log 11:04: lock on departure, then the Bridge link dropped as the phone left range.
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    c.vehicleLink.state.lost(android.os.SystemClock.elapsedRealtime());
    runDelayedStop(c, false);
    assertEquals(Arrays.asList("LOCKDOOR", "TURNOFFENGINE"), p.commands);
    assertEquals("P_at_link_loss", c.vehicleLink.lastStopBasis);
  }

  @Test
  public void bluetoothLossLongBeforeTheStopNeverAuthorizesIt() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.vehicleLink.state.lost(android.os.SystemClock.elapsedRealtime());
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(50));
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, false); // P evidence is now older than 60 s.
    assertFalse(p.commands.contains("TURNOFFENGINE"));
  }

  @Test
  public void unlockingFromInsideThePoweredCarKeepsTheTrip() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    c.refreshNow();
    completeRead(c);
    p.locked = false; // Driver opens the doors to get out.
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
    c.refreshNow();
    completeRead(c);
    assertNull(c.ownershipBlock());
  }

  @Test
  public void openWindowIsStillClosedAfterAutomaticLockWhileCarIsOn() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.windowOpen = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
  }

  @Test
  public void approachReadSixSecondsOldIsReusedSoUnlockIsSentImmediately() throws Exception {
    // Real log 12:16: the approach read was 6 s old at the threshold and a 3 s fresh query ran.
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    assertTrue(c.prefetchUnlock(() -> true, () -> {}));
    complete(c);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
    assertFalse(c.prefetchUnlock(() -> true, () -> {})); // Still young: no extra read.
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(Collections.singletonList("OPENDOOR"), p.commands);
    assertEquals(2, p.statusRequests); // Approach read + readback only.
  }

  @Test
  public void approachReadIsRefreshedBeforeItExpires() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    assertTrue(c.prefetchUnlock(() -> true, () -> {}));
    complete(c);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(13));
    assertTrue(c.prefetchUnlock(() -> true, () -> {}));
    complete(c);
    assertEquals(2, p.statusRequests);
    assertTrue(p.commands.isEmpty());
  }

  @Test
  public void lockRefusedAfterConfirmedDepartureRaisesRedAlertUntilSecured() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.doorOpen = true; // A door is open, so the lock is refused even though the user left.
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertFalse(p.commands.contains("LOCKDOOR"));
    assertNotNull(c.securityAlert);
    p.doorOpen = false;
    p.power = 1;
    p.locked = true; // Secured manually.
    c.refreshNow();
    completeRead(c);
    assertNull(c.securityAlert);
  }

  @Test
  public void openDoorAfterDepartureRaisesDoorOpenAlert() throws Exception {
    Protocol p = new Protocol();
    p.doorOpen = true; // A door is open, so the automatic lock is held.
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertFalse(p.commands.contains("LOCKDOOR"));
    assertNotNull(c.securityAlert);
    assertTrue(c.securityAlert.contains("문이 열려"));
    p.doorOpen = false;
    p.locked = true; // Door closed and secured.
    c.refreshNow();
    completeRead(c);
    assertNull(c.securityAlert);
  }

  @Test
  public void signalLossNearRunningCarDoesNotRaiseRedAlert() throws Exception {
    // Real log 10-10: parked ON, BLE lost for minutes, loss-lock blocked every ~18 s. Departure is
    // NOT confirmed (phone likely inside), so this must not raise the "secure the car" alarm.
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    for (int i = 0; i < 3; i++) {
      c.automaticCommand(
          CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, false);
      complete(c);
    }
    assertTrue(p.commands.isEmpty());
    assertNull(c.securityAlert); // No false "secure the car" alarm while sitting in a running car.
  }

  @Test
  public void lockedButStillPoweredOnWithoutStopRaisesAlert() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.securityAlert.startsWith("도어는 잠겼지만 시동이 켜져"));
  }

  @Test
  public void alreadyLockedRunningCarWhileNearDoesNotRaiseRedAlert() throws Exception {
    // Real log: an automatic re-lock on an already-locked, still-running car while the phone is NEAR
    // (departure NOT confirmed — most likely sitting in or driving the car) reached afterLock and
    // raised the red "secure the car" alert on every drive. It must stay silent until departure.
    Protocol p = new Protocol();
    p.power = 3;
    p.locked = true; // Already locked: the lock path goes straight to afterLock, no command sent.
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, false);
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertNull(c.securityAlert);
  }

  @Test
  public void tripSurvivesAppRestart() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    ownTrip(c);
    assertNull(c.ownershipBlock());
    Controller restarted = create(p); // Same stored settings, new process state.
    assertNull(restarted.ownershipBlock());
  }

  @Test
  public void sharedVehicleModeKeepsAutomaticLockButNeverStops() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.settings.edit().putBoolean("sharedVehicle", true).commit();
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, true);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("공유 차량 모드"));
  }

  @Test
  public void userCancelDuringDelayPreventsStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    new DoorActionReceiver()
        .onReceive(
            RuntimeEnvironment.getApplication(),
            new android.content.Intent(DoorNotifications.CANCEL_STOP));
    // The receiver uses the application controller; cancel this one the same way.
    c.cancelPendingStop("알림에서 사용자 취소");
    runDelayedStop(c, true);
    assertFalse(p.commands.contains("TURNOFFENGINE"));
    assertTrue(c.stopStatus.contains("사용자 취소"));
  }

  @Test
  public void configuredDelayIsHonoured() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.settings.edit().putInt("stopDelaySeconds", 30).commit();
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, true); // 16 s: still waiting.
    assertFalse(p.commands.contains("TURNOFFENGINE"));
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(12));
    feedPark(c);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(3));
    complete(c);
    assertTrue(p.commands.contains("TURNOFFENGINE"));
  }

  @Test
  public void unlockSeenFromAnotherSourceEndsThisPhonesTrip() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    Controller c = create(p);
    ownTrip(c);
    assertNull(c.ownershipBlock());
    c.refreshNow();
    complete(c);
    p.locked = false; // Someone else unlocked with a key or another phone.
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
    c.refreshNow();
    complete(c);
    assertEquals("이 휴대폰이 연 운행이 아닙니다", c.ownershipBlock());
  }

  @Test
  public void lostBridgeAfterDoorLockNeverFallsBackToCloudBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    c.vehicleLink.state.clear();
    runDelayedStop(c, false);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("연결 끊김"));
  }

  @Test
  public void changedGearAfterLockCancelsStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(3));
    c.vehicleSample(DRIVE); // Someone inside shifts out of P during the delay.
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    runDelayedStop(c, true);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("차량 조작 감지"));
  }

  @Test
  public void removedOrWrongVehiclePairingCannotAuthorizeStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.vin = "another-car";
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, true);
    assertFalse(p.commands.contains("TURNOFFENGINE"));
    assertTrue(c.stopStatus.contains("QR"));
  }

  @Test
  public void stalePairingCannotAuthorizeStopEvenWithCloudBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    runDelayedStop(c, false); // Bridge stops reporting: last P is far older than 3 s.
    assertFalse(p.commands.contains("TURNOFFENGINE"));
  }

  @Test
  public void livePDoesNotReplaceFreshDepartureSignal() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.locked = true;
    Controller c = create(p);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, false);
    complete(c);
    assertFalse(p.commands.contains("TURNOFFENGINE"));
    assertTrue(c.stopStatus.contains("이탈"));
  }

  @Test
  public void manualLockSkipsCloseWindowsWhenTheLockedReadShowsThemClosed() throws Exception {
    // Real log 11-09 20:59/21:57: the redundant CLOSEWINDOW went unanswered for 20 s and left
    // "result unknown · check the car" although BYD reported every window closed.
    Protocol p = new Protocol();
    Controller c = create(p);
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.windowsStatus, c.windowsStatus.contains("생략"));
    assertTrue(log(c).contains("WINDOWS_SKIP | lockedRead=true cloudClosed=true"));
  }

  @Test
  public void manualLockStillClosesAWindowReportedOpen() throws Exception {
    Protocol p = new Protocol();
    p.windowOpen = true;
    Controller c = create(p);
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
    assertTrue(c.windowsStatus.contains("요청 완료"));
  }

  @Test
  public void lockReadBackWithoutTheLockStateIsReadAgainNotResent() throws Exception {
    // Real log 11-09 21:08: the cloud acknowledged LOCK but the read-back came without the lock
    // state, so the result stayed "unconfirmed" for 46 min.
    Protocol p = new Protocol();
    p.lockGapReads = 1;
    Controller c = create(p);
    c.readbackRetryMs = 10;
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertEquals(3, p.statusRequests); // Preflight, gap read-back, one more read.
    assertTrue(c.lastControl, c.lastControl.contains("완료 · 차량 상태 확인됨"));
    assertTrue(log(c).contains("READBACK_RETRY | LOCK attempt=1"));
  }

  @Test
  public void lockReadBackThatNeverShowsTheStateStaysUnconfirmedAfterTwoMoreReads()
      throws Exception {
    Protocol p = new Protocol();
    p.lockGapReads = 10;
    Controller c = create(p);
    c.readbackRetryMs = 10;
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands); // Never resent.
    assertEquals(4, p.statusRequests); // Preflight, read-back, two more reads, then stop asking.
    assertTrue(c.lastControl, c.lastControl.contains("미확인"));
  }

  @Test
  public void readbackPendingOnlyForGapsNotForAnAnswer() throws Exception {
    long sent = System.currentTimeMillis();
    JSONObject base =
        new JSONObject().put("time", sent + 2000).put("speed", 0).put("powerGear", 1);
    com.dautolock.app.core.VehicleSnapshot noLock =
        new com.dautolock.app.core.VehicleSnapshot(new JSONObject(base.toString()), sent);
    assertTrue(Controller.readbackPending(noLock, false, sent));
    assertFalse(Controller.readbackPending(noLock, true, sent)); // Stop reads power, present here.
    JSONObject unlocked = new JSONObject(base.toString());
    for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"})
      unlocked.put(side + "DoorLock", 1);
    assertFalse( // An answer (still unlocked) is not a gap: not asked again.
        Controller.readbackPending(
            new com.dautolock.app.core.VehicleSnapshot(unlocked, sent), false, sent));
    unlocked.put("time", sent - 5000);
    assertTrue( // Measured before the command: cannot judge it.
        Controller.readbackPending(
            new com.dautolock.app.core.VehicleSnapshot(unlocked, sent), false, sent));
  }

  @Test
  public void cloudBrakeWithoutLivePNoLongerAuthorizesAutomaticStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    ownTrip(c);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
    c.monitoring = true;
    c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    runDelayedStop(c, false);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("실제 P단 확인 불가"));
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
    runDelayedStop(c, false);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.stopStatus.contains("자동 종료 안 함"));
  }

  @Test
  public void unavailableEpbOffVehicleReceivesStartOnlyAndReadOnlyDiagnostics() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.manualClimateStart();
    complete(c);
    assertEquals(Collections.singletonList("OPENAIR"), p.commands);
    assertTrue(c.climateStatus.contains("자동 OFF 없음"));
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    complete(c);
    assertEquals(1, p.hvacRequests);
    assertTrue(c.readyStatus.contains("READY 유지 미확인"));
    assertTrue(c.readyStatus.contains("공조 서버 응답 ON"));
    assertEquals(Collections.singletonList("OPENAIR"), p.commands);
    c.stop();
    int reads = p.statusRequests;
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(130));
    assertEquals(reads, p.statusRequests);
  }

  @Test
  public void uncertainClimateStartNeverTriggersOffOrReplay() throws Exception {
    Protocol p = new Protocol();
    p.rejectClimate = true;
    Controller c = create(p);
    c.manualClimateStart();
    complete(c);
    assertEquals(Collections.singletonList("OPENAIR"), p.commands);
    assertTrue(c.climateStatus.contains("결과 미확인"));
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    complete(c);
    assertTrue(p.hvacRequests > 0);
    assertEquals(Collections.singletonList("OPENAIR"), p.commands);
    c.stop();
  }

  @Test
  public void automaticEntryReusesFreshDoorSnapshotAndStartsOnlyOnce() throws Exception {
    Protocol p = new Protocol();
    p.locked = true;
    p.openOnUnlock = true;
    Controller c = create(p);
    c.settings.edit().putBoolean("autoReady", true).commit();
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.UNLOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertTrue(c.pollEntry(() -> true));
    complete(c);
    assertEquals(Arrays.asList("OPENDOOR", "OPENAIR"), p.commands);
    assertEquals(
        3, p.statusRequests); // Unlock preflight/readback, then door observation; no duplicate
    // preflight.
    assertFalse(c.pollEntry(() -> true));
    c.stop();
  }

  @Test
  public void lossOnlyLockDoesNotTurnOffPoweredCarEvenWithBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, false);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("BLE 끊김"));
  }

  @Test
  public void boundedWatchContinuesWithoutHvacAndDetectsOkDropWithoutCommands() throws Exception {
    Protocol p = new Protocol();
    p.rejectHvac = true;
    p.okLight = 1;
    Controller c = create(p);
    c.manualClimateStart();
    complete(c);
    org.robolectric.shadows.ShadowLooper main =
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
    int[] advance = {0, 15, 15, 30, 60};
    for (int i = 0; i < advance.length; i++) {
      if (i >= 2) {
        p.okLight = 0;
        p.power = 1;
      }
      main.idleFor(java.time.Duration.ofSeconds(advance[i]));
      complete(c);
      main.idle();
    }
    assertEquals(6, p.statusRequests); // One start preflight plus five scheduled observations.
    assertEquals(1, p.hvacRequests); // Unsupported HVAC does not suppress power/OK observations.
    assertTrue(c.readyStatus.contains("진단 종료"));
    assertTrue(c.readyStatus.contains("1 → 0"));
    assertEquals(Collections.singletonList("OPENAIR"), p.commands);
    main.idleFor(java.time.Duration.ofMinutes(5));
    assertEquals(6, p.statusRequests);
  }

  @Test
  public void poweredUnavailableBrakeVehicleLocksOnDepartureButNeverAutomaticallyStops()
      throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus.contains("자동 종료 안 함"));
  }

  @Test
  public void lostDepartureEvidenceCannotAuthorizePoweredLockWithMissingBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    java.util.concurrent.atomic.AtomicBoolean far =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    p.beforeStatus = () -> far.set(false);
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(
        CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, far::get);
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.lastControl.contains("신호 세기로 이탈"));
  }

  @Test
  public void lockRefusedForUnconfirmedDepartureIsCountedUntilOneGoesThrough() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    c.monitoring = c.autoEnabled = true;
    for (int i = 1; i <= 2; i++) {
      c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, false);
      complete(c);
      assertEquals(i, c.lockDepartureBlocks);
    }
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertEquals(0, c.lockDepartureBlocks);
  }

  private static final String HEAD_UNIT = "00:11:22:33:44:55";

  private void carAudio(Controller c, String address, boolean connected) throws Exception {
    java.lang.reflect.Field field = VehicleLink.class.getDeclaredField("address");
    field.setAccessible(true);
    field.set(c.vehicleLink, HEAD_UNIT);
    android.content.Intent i =
        new android.content.Intent(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            .putExtra(
                android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address))
            .putExtra(
                android.bluetooth.BluetoothProfile.EXTRA_STATE,
                connected
                    ? android.bluetooth.BluetoothProfile.STATE_CONNECTED
                    : android.bluetooth.BluetoothProfile.STATE_DISCONNECTED);
    c.carAudio.onBroadcast(i);
  }

  @Test
  public void carAudioOnTheHeadUnitMarksInCarAndOtherDevicesDoNot() throws Exception {
    Controller c = create(new Protocol());
    bridgeParked(c);
    carAudio(c, "AA:BB:CC:DD:EE:FF", true); // Earbuds or another car: ignored.
    assertFalse(c.inCarByAudio());
    carAudio(c, HEAD_UNIT, true);
    assertTrue(c.inCarByAudio());
    c.settings.edit().putBoolean("carAudioHold", false).commit();
    assertFalse(c.inCarByAudio()); // Switched off in settings.
    c.settings.edit().putBoolean("carAudioHold", true).commit();
    carAudio(c, HEAD_UNIT, false);
    assertFalse(c.inCarByAudio());
  }

  @Test
  public void carAudioTellsTheBridgeLinkWhetherTheCarIsOn() throws Exception {
    Controller c = create(new Protocol());
    bridgeParked(c);
    assertNull(c.carAudio.carOn()); // Never connected in this run: nothing known.
    carAudio(c, HEAD_UNIT, true);
    assertEquals(Boolean.TRUE, c.carAudio.carOn());
    assertTrue("the head unit came up: reconnect now", c.vehicleLink.wakePending());
    carAudio(c, HEAD_UNIT, false);
    assertEquals(Boolean.FALSE, c.carAudio.carOn()); // Switched off (or the phone left it).
    c.carAudio.clear();
    assertNull(c.carAudio.carOn());
  }

  @Test
  public void aWakeCutsTheBridgeReconnectWaitShort() throws Exception {
    Controller c = create(new Protocol());
    java.util.concurrent.CompletableFuture<Boolean> woke = new java.util.concurrent.CompletableFuture<>();
    long started = System.nanoTime();
    Thread waiter =
        new Thread(
            () -> {
              try {
                woke.complete(c.vehicleLink.pause(60000));
              } catch (InterruptedException e) {
                woke.completeExceptionally(e);
              }
            });
    waiter.start();
    Thread.sleep(100);
    c.vehicleLink.wake();
    assertTrue(woke.get(5, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue((System.nanoTime() - started) / 1_000_000 < 5000);
    assertFalse(c.vehicleLink.wakePending()); // Picked up once.
  }

  @Test
  public void automaticStopIsNotSentWhileThePhoneIsOnTheCarsAudio() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    ownTrip(c);
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    carAudio(c, HEAD_UNIT, true); // Back in the car before the delayed Stop.
    runDelayedStop(c, true);
    assertEquals(Collections.singletonList("LOCKDOOR"), p.commands);
    assertTrue(c.stopStatus, c.stopStatus.contains("차 블루투스"));
  }

  @Test
  public void manualParkingConfirmationStopsOnceAndClosesLockedWindowsAfterPowerOff()
      throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.locked = true;
    p.windowOpen = true;
    Controller c = create(p);
    c.manualStopAfterParkingConfirmation();
    complete(c);
    assertEquals(Arrays.asList("TURNOFFENGINE", "CLOSEWINDOW"), p.commands);
    assertTrue(c.stopStatus.contains("완료"));
    p.power = 3;
    c.command(CloudClient.Command.STOP, false, () -> true);
    complete(c);
    assertEquals(Arrays.asList("TURNOFFENGINE", "CLOSEWINDOW"), p.commands);
    assertTrue(c.lastControl.contains("주차브레이크 정보 미제공"));
  }

  @Test
  public void stopAwayFromTheCarEndsTheRemoteStartWithClimateOff() throws Exception {
    Protocol p = new Protocol();
    p.power = 3; // Started remotely (시동 켜기), locked, nobody in it.
    p.locked = true;
    p.climateOffPowersDown = true;
    Controller c = create(p);
    bridgeParked(c);
    c.vehicleLink.state.clear(); // Phone far away: the Bridge cannot vouch for P.
    c.manualStopAfterParkingConfirmation();
    complete(c);
    assertEquals(Collections.singletonList("CLOSEAIR"), p.commands); // Never TURNOFFENGINE.
    assertTrue(c.stopStatus, c.stopStatus.contains("전원 OFF 확인 중"));
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(16));
    completeRead(c);
    assertTrue(c.stopStatus, c.stopStatus.contains("원격 시동 꺼짐"));
    assertEquals(Collections.singletonList("CLOSEAIR"), p.commands);
  }

  @Test
  public void remoteClimateOffThatLeavesTheCarRunningSaysSoAndIsNotResent() throws Exception {
    Protocol p = new Protocol();
    p.power = 3; // Someone took the car over: CLOSEAIR only stops its A/C.
    Controller c = create(p);
    bridgeParked(c);
    c.vehicleLink.state.clear();
    c.manualStopAfterParkingConfirmation();
    complete(c);
    org.robolectric.shadows.ShadowLooper main =
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
    for (int i = 0; i < 4; i++) {
      main.idleFor(java.time.Duration.ofSeconds(16));
      completeRead(c);
    }
    assertEquals(Collections.singletonList("CLOSEAIR"), p.commands);
    assertTrue(c.stopStatus, c.stopStatus.contains("아직 켜져"));
  }

  @Test
  public void stopWithLiveBridgeParkStillSendsTurnOffEngine() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    bridgeParked(c);
    c.manualStopAfterParkingConfirmation();
    complete(c);
    assertEquals(Collections.singletonList("TURNOFFENGINE"), p.commands);
  }

  @Test
  public void manualParkingConfirmationExpiresDuringSlowStatusRequest() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.beforeStatus =
        () -> org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31));
    Controller c = create(p);
    c.manualStopAfterParkingConfirmation();
    complete(c);
    assertTrue(p.commands.isEmpty());
  }

  @Test
  public void manualStopAlreadyOffWithoutBrakeIsSkippedNotBlocked() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.manualStopAfterParkingConfirmation();
    complete(c);
    assertTrue(p.commands.isEmpty());
    assertTrue(c.stopStatus.contains("이미 전원 OFF"));
  }

  private void addFunction(Controller c, String functionNo) throws Exception {
    c.capabilities.getJSONArray("items").put(new JSONObject().put("functionNo", functionNo));
  }

  @Test
  public void findCarSendsOnceWithoutStatusReads() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    addFunction(c, "1007");
    c.accessory(CloudClient.Command.FIND_CAR);
    complete(c);
    assertEquals(Collections.singletonList("FINDCAR"), p.commands);
    assertEquals(0, p.statusRequests);
    assertTrue(c.lastControl.contains("차량 찾기 요청 완료"));
  }

  @Test
  public void findCarIsRefusedWhenTheCarDoesNotListIt() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p); // Lists 1005/1006/1001/1026/1031, not 1007.
    c.accessory(CloudClient.Command.FIND_CAR);
    complete(c);
    assertEquals(1, p.configRequests); // Refreshed once, still without 1007.
    assertTrue(p.commands.isEmpty());
    assertTrue(c.lastControl.contains("지원을 확인하지 못했습니다"));
  }

  @Test
  public void closeWindowsReadsBackClosedAndSkipsWhenAlreadyClosed() throws Exception {
    Protocol p = new Protocol();
    p.windowOpen = true;
    Controller c = create(p);
    c.accessory(CloudClient.Command.CLOSE_WINDOWS);
    complete(c);
    assertEquals(Collections.singletonList("CLOSEWINDOW"), p.commands);
    assertEquals(2, p.statusRequests); // Preflight and readback.
    assertTrue(c.windowsStatus.contains("창문 닫기 완료"));
    c.accessory(CloudClient.Command.CLOSE_WINDOWS);
    complete(c);
    assertEquals(Collections.singletonList("CLOSEWINDOW"), p.commands); // Not sent again.
    assertTrue(c.windowsStatus.contains("이미"));
  }

  @Test
  public void climateScaleLearnedFromHvacSetsTheNextStartTemperature() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    // Default 17–33 °C (Korean Dolphin): 23 °C in half-degree steps from 16.5 °C.
    assertEquals(13, c.cloud.climateParams().getInt("mainSettingTemp"));
    c.learnClimateScale(
        new com.dautolock.app.core.HvacSnapshot(
            new JSONObject().put("vdeTempRangeMin", 15).put("vdeTempRangeMax", 31),
            System.currentTimeMillis()));
    assertEquals(9, c.cloud.climateParams().getInt("mainSettingTemp")); // 23 °C on 15–31 °C.
    assertEquals(14f, c.settings.getFloat("climateTempOffset", 0), 0);
  }
}
