package com.dautolock.app.watchble;

/**
 * Process-wide record of our own GATT sessions with the car's BLE-key module.
 *
 * <p>The module that the watch key connects to is the same advertiser the app uses as the "vehicle
 * BLE" distance signal. While our connection is up it stops advertising, so the distance signal
 * goes blank for ~5 s after every BLE-key command (real log 11-08: all 8 sessions). That gap is
 * ours, not the phone leaving, so a signal-loss lock must not be taken from it.
 *
 * <p>Fail-safe: a session that never reports its end stops counting after {@link #MAX_SESSION_MS}, so
 * a bug here can only delay a signal-loss lock, never suppress it for good. Times are {@code
 * SystemClock.elapsedRealtime()} milliseconds.
 */
public final class BleAirtime {
  /** How long after a session ends the module is given to resume advertising (observed ~4 s). */
  public static final long AFTER_MS = 8000;

  /** Longer than the manual 35 s handshake timeout; an unclosed session stops counting after it. */
  public static final long MAX_SESSION_MS = 40000;

  private static int active;
  private static long activeSince = -1;
  private static long lastEnd = -1;

  private BleAirtime() {}

  /** A connection attempt to the car's BLE-key module is starting. */
  public static synchronized void begin(long now) {
    if (active == 0) activeSince = now;
    active++;
  }

  /** That connection is closed (success, failure, timeout or cancel). */
  public static synchronized void end(long now) {
    if (active > 0) active--;
    if (active == 0) activeSince = -1;
    lastEnd = now;
  }

  /** Our own connection is (or was just) holding the car's advertiser quiet. */
  public static synchronized boolean quiet(long now) {
    boolean connected = active > 0 && now >= activeSince && now - activeSince < MAX_SESSION_MS;
    boolean settling = lastEnd >= 0 && now >= lastEnd && now - lastEnd < AFTER_MS;
    return connected || settling;
  }

  /** Test-only: the state is static and Robolectric reuses the JVM between tests. */
  public static synchronized void reset() {
    active = 0;
    activeSince = -1;
    lastEnd = -1;
  }
}
