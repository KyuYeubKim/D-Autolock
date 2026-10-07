package com.dautolock.app.core;

import static com.dautolock.app.core.ProximityEngine.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ProximityEngineTest {
  @Test
  public void departureEvidenceSurvivesClaimButExpiresWithLossOrReturn() {
    ProximityEngine e = new ProximityEngine(-60, -80, 0, 1000, 10000);
    feed(e, -50, 0, 3000);
    assertFalse(e.departureConfirmed(3000));
    e.alreadySatisfied(UNLOCK, 3000);
    feed(e, -100, 4000, 9000);
    assertTrue(e.departureConfirmed(9000));
    assertTrue(e.claim(LOCK, 9000));
    assertTrue(e.departureConfirmed(9000));
    assertFalse(e.departureConfirmed(15000));
    feed(e, -50, 15000, 19000);
    assertFalse(e.departureConfirmed(19000));
  }

  @Test
  public void preflightAllowsSmallFreshDipWithoutChangingInitialThreshold() {
    ProximityEngine e = new ProximityEngine(-60, -80, 1000, 2000, 10000);
    for (int t = 0; t <= 2000; t += 200) e.sample(-59, t);
    assertEquals(UNLOCK, e.pending(2000));
    e.beginCheck(UNLOCK, 2000);
    for (int t = 2200; t <= 6000; t += 200) e.sample(-62, t);
    assertEquals(NONE, e.pending(6000));
    assertTrue(e.stillValid(UNLOCK, 6000));
    assertTrue(e.claim(UNLOCK, 6000));
    assertFalse(e.stillValid(UNLOCK, 6000));
  }

  @Test
  public void preflightReservationExpiresOrRevokesForRealDepartureAndLoss() {
    for (int scenario = 0; scenario < 3; scenario++) {
      ProximityEngine e = new ProximityEngine(-60, -80, 0, 2000, 10000);
      feed(e, -50, 0, 3000);
      e.beginCheck(UNLOCK, 3000);
      if (scenario == 0) {
        for (int t = 3200; t <= 13200; t += 200) e.sample(-63, t);
        assertFalse(e.stillValid(UNLOCK, 13200));
      } else if (scenario == 1) {
        feed(e, -100, 4000, 6000);
        assertFalse(e.stillValid(UNLOCK, 6000));
      } else assertFalse(e.stillValid(UNLOCK, 9000));
    }
  }

  @Test
  public void unqualifiedSignalCannotReserveAnUnlock() {
    ProximityEngine e = new ProximityEngine(-60, -80);
    feed(e, -62, 0, 5000);
    e.beginCheck(UNLOCK, 5000);
    assertFalse(e.stillValid(UNLOCK, 5000));
  }

  @Test
  public void configuredOneSecondApproachReplacesThreeSecondDefault() {
    ProximityEngine e = new ProximityEngine(-65, -80, 1000, 8000, 10000);
    e.sample(-50, 0);
    e.sample(-50, 200);
    e.sample(-50, 400);
    e.sample(-50, 600);
    assertEquals(NONE, e.pending(999));
    assertEquals(UNLOCK, e.sample(-50, 1000));
  }

  @Test
  public void zeroDelayStillRequiresFourDistinctFreshAdvertisements() {
    ProximityEngine e = new ProximityEngine(-65, -80, 0, 0, 10000);
    e.sample(-50, 0);
    e.sample(-50, 100);
    e.sample(-50, 200);
    assertEquals(NONE, e.pending(200));
    assertEquals(UNLOCK, e.sample(-50, 300));
    assertTrue(e.claim(UNLOCK, 300));
    assertEquals(NONE, e.pending(400));
  }

  @Test
  public void configuredDepartureDelayIsUsed() {
    ProximityEngine e = new ProximityEngine(-65, -80, 0, 3000, 10000);
    feed(e, -50, 0, 3000);
    e.alreadySatisfied(UNLOCK, 3000);
    feed(e, -110, 4000, 7000);
    assertEquals(NONE, e.pending(7999));
    assertEquals(LOCK, e.sample(-110, 8000));
  }

  @Test
  public void configuredLossTimeAndValidationAreApplied() {
    ProximityEngine e = new ProximityEngine(-65, -80, 3000, 8000, 20000);
    feed(e, -50, 0, 3000);
    e.claim(UNLOCK, 3000);
    assertEquals(NONE, e.pending(22999));
    assertEquals(LOCK, e.pending(23000));
    assertTrue(e.reason(23000).contains("20초"));
    assertThrows(
        IllegalArgumentException.class, () -> new ProximityEngine(-65, -80, -1, 8000, 10000));
    assertThrows(
        IllegalArgumentException.class, () -> new ProximityEngine(-65, -80, 16000, 8000, 10000));
    assertThrows(
        IllegalArgumentException.class, () -> new ProximityEngine(-65, -80, 0, 31000, 10000));
    assertThrows(IllegalArgumentException.class, () -> new ProximityEngine(-65, -80, 0, 0, 4000));
  }

  private void feed(ProximityEngine e, int rssi, long from, long to) {
    for (long t = from; t <= to; t += 1000) e.sample(rssi, t);
  }

  @Test
  public void initialStableNearCanUnlock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    assertEquals(UNLOCK, e.pending(3000));
  }

  @Test
  public void initialFarDoesNotLock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -90, 0, 12000);
    assertEquals(NONE, e.pending(12000));
  }

  @Test
  public void noSignalEverDoesNotLock() {
    assertEquals(NONE, new ProximityEngine(-65, -80).pending(900000));
  }

  @Test
  public void transientBusyDoesNotConsumeEvent() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    assertEquals(UNLOCK, e.pending(3000));
    feed(e, -50, 4000, 10000);
    assertEquals(UNLOCK, e.pending(10000));
    assertTrue(e.claim(UNLOCK, 10000));
    assertEquals(NONE, e.pending(10000));
  }

  @Test
  public void stableDepartureLocksAfterUnlock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    assertTrue(e.claim(UNLOCK, 3000));
    feed(e, -95, 4000, 17000);
    assertEquals(LOCK, e.pending(17000));
  }

  @Test
  public void cooldownDefersInsteadOfDropping() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    e.claim(UNLOCK, 3000);
    feed(e, -110, 4000, 13000);
    assertEquals(LOCK, e.pending(13000));
  }

  @Test
  public void gapThenFirstApproachStillWorks() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -90, 0, 9000);
    feed(e, -50, 30000, 33000);
    assertEquals(UNLOCK, e.pending(33000));
  }

  @Test
  public void lossLocksAfterTenSecondsOnlyOnce() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    e.claim(UNLOCK, 3000);
    assertEquals(NONE, e.pending(12000));
    assertEquals(LOCK, e.pending(13000));
    assertTrue(e.claim(LOCK, 13000));
    assertEquals(NONE, e.pending(90000));
  }

  @Test
  public void returnCancelsQueuedLossLock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    e.claim(UNLOCK, 3000);
    assertEquals(LOCK, e.pending(13000));
    e.sample(-50, 14000);
    assertFalse(e.stillValid(LOCK, 14000));
  }

  @Test
  public void noDuplicateSendDuringSustainedNear() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    e.claim(UNLOCK, 3000);
    feed(e, -50, 4000, 60000);
    assertEquals(NONE, e.pending(60000));
  }

  @Test
  public void duplicateAndOldAdvertisementsDoNotCount() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    e.sample(-50, 1000);
    e.sample(-50, 1000);
    e.sample(-50, 999);
    assertEquals(1, e.count());
    assertEquals(NONE, e.pending(5000));
  }

  @Test
  public void staleRssiDoesNotUnlock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    assertFalse(e.stillValid(UNLOCK, 9000));
  }

  @Test
  public void sparseNoiseCannotTriggerLossLock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    e.sample(-50, 0);
    e.sample(-50, 6000);
    assertEquals(NONE, e.pending(16000));
  }

  @Test
  public void strengthIsClampedAndUnknownIsZero() {
    assertEquals(0, ProximityEngine.strength(Double.NaN));
    assertEquals(0, ProximityEngine.strength(-110));
    assertEquals(100, ProximityEngine.strength(-20));
    assertEquals(50, ProximityEngine.strength(-65));
  }

  @Test(expected = IllegalArgumentException.class)
  public void thresholdsNeedHysteresis() {
    new ProximityEngine(-70, -75);
  }
}
