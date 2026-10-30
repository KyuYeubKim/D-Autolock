package com.dautolock.app;

/** Where a smoothed RSSI sits against its own unlock (near) and lock (far) thresholds. */
enum SignalBand {
  OPEN("열림 구간", Palette.OPEN),
  MIDDLE("중간", Palette.TEXT),
  LOCK("잠금 구간", Palette.LOCK),
  LOST("끊김", Palette.IDLE);

  final String label;
  final int color;

  SignalBand(String label, int color) {
    this.label = label;
    this.color = color;
  }

  /** Same comparison the engine uses: at or above near opens, at or below far locks. */
  static SignalBand of(double rssi, int near, int far) {
    if (Double.isNaN(rssi)) return LOST;
    return rssi >= near ? OPEN : rssi <= far ? LOCK : MIDDLE;
  }
}
