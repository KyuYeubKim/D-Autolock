package com.dautolock.app.core;

import static org.junit.Assert.*;

import com.dautolock.link.LinkProtocol;
import org.junit.Test;

public class ParkedOnWatchTest {
  private static final int P = LinkProtocol.P, D = LinkProtocol.D, UNK = LinkProtocol.UNKNOWN;
  private static final long HOLD = ParkedOnWatch.HOLD_MS;

  @Test
  public void aPRunIsDueAfterFiveMinutesAndReportedOnce() {
    ParkedOnWatch w = new ParkedOnWatch();
    assertEquals(ParkedOnWatch.STARTED, w.onSample(P, 0, 1000));
    int run = w.run();
    assertEquals(ParkedOnWatch.NONE, w.onSample(P, 0, 1500)); // Same run continues.
    assertFalse(w.due(run, 1000 + HOLD - 1));
    assertTrue(w.due(run, 1000 + HOLD));
    assertTrue(w.markNotified(run));
    assertFalse(w.markNotified(run)); // One notice per run.
    assertFalse(w.due(run, 1000 + 3 * HOLD));
  }

  @Test
  public void drivingEndsTheRunAndUntrustedReadsHoldIt() {
    ParkedOnWatch w = new ParkedOnWatch();
    assertEquals(ParkedOnWatch.NONE, w.onSample(D, 0, 0)); // Driving: nothing to watch.
    assertEquals(ParkedOnWatch.STARTED, w.onSample(P, 0, 1000));
    int run = w.run();
    assertEquals(ParkedOnWatch.NONE, w.onSample(D, 2, 2000)); // Untrusted gear: still in the run.
    assertEquals(ParkedOnWatch.NONE, w.onSample(UNK, 0, 3000));
    assertTrue(w.active());
    assertEquals(ParkedOnWatch.ENDED, w.onSample(D, 0, 4000)); // Shifted out of P.
    assertFalse(w.active());
    assertFalse(w.due(run, 1000 + HOLD));
    assertFalse(w.markNotified(run));
  }

  @Test
  public void theRunOutlivesALostLinkButAReturnAfterAGapStartsAgain() {
    ParkedOnWatch w = new ParkedOnWatch();
    w.onSample(P, 0, 0);
    int run = w.run();
    // The phone walked away from the car at 1 s; no samples since. The run is still timed.
    assertTrue(w.due(run, HOLD));
    assertTrue(w.markNotified(run));
    // Back at the car 10 minutes later, still P: the car may have been off meanwhile, so it starts
    // over and may be reported again after another 5 minutes.
    assertEquals(ParkedOnWatch.STARTED, w.onSample(P, 0, 2 * HOLD));
    int again = w.run();
    assertNotEquals(run, again);
    assertFalse(w.due(again, 2 * HOLD + HOLD - 1));
    assertTrue(w.due(again, 3 * HOLD));
  }

  @Test
  public void endReportsWhetherAShownNoticeShouldBeWithdrawn() {
    ParkedOnWatch w = new ParkedOnWatch();
    assertFalse(w.end()); // Nothing running.
    w.onSample(P, 0, 0);
    assertFalse(w.end()); // Ended before any notice.
    w.onSample(P, 0, 1000);
    int run = w.run();
    assertTrue(w.markNotified(run));
    assertTrue(w.end()); // A notice was shown for this run.
    assertFalse(w.active());
    // A stale run id cannot touch a newer run.
    w.onSample(P, 0, 2000);
    assertFalse(w.markNotified(run));
    assertTrue(w.due(w.run(), 2000 + HOLD));
  }
}
