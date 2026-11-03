package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The car picture (a cut-out Dolphin render) with a static signal mark where each BLE source
 * sits. Static on purpose: it only redraws when a band changes, so it costs nothing while the
 * screen is idle.
 */
final class CarHeroView extends View {
  /** Share of the width the car takes, centred, leaving the top corners to the badges. */
  private static final float CAR_FRACTION = 0.84f;
  // Points in the picture's own pixels: the car's BLE behind the windscreen at the dashboard,
  // the auxiliary beacon at the back by the boot.
  private static final float FRONT_X = 209, FRONT_Y = 85, REAR_X = 475, REAR_Y = 92;
  private final Drawable car;
  private final float imageW, imageH;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF arc = new RectF(), floor = new RectF();
  private final DashPathEffect dashed;
  private SignalBand front = SignalBand.LOST, rear; // rear == null: no beacon configured.

  CarHeroView(Context context) {
    super(context);
    car = context.getDrawable(R.drawable.car_dolphin);
    imageW = car.getIntrinsicWidth();
    imageH = car.getIntrinsicHeight();
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

  private float carWidth() {
    return (getWidth() - getPaddingLeft() - getPaddingRight()) * CAR_FRACTION;
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    float carWidth = (width - getPaddingLeft() - getPaddingRight()) * CAR_FRACTION;
    setMeasuredDimension(
        width, getPaddingTop() + getPaddingBottom() + Math.round(carWidth * imageH / imageW));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    // A wide, soft floor shadow so the cut-out sits on the page instead of floating.
    float carW = carWidth(), carH = carW * imageH / imageW;
    float cx = getPaddingLeft() + (w - getPaddingLeft() - getPaddingRight()) / 2f + carW * 0.03f;
    float cy = getPaddingTop() + carH * 0.93f;
    floor.set(cx - carW * 0.5f, cy - carH * 0.07f, cx + carW * 0.5f, cy + carH * 0.07f);
    ground.setShader(
        new RadialGradient(
            0, 0, 1, new int[] {0x30000000, 0x14000000, 0}, new float[] {0, .55f, 1},
            Shader.TileMode.CLAMP));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float carW = carWidth(), s = carW / imageW;
    float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - carW) / 2f;
    float top = getPaddingTop();
    canvas.save();
    canvas.translate(floor.centerX(), floor.centerY());
    canvas.scale(floor.width() / 2, floor.height() / 2);
    canvas.drawCircle(0, 0, 1, ground);
    canvas.restore();
    car.setBounds(
        Math.round(left), Math.round(top), Math.round(left + carW), Math.round(top + imageH * s));
    car.draw(canvas);
    mark(canvas, left + FRONT_X * s, top + FRONT_Y * s, s, front);
    if (rear != null) mark(canvas, left + REAR_X * s, top + REAR_Y * s, s, rear);
  }

  /** Three arcs fanning upward from the source; colour follows the band, dashed when lost. */
  private void mark(Canvas canvas, float x, float y, float scale, SignalBand band) {
    int color = band == SignalBand.MIDDLE ? Palette.ACCENT : band.color;
    paint.setStyle(Paint.Style.STROKE);
    paint.setPathEffect(band == SignalBand.LOST ? dashed : null);
    paint.setStrokeWidth(2.2f * scale);
    for (int i = 0; i < 3; i++) {
      float r = (11 + 8 * i) * scale;
      paint.setColor(color);
      paint.setAlpha(235 - 60 * i);
      arc.set(x - r, y - r, x + r, y + r);
      canvas.drawArc(arc, 220, 100, false, paint);
    }
    paint.setPathEffect(null);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xffffffff);
    canvas.drawCircle(x, y, 5f * scale, paint);
    paint.setColor(color);
    canvas.drawCircle(x, y, 3.4f * scale, paint);
  }
}
