package com.dautolock.app.core;

import static com.dautolock.app.core.ProximityEngine.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ProximityEngineTest {
  @Test
  public void strongerThanNeedsAFreshReadingAtThatLevel() {
    ProximityEngine e = new ProximityEngine(-70, -80);
    assertFalse(e.strongerThan(-62, 1000)); // Nothing received yet.
    e.sample(-55, 1000);
    assertTrue(e.strongerThan(-62, 1000));
    assertFalse(e.strongerThan(-50, 1000));
    assertFalse(e.strongerThan(-62, 1000 + 6000)); // Not receiving any more.
  }

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
  public void lastReadingNearReflectsSignalStrengthWhenLost() {
    ProximityEngine e = new ProximityEngine(-75, -80, 1000, 2000, 10000);
    assertFalse(e.lastReadingNear()); // No samples yet.
    feed(e, -55, 0, 4000); // Phone close.
    assertTrue(e.lastReadingNear()); // Strong signal: a loss here is a Bluetooth glitch near the car.
    feed(e, -92, 5000, 9000); // Walked away to far.
    assertFalse(e.lastReadingNear()); // Weak signal before loss: a real departure must still lock.
  }

  @Test
  public void midRangeRecoveryDoesNotCancelLatchedDeparture() {
    ProximityEngine e = new ProximityEngine(-60, -80, 0, 1000, 10000);
    feed(e, -50, 0, 3000); // Near: seenNear.
    e.alreadySatisfied(UNLOCK, 3000);
    feed(e, -100, 4000, 9000); // Far: departure confirmed, latch set.
    assertTrue(e.departureConfirmed(9000));
    // A stray mid-range recovery (above far, still well below near) must not drop the departure
    // latch; otherwise a single borderline sample during the Stop delay cancels a correct Stop.
    feed(e, -70, 10000, 14000);
    // Signal is then lost; still inside the 5-minute latch window, so the departure still counts.
    assertTrue(e.departureConfirmed(40000));
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
  public void alreadyOpenStaysHandledThroughAWobbleDuringTheRead() {
    // Real log 11-09 10:40 (beacon): already unlocked, but the signal sat between the thresholds
    // during the ~2 s read, so the zone was not marked; back near, the engine asked again and
    // reopened the car after its own 30 s relock.
    ProximityEngine e = new ProximityEngine(-75, -85, 1000, 2000, 5000);
    feed(e, -60, 0, 4000);
    assertEquals(UNLOCK, e.pending(4000));
    e.beginCheck(UNLOCK, 4000);
    feed(e, -84, 5000, 9000); // Between the thresholds, past the small-dip allowance.
    assertFalse(e.stillValid(UNLOCK, 9000));
    e.alreadySatisfied(UNLOCK, 9000);
    e.endCheck();
    assertEquals("NEAR", e.handled());
    feed(e, -60, 10000, 16000);
    assertEquals(NONE, e.pending(16000));
  }

  @Test
  public void alreadyOpenIsNotMarkedWhenThePhoneTurnedAroundDuringTheRead() {
    ProximityEngine e = new ProximityEngine(-75, -85, 1000, 2000, 5000);
    feed(e, -60, 0, 4000);
    e.beginCheck(UNLOCK, 4000);
    feed(e, -95, 5000, 9000); // Walked away: a fresh far reading after the check began.
    e.alreadySatisfied(UNLOCK, 9000);
    assertEquals("UNKNOWN", e.handled());
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
  public void farForCountsOnlyAContinuousFreshFarStretch() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -60, 0, 3000);
    assertEquals(0, e.farFor(3000));
    feed(e, -95, 4000, 9000); // Smoothing reaches far after a couple of samples.
    long far = e.farFor(9000);
    assertTrue(String.valueOf(far), far >= 3000 && far <= 5000);
    feed(e, -60, 10000, 14000); // Back near: the stretch ends.
    assertEquals(0, e.farFor(14000));
    feed(e, -95, 15000, 22000);
    assertTrue(e.farFor(22000) > 0);
    assertEquals(0, e.farFor(22000 + ProximityEngine.STALE_MS + 1)); // Lost: not "far".
  }

  @Test
  public void fiveDbGapIsAllowedForEarlierLocking() {
    new ProximityEngine(-75, -80); // near/far 5 dB apart: valid, locks closer.
  }

  @Test
  public void aClaimedCommandStaysSendableOnTheSecondChannelForAWhile() {
    // claim() consumes the proposal; the cloud fallback after a failed BLE key attempt still has to
    // be able to send the same command (real log 11-08: it was cancelled every time).
    ProximityEngine e = new ProximityEngine(-75, -85, 0, 2000, 5000);
    feed(e, -50, 0, 3000);
    assertTrue(e.claim(UNLOCK, 3000));
    assertFalse(e.stillValid(UNLOCK, 3500)); // Consumed...
    assertTrue(e.canDispatch(UNLOCK, 3500)); // ...but still this command's to finish.
    assertFalse(e.canDispatch(LOCK, 3500));
    // Silence does not withdraw it: the BLE key session itself blanks the car's advertisement.
    assertTrue(e.canDispatch(UNLOCK, 9000));
    assertFalse(e.canDispatch(UNLOCK, 3000 + ProximityEngine.CLAIM_HOLD_MS + 1));
  }

  @Test
  public void aClaimedLockIsWithdrawnOnceThePhoneReadsNearAgain() {
    ProximityEngine e = new ProximityEngine(-75, -85, 0, 2000, 5000);
    feed(e, -50, 0, 3000);
    e.alreadySatisfied(UNLOCK, 3000);
    assertEquals(LOCK, e.pending(8000)); // 5 s without a sample.
    assertTrue(e.claim(LOCK, 8000));
    // The last reading (near, 5 s old) predates the claim: it is not a return to the car.
    assertTrue(e.canDispatch(LOCK, 8000));
    assertTrue(e.canDispatch(LOCK, 9000));
    e.sample(-50, 9500); // Back by the car before the fallback went out.
    assertFalse(e.canDispatch(LOCK, 9600));
  }

  @Test
  public void lossLockAtTheExactStaleBoundaryIsStillReportedAsALoss() {
    // With a 5 s loss-lock the lock is due at exactly 5000 ms, where fresh() (<= 5000) still holds;
    // gates that asked "!fresh" for "is this a signal-loss lock?" missed that moment.
    ProximityEngine e = new ProximityEngine(-75, -85, 0, 2000, 5000);
    feed(e, -50, 0, 3000);
    e.alreadySatisfied(UNLOCK, 3000);
    assertFalse(e.lossLockPending(7999));
    assertTrue(e.fresh(8000));
    assertEquals(LOCK, e.pending(8000));
    assertTrue(e.lossLockPending(8000));
  }

  @Test
  public void savedThresholdsDoNotReopenAnApproachAlreadyHandled() {
    // Real log 11-08 17:48: unlocked, the car re-locked itself 30 s later, the user saved thresholds
    // while standing at the car, and the rebuilt engine opened it again as a "new" approach.
    ProximityEngine before = new ProximityEngine(-73, -83, 1000, 2000, 5000);
    feed(before, -60, 0, 3000);
    assertTrue(before.claim(UNLOCK, 3000));

    ProximityEngine fresh = new ProximityEngine(-75, -85, 1000, 2000, 5000);
    feed(fresh, -60, 40000, 43000);
    assertEquals(UNLOCK, fresh.pending(43000)); // Without the carry-over: reopens (the bug).

    ProximityEngine after = new ProximityEngine(-75, -85, 1000, 2000, 5000);
    after.continueFrom(before);
    assertEquals("NEAR", after.handled());
    feed(after, -60, 40000, 43000);
    assertEquals(NONE, after.pending(43000)); // Same visit, already handled.
    // Walking away still locks under the new thresholds (the phone was near this visit); the
    // carried unlock time keeps the 20 s anti-flap far dwell, as without the save.
    feed(after, -95, 44000, 60000);
    assertEquals(NONE, after.pending(60000));
    feed(after, -95, 61000, 70000);
    assertEquals(LOCK, after.pending(70000));
    // And coming back after that opens again.
    assertTrue(after.claim(LOCK, 70000));
    feed(after, -60, 90000, 94000);
    assertEquals(UNLOCK, after.pending(94000));
  }

  @Test
  public void savedThresholdsDoNotCarryDepartureEvidenceForTheAutomaticStop() {
    ProximityEngine before = new ProximityEngine(-60, -80, 0, 1000, 10000);
    feed(before, -50, 0, 3000);
    before.alreadySatisfied(UNLOCK, 3000);
    feed(before, -100, 4000, 9000);
    assertTrue(before.departureConfirmed(9000));
    assertTrue(before.claim(LOCK, 9000));

    ProximityEngine after = new ProximityEngine(-62, -82, 0, 1000, 10000);
    after.continueFrom(before);
    assertEquals("FAR", after.handled());
    // The latch measured with the old values is gone until new far evidence arrives.
    assertFalse(after.departureConfirmed(15000));
  }
}
