package com.dautolock.app.core;

import static org.junit.Assert.*;

import com.dautolock.link.LinkProtocol;
import org.junit.Test;

public class ParkUnlockTriggerTest {
  private static final int P = LinkProtocol.P,
      D = LinkProtocol.D,
      N = LinkProtocol.N,
      UNK = LinkProtocol.UNKNOWN;

  @Test
  public void firesOnceWhenStablePFollowsDriving() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    assertFalse(t.onSample(D, 0, 0)); // Driving.
    assertFalse(t.onSample(D, 0, 500));
    assertFalse(t.onSample(P, 0, 1000)); // Just parked — not yet stable.
    assertFalse(t.onSample(P, 0, 1500));
    assertTrue(t.onSample(P, 0, 3000)); // P held >= 2 s after driving → unlock.
    assertFalse(t.onSample(P, 0, 3500)); // Latched: no repeat while staying in P.
    assertFalse(t.onSample(P, 0, 9000));
  }

  @Test
  public void doesNotFireWhenConnectingAlreadyParked() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    for (long at = 0; at <= 10000; at += 500) assertFalse(t.onSample(P, 0, at));
  }

  @Test
  public void ignoresUntrustedGearReads() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    t.onSample(D, 0, 0);
    assertFalse(t.onSample(P, 1, 1000)); // quality != 0 — not trusted, no stable clock started.
    assertFalse(t.onSample(UNK, 0, 1500));
    assertFalse(t.onSample(P, 0, 2000)); // First trusted P; stable clock starts here.
    assertTrue(t.onSample(P, 0, 4100));
  }

  @Test
  public void neutralCountsAsDrivingNotPark() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    assertFalse(t.onSample(N, 0, 0)); // Neutral is not a park.
    assertFalse(t.onSample(P, 0, 500));
    assertTrue(t.onSample(P, 0, 2600));
  }

  @Test
  public void firesAgainForASecondParkAfterDrivingAgain() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    t.onSample(D, 0, 0);
    t.onSample(P, 0, 500);
    assertTrue(t.onSample(P, 0, 2600)); // First park.
    t.onSample(D, 0, 20000); // Drove off again (resets latch).
    t.onSample(P, 0, 20500);
    assertTrue(t.onSample(P, 0, 23000)); // Second park, well past dedup window.
  }

  @Test
  public void dedupesTwoParksWithinTenSeconds() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    t.onSample(D, 0, 0);
    t.onSample(P, 0, 500);
    assertTrue(t.onSample(P, 0, 2600)); // Fires at t=2600.
    t.onSample(D, 0, 3000); // Brief shift out of P (latch resets).
    t.onSample(P, 0, 3500);
    assertFalse(t.onSample(P, 0, 6000)); // Would be stable, but within 10 s of last fire.
  }

  @Test
  public void aLongSampleGapClearsDroveStateSoReturnDoesNotUnlock() {
    ParkUnlockTrigger t = new ParkUnlockTrigger();
    t.onSample(D, 0, 0); // Driving, then the link drops (phone walked away).
    // Reconnect much later while the car sits in P: must not unlock on return.
    for (long at = 200000; at <= 210000; at += 500) assertFalse(t.onSample(P, 0, at));
  }
}
