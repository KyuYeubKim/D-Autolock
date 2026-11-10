package com.dautolock.app.core;

import com.dautolock.link.LinkProtocol;

/**
 * How long the car has sat in P while the 차량 보조 앱 is reporting it, so the app can say when the car
 * has been left ON in P for {@link #HOLD_MS}. Fed every authenticated Bridge gear sample; thread-safe
 * (samples arrive on the link thread, the timed check and status reads on others).
 *
 * <ul>
 *   <li>Only a trustworthy live gear (quality 0) counts; an untrusted read holds the state.
 *   <li>A P run starts at the first trusted P; any trusted non-P gear ends it (the car is driving).
 *   <li>The run stays open while the link is down: the phone walking away from a car left running is
 *       exactly the case worth a notice, and nobody can shift out of P from outside the car.
 *   <li>Samples resuming after a gap over {@link #GAP_MS} start a fresh run: the car may have been
 *       switched off and on meanwhile, so the old start time no longer means anything.
 *   <li>At most one notice per run ({@link #markNotified}).
 * </ul>
 */
public final class ParkedOnWatch {
  public static final long HOLD_MS = 5 * 60 * 1000, GAP_MS = 60000;
  public static final int NONE = 0, STARTED = 1, ENDED = 2;

  private long since, lastSampleAt = Long.MIN_VALUE;
  private int run;
  private boolean running, notified;

  /**
   * @param gear LinkProtocol gear constant from the sample
   * @param quality 0 means the gear read is trustworthy
   * @param now monotonic time (SystemClock.elapsedRealtime)
   * @return {@link #STARTED} when this sample began a P run, {@link #ENDED} when it ended one without
   *     starting another, otherwise {@link #NONE}
   */
  public synchronized int onSample(int gear, int quality, long now) {
    boolean ended = false;
    if (lastSampleAt != Long.MIN_VALUE && now - lastSampleAt > GAP_MS && running) {
      reset();
      ended = true;
    }
    lastSampleAt = now;
    if (quality != 0 || gear == LinkProtocol.UNKNOWN) return ended ? ENDED : NONE;
    if (gear != LinkProtocol.P) {
      if (running) {
        reset();
        ended = true;
      }
      return ended ? ENDED : NONE;
    }
    if (running) return NONE;
    running = true;
    since = now;
    run++;
    notified = false;
    return STARTED;
  }

  /** The current run's id (changes whenever a run starts or ends). */
  public synchronized int run() {
    return run;
  }

  public synchronized boolean active() {
    return running;
  }

  public synchronized boolean notified() {
    return notified;
  }

  /** The given run is still current, has lasted {@link #HOLD_MS}, and has not been reported yet. */
  public synchronized boolean due(int run, long now) {
    return run == this.run && running && !notified && now - since >= HOLD_MS;
  }

  /** Reports the run once; false if it is no longer current or was already reported. */
  public synchronized boolean markNotified(int run) {
    if (run != this.run || !running || notified) return false;
    notified = true;
    return true;
  }

  /** The car was seen OFF or moving: the run is over. Returns whether a reported run ended. */
  public synchronized boolean end() {
    boolean reported = running && notified;
    if (running) reset();
    return reported;
  }

  private void reset() {
    running = false;
    notified = false;
    run++;
  }
}
