package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The car illustration with a static signal mark where each BLE source sits. Static on purpose:
 * it only redraws when a band changes, so it costs nothing while the screen is idle.
 */
final class CarHeroView extends View {
  private static final float VIEW_W = 480, VIEW_H = 200;
  // Viewport points: the car's own BLE near the dashboard, the auxiliary beacon in the boot.
  private static final float FRONT_X = 196, FRONT_Y = 92, REAR_X = 424, REAR_Y = 104;
  private final Drawable car;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF arc = new RectF();
  private final DashPathEffect dashed;
  private SignalBand front = SignalBand.LOST, rear; // rear == null: no beacon configured.

  CarHeroView(Context context) {
    super(context);
    car = context.getDrawable(R.drawable.car_hero);
    float d = context.getResources().getDisplayMetrics().density;
    dashed = new DashPathEffect(new float[] {3 * d, 3 * d}, 0);
    paint.setStrokeCap(Paint.Cap.ROUND);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // The badges describe it.
  }

  void bands(SignalBand front, SignalBand rear) {
    if (this.front == front && this.rear == rear) return;
    this.front = front;
    this.rear = rear;
    invalidate();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    int carWidth = width - getPaddingLeft() - getPaddingRight();
    setMeasuredDimension(
        width, getPaddingTop() + getPaddingBottom() + Math.round(carWidth * VIEW_H / VIEW_W));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int left = getPaddingLeft(), top = getPaddingTop();
    float scale = (getWidth() - left - getPaddingRight()) / VIEW_W;
    car.setBounds(left, top, left + Math.round(VIEW_W * scale), top + Math.round(VIEW_H * scale));
    car.draw(canvas);
    mark(canvas, left + FRONT_X * scale, top + FRONT_Y * scale, scale, front);
    if (rear != null) mark(canvas, left + REAR_X * scale, top + REAR_Y * scale, scale, rear);
  }

  /** Three arcs fanning upward from the source; colour follows the band, dashed when lost. */
  private void mark(Canvas canvas, float x, float y, float scale, SignalBand band) {
    int color = band == SignalBand.MIDDLE ? Palette.ACCENT : band.color;
    paint.setStyle(Paint.Style.STROKE);
    paint.setPathEffect(band == SignalBand.LOST ? dashed : null);
    paint.setStrokeWidth(2.4f * scale);
    for (int i = 0; i < 3; i++) {
      float r = (11 + 9 * i) * scale;
      paint.setColor(color);
      paint.setAlpha(230 - 60 * i);
      arc.set(x - r, y - r, x + r, y + r);
      canvas.drawArc(arc, 215, 110, false, paint);
    }
    paint.setPathEffect(null);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xffffffff);
    canvas.drawCircle(x, y, 5.2f * scale, paint);
    paint.setColor(color);
    canvas.drawCircle(x, y, 3.6f * scale, paint);
  }
}
