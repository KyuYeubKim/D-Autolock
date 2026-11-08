package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * The car picture (a cut-out Dolphin photo) with a static signal mark where each BLE source sits.
 * Static on purpose: it only redraws when a band changes, so it costs nothing while the screen is
 * idle. The bonnet is a button: a tap there clicks the view (차량 찾기, armed by a first tap).
 */
final class CarHeroView extends View {
  /** Share of the width the car takes, centred, leaving the top corners to the badges. */
  static final float CAR_FRACTION = 0.70f;
  // Points in the picture's own pixels (car_dolphin is 960×517): the car's BLE behind the
  // windscreen at the dashboard, the auxiliary beacon at the back by the boot.
  private static final float FRONT_X = 358, FRONT_Y = 128, REAR_X = 900, REAR_Y = 126;
  /** The bonnet as drawn when armed, in picture pixels. */
  private static final float[] BONNET = {
    41, 205, 169, 144, 516, 147, 544, 177, 405, 217, 263, 225, 65, 223
  };
  /** What a tap may hit: the bonnet plus the light bar under it, so the target is not a sliver. */
  private static final float[] BONNET_TOUCH = {
    20, 215, 169, 144, 516, 147, 544, 177, 470, 222, 400, 268, 30, 262
  };
  /** car_dolphin's size in pixels; every point above is in this frame. */
  static final float IMAGE_W = 960, IMAGE_H = 517;
  private final Drawable car;
  private final float imageW = IMAGE_W, imageH = IMAGE_H, slop;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF arc = new RectF(), floor = new RectF(), pill = new RectF();
  private final Path bonnet = new Path();
  private final DashPathEffect dashed;
  private SignalBand front = SignalBand.LOST, rear; // rear == null: no beacon configured.
  private boolean armed;

  CarHeroView(Context context) {
    super(context);
    car = context.getDrawable(R.drawable.car_dolphin);
    float d = context.getResources().getDisplayMetrics().density;
    slop = 10 * d;
    dashed = new DashPathEffect(new float[] {3 * d, 3 * d}, 0);
    paint.setStrokeCap(Paint.Cap.ROUND);
    label.setColor(0xffffffff);
    label.setTextAlign(Paint.Align.CENTER);
    label.setFakeBoldText(true);
    label.setTextSize(12 * context.getResources().getDisplayMetrics().scaledDensity);
  }

  void bands(SignalBand front, SignalBand rear) {
    if (this.front == front && this.rear == rear) return;
    this.front = front;
    this.rear = rear;
    invalidate();
  }

  /** Waiting for the second tap: the bonnet lights up and says what the next tap does. */
  void armed(boolean armed) {
    if (this.armed == armed) return;
    this.armed = armed;
    invalidate();
  }

  boolean armed() {
    return armed;
  }

  private float carWidth() {
    return (getWidth() - getPaddingLeft() - getPaddingRight()) * CAR_FRACTION;
  }

