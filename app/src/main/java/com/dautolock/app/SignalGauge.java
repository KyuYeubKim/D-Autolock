package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Actual smoothed RSSI plus the user's two trigger thresholds, labelled under each marker. */
final class SignalGauge extends View {
  static final int LOCK_COLOR = 0xffdd994b, OPEN_COLOR = 0xff66dba7;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
  private double rssi = Double.NaN;
  private int near = Controller.DEFAULT_NEAR, far = Controller.DEFAULT_FAR;

  SignalGauge(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    label.setTextSize(12 * context.getResources().getDisplayMetrics().scaledDensity);
  }

  void reading(double value, int near, int far) {
    rssi = value;
    this.near = near;
    this.far = far;
    setContentDescription(
        "평균 "
            + (Double.isNaN(value) ? "미수신" : Math.round(value) + " dBm")
            + ", 열림 기준 "
            + near
            + ", 잠금 기준 "
            + far);
    invalidate();
  }

  private float x(double value) {
    float pad = 14 * getResources().getDisplayMetrics().density;
    return pad + (float) Math.max(0, Math.min(1, (value + 100) / 70)) * (getWidth() - 2 * pad);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    float y = getHeight() * .36f, radius = 7 * density, pad = 14 * density;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xff253237);
    canvas.drawRoundRect(pad, y - radius / 2, getWidth() - pad, y + radius / 2, radius, radius, paint);
    paint.setStrokeWidth(3 * density);
    paint.setColor(LOCK_COLOR);
    canvas.drawLine(x(far), y - radius, x(far), y + radius, paint);
    paint.setColor(OPEN_COLOR);
    canvas.drawLine(x(near), y - radius, x(near), y + radius, paint);
    if (!Double.isNaN(rssi)) {
      paint.setColor(0x335de4b7);
      canvas.drawCircle(x(rssi), y, radius * 1.7f, paint);
      paint.setColor(OPEN_COLOR);
      canvas.drawCircle(x(rssi), y, radius, paint);
      paint.setColor(0xffe8fff5);
      canvas.drawCircle(x(rssi), y, radius * .65f, paint);
    }
    // Labels: lock text ends at its marker, open text starts at its marker, so they never overlap.
    float base = getHeight() - 4 * density;
    label.setColor(LOCK_COLOR);
    label.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText("잠금 " + far, x(far) + 2 * density, base, label);
    label.setColor(OPEN_COLOR);
    label.setTextAlign(Paint.Align.LEFT);
    canvas.drawText("열림 " + near, x(near) + 4 * density, base, label);
  }
}
