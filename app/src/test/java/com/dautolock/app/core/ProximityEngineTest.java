package com.dautolock.app.core;

import static com.dautolock.app.core.ProximityEngine.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ProximityEngineTest {
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