  private float carLeft() {
    return getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - carWidth()) / 2f;
  }

  /** True when a view point is on the bonnet (or within a finger's slop of it). */
  boolean bonnetAt(float x, float y) {
    float s = carWidth() / imageW;
    if (s <= 0) return false;
    float px = (x - carLeft()) / s, py = (y - getPaddingTop()) / s, tolerance = slop / s;
    return inside(BONNET_TOUCH, px, py) || near(BONNET_TOUCH, px, py, tolerance);
  }

  /** Even-odd ray cast over a closed polygon given as x,y pairs. */
  static boolean inside(float[] poly, float x, float y) {
    boolean in = false;
    for (int i = 0, j = poly.length - 2; i < poly.length; j = i, i += 2) {
      float xi = poly[i], yi = poly[i + 1], xj = poly[j], yj = poly[j + 1];
      if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
    }
    return in;
  }

  private static boolean near(float[] poly, float x, float y, float tolerance) {
    for (int i = 0, j = poly.length - 2; i < poly.length; j = i, i += 2) {
      float ax = poly[j], ay = poly[j + 1], bx = poly[i], by = poly[i + 1];
      float dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
      float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len));
      float ex = ax + t * dx - x, ey = ay + t * dy - y;
      if (ex * ex + ey * ey <= tolerance * tolerance) return true;
    }
    return false;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled() || !isClickable()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (!bonnetAt(event.getX(), event.getY())) return false; // Only the bonnet is a button.
        setPressed(true);
        return true;
      case MotionEvent.ACTION_UP:
        boolean hit = isPressed() && bonnetAt(event.getX(), event.getY());
        setPressed(false);
        if (hit) performClick();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!bonnetAt(event.getX(), event.getY())) setPressed(false);
        return true;
      case MotionEvent.ACTION_CANCEL:
        setPressed(false);
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
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
    // A wide, soft floor shadow under the wheels so the cut-out sits on the page.
    float carW = carWidth(), carH = carW * imageH / imageW;
    float cx = carLeft() + carW * 0.53f;
    float cy = getPaddingTop() + carH * 0.90f;
    floor.set(cx - carW * 0.52f, cy - carH * 0.09f, cx + carW * 0.52f, cy + carH * 0.09f);
    ground.setShader(
        new RadialGradient(
            0, 0, 1, new int[] {0x2a000000, 0x12000000, 0}, new float[] {0, .55f, 1},
            Shader.TileMode.CLAMP));
    float s = carW / imageW, left = carLeft(), top = getPaddingTop();
    bonnet.rewind();
    for (int i = 0; i < BONNET.length; i += 2) {
      float x = left + BONNET[i] * s, y = top + BONNET[i + 1] * s;
      if (i == 0) bonnet.moveTo(x, y);
      else bonnet.lineTo(x, y);
    }
    bonnet.close();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float carW = carWidth(), s = carW / imageW;
    float left = carLeft();
    float top = getPaddingTop();
    canvas.save();
    canvas.translate(floor.centerX(), floor.centerY());
    canvas.scale(floor.width() / 2, floor.height() / 2);
    canvas.drawCircle(0, 0, 1, ground);
    canvas.restore();
    car.setBounds(
        Math.round(left), Math.round(top), Math.round(left + carW), Math.round(top + imageH * s));
    car.draw(canvas);
    if (armed || isPressed()) {
      paint.setPathEffect(null);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(Palette.ACCENT);
      paint.setAlpha(armed ? 0x55 : 0x30);
      canvas.drawPath(bonnet, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setStrokeWidth(Math.max(2, 3 * s));
      paint.setAlpha(0xff);
      canvas.drawPath(bonnet, paint);
    }
    mark(canvas, left + FRONT_X * s, top + FRONT_Y * s, s, front);
    if (rear != null) mark(canvas, left + REAR_X * s, top + REAR_Y * s, s, rear);
    if (armed) hint(canvas, left + 290 * s, top + 300 * s);
  }

  /** A blue pill over the bumper: what the second tap will do. */
  private void hint(Canvas canvas, float x, float y) {
    String text = "한 번 더 누르면 차량 찾기";
    float pad = label.getTextSize() * 0.9f;
    float w = label.measureText(text) + pad * 2, h = label.getTextSize() * 2.1f;
    x = Math.max(w / 2, Math.min(getWidth() - w / 2, x));
    pill.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
    paint.setPathEffect(null);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Palette.ACCENT);
    canvas.drawRoundRect(pill, h / 2, h / 2, paint);
    Paint.FontMetrics m = label.getFontMetrics();
    canvas.drawText(text, x, y - (m.ascent + m.descent) / 2, label);
  }

  /** Three arcs fanning upward from the source; colour follows the band, dashed when lost. */
  private void mark(Canvas canvas, float x, float y, float scale, SignalBand band) {
    int color = band == SignalBand.MIDDLE ? Palette.ACCENT : band.color;
    paint.setStyle(Paint.Style.STROKE);
    paint.setPathEffect(band == SignalBand.LOST ? dashed : null);
    paint.setStrokeWidth(4.2f * scale);
    for (int i = 0; i < 3; i++) {
      float r = (22 + 16 * i) * scale;
      paint.setColor(color);
      paint.setAlpha(235 - 60 * i);
      arc.set(x - r, y - r, x + r, y + r);
      canvas.drawArc(arc, 220, 100, false, paint);
    }
    paint.setPathEffect(null);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xffffffff);
    canvas.drawCircle(x, y, 10f * scale, paint);
    paint.setColor(color);
    canvas.drawCircle(x, y, 6.8f * scale, paint);
  }
}
