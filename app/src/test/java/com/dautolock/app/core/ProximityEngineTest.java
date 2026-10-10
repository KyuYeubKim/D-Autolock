package com.dautolock.app.core;

import static com.dautolock.app.core.ProximityEngine.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ProximityEngineTest {
  @Test
  public void preparationStartsBeforeUnlockButDoesNotRelaxUnlockThreshold() {
    ProximityEngine e = new ProximityEngine(-65, -80, 1000, 2000, 10000);
    e.sample(-72, 0);
    e.sample(-72, 100);
    e.sample(-72, 200);
    assertFalse(e.approaching(200));
    e.sample(-72, 300);
    assertTrue(e.approaching(300));
    assertEquals(NONE, e.pending(300));
    e.sample(-40, 400); // Crosses threshold; preparation must survive candidate counter reset.
    assertTrue(e.approaching(400));
    assertEquals(NONE, e.pending(400));
    for (int t = 500; t <= 1500; t += 100) e.sample(-40, t);
    assertEquals(UNLOCK, e.pending(1500));
    e.claim(UNLOCK, 1500);
    assertFalse(e.approaching(1500));
    e.sample(-72, 17000);
    assertFalse(e.approaching(17000)); // A gap needs four new observations.
  }

  @Test
  public void preparationNeverRunsInFarZoneOrWithoutFreshSignal() {
    ProximityEngine e = new ProximityEngine(-65, -73, 0, 0, 10000);
    feed(e, -73, 0, 5000);
    assertFalse(e.approaching(5000));
    feed(e, -70, 6000, 9000);
    assertTrue(e.approaching(9000));
    assertFalse(e.approaching(15000));
  }

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
    // Leaving range right after a confirmed departure keeps it (phone walked away).
    assertTrue(e.departureConfirmed(15000));
    feed(e, -50, 15000, 19000);
    assertFalse(e.departureConfirmed(19000)); // The phone came back.
  }

  @Test
  public void departureLatchExpiresAndSignalLossAloneNeverSetsIt() {
    ProximityEngine e = new ProximityEngine(-60, -80, 0, 1000, 10000);
    feed(e, -50, 0, 3000);
    assertFalse(e.departureConfirmed(30000)); // Lost while near: not a departure.
    feed(e, -100, 31000, 36000);
    assertTrue(e.departureConfirmed(36000));
    assertTrue(e.departureConfirmed(33000 + ProximityEngine.DEPARTURE_LATCH_MS)); // Set at 34 s.
    assertFalse(e.departureConfirmed(36000 + ProximityEngine.DEPARTURE_LATCH_MS + 5000));
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

  @Test
  public void walkingAwayWithFewFarSamplesAfterGapStillLocksOnSignalLoss() {
    // Real log 21:54: NEAR, a 5 s reception gap reset `stable`, three far samples, then lost.
    ProximityEngine e = new ProximityEngine(-70, -80, 0, 2000, 10000);
    feed(e, -55, 0, 6000);
    assertTrue(e.claim(UNLOCK, 6000));
    e.sample(-83, 12000);
    e.sample(-79, 15000);
    e.sample(-78, 17000);
    assertEquals(NONE, e.pending(20000));
    assertEquals(LOCK, e.pending(27000)); // 10 s after the last sample.
  }

  @Test
  public void neverStableReceptionStillDoesNotLockOnLoss() {
    ProximityEngine e = new ProximityEngine(-70, -80, 0, 2000, 10000);
    e.sample(-75, 0);
    e.sample(-75, 1000);
    e.sample(-75, 2000);
    e.sample(-75, 3000);
    assertEquals(NONE, e.pending(30000));
  }

  @Test
  public void standingByTheCarAfterUnlockNeedsTwentySecondsFarBeforeLocking() {
    // Real log 21:21: unlock, then -80..-87 for a few seconds while standing still → lock.
    ProximityEngine e = new ProximityEngine(-70, -80, 0, 2000, 10000);
    feed(e, -65, 0, 4000);
    assertTrue(e.claim(UNLOCK, 4000));
    for (long t = 20000; t <= 30000; t += 500) e.sample(-86, t);
    assertEquals(NONE, e.pending(30000)); // 10 s far: still treated as standing nearby.
    for (long t = 30500; t <= 40500; t += 500) e.sample(-86, t);
    assertEquals(LOCK, e.pending(40500)); // 20 s continuously far.
    // Outside the 2-minute window the configured 2 s applies again.
    ProximityEngine later = new ProximityEngine(-70, -80, 0, 2000, 10000);
    feed(later, -65, 0, 4000);
    assertTrue(later.claim(UNLOCK, 4000));
    for (long t = 130000; t <= 133000; t += 500) later.sample(-86, t);
    assertEquals(LOCK, later.pending(133000));
  }

  @Test
  public void reUnlockRightAfterLockNeedsThreeSecondsNear() {
    ProximityEngine e = new ProximityEngine(-70, -80, 0, 2000, 10000);
    feed(e, -65, 0, 4000);
    e.alreadySatisfied(UNLOCK, 4000);
    feed(e, -90, 5000, 11000);
    assertEquals(LOCK, e.pending(11000));
    assertTrue(e.claim(LOCK, 11000));
    for (long t = 20000; t <= 21500; t += 250) e.sample(-60, t);
    assertEquals(NONE, e.pending(21500));
    for (long t = 21750; t <= 23250; t += 250) e.sample(-60, t);
    assertEquals(UNLOCK, e.pending(23250));
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
    // Within 2 minutes of the unlock, 20 s continuously far is required (anti-flap).
    assertEquals(NONE, e.pending(17000));
    feed(e, -95, 18000, 28000); // Smoothed signal is FAR from 7 s, so 20 s far at 27 s.
    assertEquals(LOCK, e.pending(28000));
  }

  @Test
  public void cooldownDefersInsteadOfDropping() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -50, 0, 3000);
    e.claim(UNLOCK, 3000);
    feed(e, -110, 4000, 25000);
    assertEquals(LOCK, e.pending(25000));
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
    new ProximityEngine(-70, -73); // Gap below MIN_GAP_DB (5) is rejected.
  }

  @Test
  public void fiveDbGapIsAllowedForEarlierLocking() {
    new ProximityEngine(-75, -80); // near/far 5 dB apart: valid, locks closer.
  }
}
