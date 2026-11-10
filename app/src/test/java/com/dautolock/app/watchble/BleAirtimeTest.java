package com.dautolock.app.watchble;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BleAirtimeTest {
  @Before
  public void clear() {
    BleAirtime.reset();
  }

  @After
  public void cleanUp() {
    BleAirtime.reset();
  }

  @Test
  public void quietDuringTheSessionAndBrieflyAfter() {
    long t = 100_000;
    assertFalse(BleAirtime.quiet(t));
    BleAirtime.begin(t);
    assertTrue(BleAirtime.quiet(t + 1_500)); // Handshake in progress.
    BleAirtime.end(t + 2_000);
    assertTrue(BleAirtime.quiet(t + 6_000)); // Advertiser still resuming (observed ~4 s).
    assertTrue(BleAirtime.quiet(t + 2_000 + BleAirtime.AFTER_MS - 1));
    assertFalse(BleAirtime.quiet(t + 2_000 + BleAirtime.AFTER_MS));
  }

  @Test
  public void anUnclosedSessionStopsCountingSoALockIsNeverSuppressedForGood() {
    long t = 100_000;
    BleAirtime.begin(t);
    assertTrue(BleAirtime.quiet(t + BleAirtime.MAX_SESSION_MS - 1));
    assertFalse(BleAirtime.quiet(t + BleAirtime.MAX_SESSION_MS));
    assertFalse(BleAirtime.quiet(t + 10 * BleAirtime.MAX_SESSION_MS));
  }

  @Test
  public void overlappingSessionsStayQuietUntilTheLastOneEnds() {
    long t = 100_000;
    BleAirtime.begin(t); // Automatic command.
    BleAirtime.begin(t + 500); // Manual test pressed meanwhile.
    BleAirtime.end(t + 2_000);
    assertTrue(BleAirtime.quiet(t + 2_000 + BleAirtime.AFTER_MS + 1_000)); // One still connected.
    BleAirtime.end(t + 12_000);
    assertTrue(BleAirtime.quiet(t + 12_000 + BleAirtime.AFTER_MS - 1));
    assertFalse(BleAirtime.quiet(t + 12_000 + BleAirtime.AFTER_MS));
  }

  @Test
  public void aClockFromBeforeTheSessionIsNotQuiet() {
    BleAirtime.begin(100_000);
    BleAirtime.end(101_000);
    assertFalse(BleAirtime.quiet(50_000)); // e.g. a reset test clock.
  }

  @Test
  public void aHeldStartAuthorizationStaysQuietPastTheSessionCapUntilItEnds() {
    // The key is kept connected up to 2 minutes; the module stays silent all that time.
    BleAirtime.begin(1000);
    BleAirtime.holdUntil(1000 + 120000);
    assertTrue(BleAirtime.holding(1000 + 60000));
    assertTrue(BleAirtime.quiet(1000 + BleAirtime.MAX_SESSION_MS + 5000)); // Past the 40 s cap.
    assertFalse(BleAirtime.holding(1000 + 120000)); // A fixed end: never suppressed for good.
    // Released early (the car came on): only the usual settling time is left.
    BleAirtime.holdUntil(-1);
    BleAirtime.end(70000);
    assertFalse(BleAirtime.holding(70001));
    assertTrue(BleAirtime.quiet(70000 + BleAirtime.AFTER_MS - 1));
    assertFalse(BleAirtime.quiet(70000 + BleAirtime.AFTER_MS + 1));
  }
}
