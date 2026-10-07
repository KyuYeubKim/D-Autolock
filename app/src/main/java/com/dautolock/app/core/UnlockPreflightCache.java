package com.dautolock.app.core;

/** A short-lived, single-use observation for automatic unlock only. Never persisted. */
public final class UnlockPreflightCache {
  public static final long RECEIVED_MAX_MS = 5000, MEASURED_MAX_MS = 8000;

  public static final class Entry {
    public final VehicleSnapshot state;
    private final long receivedElapsed;

    private Entry(VehicleSnapshot state, long receivedElapsed) {
      this.state = state;
      this.receivedElapsed = receivedElapsed;
    }

    public boolean usable(long elapsed, long wall) {
      return elapsed >= receivedElapsed
          && elapsed - receivedElapsed <= RECEIVED_MAX_MS
          && state.measuredAt > 0
          && wall >= state.measuredAt
          && wall - state.measuredAt <= MEASURED_MAX_MS
          && wall >= state.receivedAt
          && wall - state.receivedAt <= RECEIVED_MAX_MS
          && state.automaticBlock(false, wall) == null
          && Boolean.TRUE.equals(state.locked)
          && Boolean.TRUE.equals(state.doorsClosed);
    }
  }

  private long revision;
  private int session;
  private String vehicle = "";
  private Entry entry;

  public synchronized long revision() {
    return revision;
  }

  /** Invalidates both a stored observation and any outstanding query using an older revision. */
  public synchronized void invalidate() {
    revision++;
    entry = null;
  }

  /** A newer observation supersedes stored data; queries run on the same serial executor. */
  public synchronized void observed() {
    entry = null;
  }

  public synchronized boolean offer(
      long expectedRevision,
      int session,
      String vehicle,
      VehicleSnapshot state,
      long elapsed,
      long wall) {
    if (revision != expectedRevision) return false;
    entry = null;
    Entry proposed = new Entry(state, elapsed);
    if (!proposed.usable(elapsed, wall)) return false;
    this.session = session;
    this.vehicle = vehicle;
    entry = proposed;
    return true;
  }

  public synchronized boolean available(int session, String vehicle, long elapsed, long wall) {
    return entry != null
        && this.session == session
        && this.vehicle.equals(vehicle)
        && entry.usable(elapsed, wall);
  }

  public synchronized Entry take(int session, String vehicle, long elapsed, long wall) {
    Entry result = available(session, vehicle, elapsed, wall) ? entry : null;
    invalidate();
    return result;
  }
}
