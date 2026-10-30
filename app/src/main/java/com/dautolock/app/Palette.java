package com.dautolock.app;

/** Light theme colours shared by the main screen, the BLE settings page and their custom views. */
final class Palette {
  static final int BG_TOP = 0xffdde8fb, // Top of the page gradient; also the status bar.
      BG = 0xffeef3fa,
      CARD = 0xffffffff,
      STROKE = 0xffe1e7f0,
      FIELD = 0xfff2f5fa, // Inner panels, steppers and plain buttons.
      TEXT = 0xff111827,
      SUBTLE = 0xff4b5563,
      MUTED = 0xff6b7486,
      ACCENT = 0xff1d5bd6,
      ACCENT_SOFT = 0xffe5edfc,
      OPEN = 0xff0f7f8a, // Unlock threshold and the near band.
      OPEN_SOFT = 0xffd5eef0,
      LOCK = 0xffc8641a, // Lock threshold and the far band.
      LOCK_SOFT = 0xfff8e5d4,
      IDLE = 0xff9aa4b5, // Between the thresholds, or no signal.
      IDLE_SOFT = 0xffe4e9f1,
      SUCCESS = 0xff1f9d63,
      DANGER = 0xffd93636,
      DANGER_SOFT = 0xfffdecec;

  private Palette() {}
}
