package com.dautolock.link;

import static org.junit.Assert.*;

import org.junit.Test;

public class LinkStateTest {
  private LinkProtocol.Sample p() {
    return new LinkProtocol.Sample(0, -1, 1, 3, -1, 0);
  }

  private LinkState parked() {
    LinkState s = new LinkState();
    s.accept(p(), 0, 0);
    s.accept(p(), 1000, 1000);
    s.accept(p(), 2000, 2000);
    return s;
  }

  @Test
  public void stableFreshPRequired() {
    LinkState s = new LinkState();
    s.accept(p(), 0, 0);
    assertNotNull(s.block(500));
    s.accept(p(), 1000, 1000);
    assertNotNull(s.block(2000));
    s.accept(p(), 2000, 2000);
    assertNull(s.block(2000));
  }

  @Test
  public void oldDisconnectedAndBackwardClockInvalidateP() {
    LinkState s = parked();
    assertNotNull(s.block(5001));
    assertNotNull(s.block(1999));
    s.clear();
    assertNotNull(s.block(2000));
  }

  @Test
  public void anyNonPUnknownConflictOrReleasedBrakeResetsStability() {
    for (LinkProtocol.Sample bad :
        new LinkProtocol.Sample[] {
          new LinkProtocol.Sample(3, -1, 4, 2, -1, 0),
          new LinkProtocol.Sample(-1, -1, -1, -1, -1, 1),
          new LinkProtocol.Sample(-1, -1, 1, 1, -1, 3),
          new LinkProtocol.Sample(0, 0, 1, 3, 0, 0)
        }) {
      LinkState s = parked();
      s.accept(bad, 2200, 2200);
      assertNotNull(s.block(2200));
      s.accept(p(), 2300, 2300);
      assertNotNull(s.block(2300));
    }
  }

  @Test
  public void slowResponseUsesRequestTimeAndInvalidatesOldP() {
    LinkState s = parked();
    assertFalse(s.accept(p(), 2500, 4501));
    assertNotNull(s.block(4501));
  }

  @Test
  public void staleGapRestartsStableInterval() {
    LinkState s = parked();
    s.accept(p(), 5001, 5001);
    assertNotNull(s.block(5001));
  }

  @Test
  public void sampleAgeIncludesTransitTime() {
    LinkState s = parked();
    s.accept(p(), 2500, 4000);
    assertNull(s.block(5000));
    assertNotNull(s.block(5501));
  }
}
