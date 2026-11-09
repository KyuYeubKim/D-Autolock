package com.dautolock.app;

import static com.dautolock.app.VehicleLink.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VehicleLinkPaceTest {
  @Test
  public void carSwitchedOffWaitsTheLongIntervalAtOnce() {
    // Real logs 11-08..11-10: right after switching off, the 5 s reconnects kept the phone paging
    // the dead head unit ~56% of the time and the BLE scan next to the car went quiet.
    assertEquals(RECONNECT_AWAY_MS, retryDelayMs(RECONNECT_MIN_MS, true, 0, Boolean.FALSE));
    assertEquals(RECONNECT_AWAY_MS, retryDelayMs(3000, false, 10000, Boolean.FALSE));
  }

  @Test
  public void carOnKeepsTheShortCapEvenAfterALongAbsence() {
    assertEquals(RECONNECT_MIN_MS, retryDelayMs(RECONNECT_AWAY_MS, true, 0, Boolean.TRUE));
    assertEquals(RECONNECT_MAX_MS, retryDelayMs(RECONNECT_AWAY_MS, false, 3_600_000, Boolean.TRUE));
    assertEquals(3000, retryDelayMs(RECONNECT_MIN_MS, false, 3_600_000, Boolean.TRUE));
  }

  @Test
  public void unknownCarAudioKeepsTheEarlierPacing() {
    assertEquals(RECONNECT_MIN_MS, retryDelayMs(RECONNECT_MAX_MS, true, 0, null));
    assertEquals(3000, retryDelayMs(RECONNECT_MIN_MS, false, 1000, null));
    assertEquals(RECONNECT_MAX_MS, retryDelayMs(RECONNECT_MAX_MS, false, AWAY_AFTER_MS - 1, null));
    assertEquals(10000, retryDelayMs(RECONNECT_MAX_MS, false, AWAY_AFTER_MS, null));
    assertEquals(RECONNECT_AWAY_MS, retryDelayMs(RECONNECT_AWAY_MS, false, AWAY_AFTER_MS, null));
  }
}
