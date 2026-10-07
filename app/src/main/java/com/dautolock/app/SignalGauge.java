package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Actual smoothed RSSI plus the user's two trigger thresholds. */
final class SignalGauge extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private double rssi = Double.NaN;
  private int near = -65, far = -80;

  SignalGauge(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
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
    return 10 + (float) Math.max(0, Math.min(1, (value + 100) / 70)) * (getWidth() - 20);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float y = getHeight() / 2f, radius = 7 * getResources().getDisplayMetrics().density;
    paint.setColor(0xff253237);
    canvas.drawRoundRect(
        10, y - radius / 2, getWidth() - 10, y + radius / 2, radius, radius, paint);
    paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
    paint.setColor(0xffdd994b);
    canvas.drawLine(x(far), y - radius, x(far), y + radius, paint);
    paint.setColor(0xff66dba7);
    canvas.drawLine(x(near), y - radius, x(near), y + radius, paint);
    if (!Double.isNaN(rssi)) {
      paint.setColor(0x335de4b7);
      canvas.drawCircle(x(rssi), y, radius * 1.7f, paint);
      paint.setColor(0xff66dba7);
      canvas.drawCircle(x(rssi), y, radius, paint);
      paint.setColor(0xffe8fff5);
      canvas.drawCircle(x(rssi), y, radius * .65f, paint);
    }
  }
}
