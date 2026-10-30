package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Arrays;

/** The last few seconds of smoothed RSSI as bars, oldest on the left, coloured by band. */
final class SignalHistory extends View {
  static final int SIZE = 12;
  private final double[] values = new double[SIZE]; // Ring buffer; NaN is no signal.
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final float density;
  private int head, near = Controller.DEFAULT_NEAR, far = Controller.DEFAULT_FAR;

  SignalHistory(Context context) {
    super(context);
    density = context.getResources().getDisplayMetrics().density;
    Arrays.fill(values, Double.NaN);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // The dBm text says the same.
  }

  void push(double rssi, int near, int far) {
    values[head] = rssi;
    head = (head + 1) % SIZE;
    this.near = near;
    this.far = far;
    invalidate();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(40 * density));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float gap = 6 * density, width = (getWidth() - gap * (SIZE - 1)) / SIZE;
    float floor = 3 * density, top = getHeight(), radius = 3 * density;
    for (int i = 0; i < SIZE; i++) {
      double v = values[(head + i) % SIZE];
      float fraction =
          Double.isNaN(v) ? 0 : (float) Math.max(.08, Math.min(1, (v - RangeSlider.MIN) / 60.0));
      float height = floor + fraction * (top - floor);
      SignalBand band = SignalBand.of(v, near, far);
      paint.setColor(
          band == SignalBand.LOST
              ? Palette.IDLE_SOFT
              : band == SignalBand.MIDDLE ? 0xffa9b8d4 : band.color);
      float x = i * (width + gap);
      rect.set(x, top - height, x + width, top);
      canvas.drawRoundRect(rect, radius, radius, paint);
    }
  }
}
