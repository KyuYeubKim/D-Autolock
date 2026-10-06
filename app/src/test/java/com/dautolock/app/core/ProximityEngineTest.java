package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProximityEngineTest {
  private ProximityEngine.Action feed(ProximityEngine e, int rssi, long start, long end) {
    ProximityEngine.Action found = ProximityEngine.Action.NONE;
    for (long t = start; t <= end; t += 1000) {
      ProximityEngine.Action a = e.sample(rssi, t);
      if (a != ProximityEngine.Action.NONE) found = a;
    }
    return found;
  }

  @Test
  public void startupDoesNotOperateDoors() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    assertEquals(ProximityEngine.Action.NONE, feed(e, -55, 0, 12000));
    assertEquals(ProximityEngine.Action.NONE, feed(new ProximityEngine(-65, -80), -90, 0, 12000));
  }

  @Test
  public void stableApproachUnlocksAndDepartureLocks() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -90, 0, 10000);
    assertEquals(ProximityEngine.Action.UNLOCK, feed(e, -50, 11000, 21000));
    feed(e, -50, 22000, 54000);
    assertEquals(ProximityEngine.Action.LOCK, feed(e, -90, 55000, 69000));
  }

  @Test
  public void interruptionIsNotDepartureOrApproach() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -55, 0, 8000);
    assertFalse(e.stillValid(ProximityEngine.Action.UNLOCK, 16000));
    assertEquals(ProximityEngine.Action.NONE, feed(e, -90, 16000, 30000));
  }

  @Test
  public void weakSingleSampleDoesNotLock() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -55, 0, 8000);
    assertEquals(ProximityEngine.Action.NONE, e.sample(-105, 9000));
    assertEquals(ProximityEngine.Action.NONE, feed(e, -55, 10000, 20000));
  }

  @Test
  public void noisyMiddleDoesNotTrigger() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -90, 0, 8000);
    assertEquals(ProximityEngine.Action.NONE, feed(e, -70, 9000, 40000));
  }

  @Test
  public void cooldownSuppressesRapidOppositeCommand() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -90, 0, 8000);
    feed(e, -50, 9000, 19000);
    assertEquals(ProximityEngine.Action.NONE, feed(e, -100, 20000, 32000));
  }

  @Test
  public void staleSignalCancelsQueuedAction() {
    ProximityEngine e = new ProximityEngine(-65, -80);
    feed(e, -90, 0, 8000);
    feed(e, -50, 9000, 18000);
    assertTrue(e.stillValid(ProximityEngine.Action.UNLOCK, 18000));
    assertFalse(e.stillValid(ProximityEngine.Action.UNLOCK, 24000));
  }

  @Test(expected = IllegalArgumentException.class)
  public void thresholdsNeedHysteresis() {
    new ProximityEngine(-70, -75);
  }
}
