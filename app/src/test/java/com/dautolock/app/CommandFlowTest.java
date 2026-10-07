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
    Object epb = "--";
    boolean doorOpen, openOnUnlock, rejectClimate, rejectHvac;
    Object okLight = JSONObject.NULL;
    int statusRequests, hvacRequests;
    Runnable beforeStatus = () -> {};
    final List<String> commands = new ArrayList<>();

    Protocol() {
      super(BydConfig.fromRegion("KR"));
      setSignToken("test-session");
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
                  .put("time", System.currentTimeMillis())
                  .put("speed", 0)
                  .put("powerGear", power)
                  .put("okLight", okLight)
                  .put("epb", epb);
          for (String side : new String[] {"leftFront", "rightFront", "leftRear", "rightRear"}) {
            s.put(side + "Door", doorOpen ? 1 : 0)
                .put(side + "DoorLock", locked ? 2 : 1)
                .put(side + "Window", 1);
          }
          cb.onSuccess(s);
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
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
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
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
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
    bridgeParked(c);
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "TURNOFFENGINE", "CLOSEWINDOW"), p.commands);
    assertTrue(c.stopStatus.contains("전원 OFF 확인"));
  }

  @Test
  public void lostBridgeAfterDoorLockNeverFallsBackToCloudBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    bridgeParked(c);
    p.beforeStatus =
        () -> {
          if (p.statusRequests >= 3) c.vehicleLink.state.clear();
        };
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
    assertTrue(c.stopStatus.contains("연결 끊김"));
  }

  @Test
  public void changedGearAfterLockCancelsStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    Controller c = create(p);
    bridgeParked(c);
    p.beforeStatus =
        () -> {
          if (p.statusRequests >= 3) {
            long n = android.os.SystemClock.elapsedRealtime();
            c.vehicleLink.state.accept(
                new com.dautolock.link.LinkProtocol.Sample(3, -1, 4, 2, -1, 0), n, n);
          }
        };
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
    assertTrue(c.stopStatus.contains("P단 미확인"));
  }

  @Test
  public void removedOrWrongVehiclePairingCannotAuthorizeStop() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    bridgeParked(c);
    c.vin = "another-car";
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
    assertFalse(p.commands.contains("TURNOFFENGINE"));
    assertTrue(c.stopStatus.contains("QR"));
  }

  @Test
  public void stalePairingCannotAuthorizeStopEvenWithCloudBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    bridgeParked(c);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4));
    c.monitoring = c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {}, true);
    complete(c);
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
  public void manualLockSendsCloseWindowsEvenIfCloudAlreadyReportsClosed() throws Exception {
    Protocol p = new Protocol();
    Controller c = create(p);
    c.command(CloudClient.Command.LOCK, false, () -> true);
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
    assertTrue(c.windowsStatus.contains("요청 완료"));
  }

  @Test
  public void automaticLockStopsThenClosesWindowsOnlyWithKnownBrake() throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.epb = 1;
    Controller c = create(p);
    c.monitoring = true;
    c.autoEnabled = true;
    c.automaticCommand(CloudClient.Command.LOCK, () -> true, () -> true, () -> {}, () -> {});
    complete(c);
    assertEquals(Arrays.asList("LOCKDOOR", "TURNOFFENGINE", "CLOSEWINDOW"), p.commands);
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
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
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
    assertEquals(Arrays.asList("LOCKDOOR", "CLOSEWINDOW"), p.commands);
    assertTrue(c.stopStatus.contains("자동 종료 불가"));
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
  public void manualParkingConfirmationStopsOnceAndClosesLockedWindowsAfterPowerOff()
      throws Exception {
    Protocol p = new Protocol();
    p.power = 3;
    p.locked = true;
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
}
