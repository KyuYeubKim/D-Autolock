package com.dautolock.app.core;

/**
 * A short-lived, single-use observation for automatic unlock only. Never persisted.
 *
 * <p>Real logs: the approach read often finished 6 s before the unlock threshold, so a 5 s limit
 * forced a fresh 3 s query while the user stood at the door. A parked, locked, powered-OFF car does
 * not change within ~20 s in a way that makes unlocking unsafe, and every other unlock check
 * (signal, dwell, single-use, re-check at dispatch) still applies.
 */
public final class UnlockPreflightCache {
  public static final long RECEIVED_MAX_MS = 20000, MEASURED_MAX_MS = 25000;
  /** Start a new approach read once the stored one is this old, so it never quite expires. */
  public static final long REFRESH_AFTER_MS = 12000;

  public static final class Entry {
    public final VehicleSnapshot state;
    public final String source;
    private final long receivedElapsed;

    private Entry(VehicleSnapshot state, long receivedElapsed, String source) {
      this.state = state;
      this.receivedElapsed = receivedElapsed;
      this.source = source;
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
    return offer(expectedRevision, session, vehicle, state, elapsed, wall, "approach_prefetch");
  }

  public synchronized boolean offer(
      long expectedRevision,
      int session,
      String vehicle,
      VehicleSnapshot state,
      long elapsed,
      long wall,
      String source) {
    if (revision != expectedRevision) return false;
    entry = null;
    Entry proposed = new Entry(state, elapsed, source);
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

  /** Available and still young enough that no refresh read is needed yet. */
  public synchronized boolean young(int session, String vehicle, long elapsed, long wall) {
    return available(session, vehicle, elapsed, wall)
        && elapsed - entry.receivedElapsed < REFRESH_AFTER_MS;
  }

  public synchronized Entry take(int session, String vehicle, long elapsed, long wall) {
    Entry result = available(session, vehicle, elapsed, wall) ? entry : null;
    invalidate();
    return result;
  }
}
