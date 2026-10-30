package com.dautolock.app;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import com.dautolock.app.core.ProximityEngine;

/**
 * One track, two handles: lock (far, orange) and unlock (near, teal). Moving one pushes the other
 * so they always stay ProximityEngine.MIN_GAP_DB apart, like the old pair of sliders did.
 */
final class RangeSlider extends View {
  interface Listener {
    void changed(int far, int near);
  }

  static final int MIN = -100, MAX = -40;
  private static final int GAP = ProximityEngine.MIN_GAP_DB;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final float density;
  private int far = Controller.DEFAULT_FAR, near = Controller.DEFAULT_NEAR;
  private int dragging; // 0 none, 1 far handle, 2 near handle.
  private Listener listener;

  RangeSlider(Context context) {
    super(context);
    density = context.getResources().getDisplayMetrics().density;
    label.setTextSize(11 * context.getResources().getDisplayMetrics().scaledDensity);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    describe();
  }

  void listener(Listener listener) {
    this.listener = listener;
  }

  int far() {
    return far;
  }

  int near() {
    return near;
  }

  /** Loads saved values without reporting a change: loading is not an edit. */
  void set(int far, int near) {
    this.near = clamp(near, MIN + GAP, MAX);
    this.far = Math.min(clamp(far, MIN, MAX - GAP), this.near - GAP);
    describe();
    invalidate();
  }

  /** A user edit of the unlock threshold; the lock handle yields to keep the gap. */
  void moveNear(int value) {
    int n = clamp(value, MIN + GAP, MAX), f = Math.min(far, n - GAP);
    if (n != near || f != far) changed(f, n);
  }

  /** A user edit of the lock threshold; the unlock handle yields to keep the gap. */
  void moveFar(int value) {
    int f = clamp(value, MIN, MAX - GAP), n = Math.max(near, f + GAP);
    if (n != near || f != far) changed(f, n);
  }

  private void changed(int f, int n) {
    far = f;
    near = n;
    describe();
    invalidate();
    if (listener != null) listener.changed(far, near);
  }

  private void describe() {
    setContentDescription("잠금 임계값 " + far + " dBm, 열림 임계값 " + near + " dBm");
  }

  private static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }

  static String dbm(int value) {
    return value < 0 ? "−" + -value : String.valueOf(value);
  }

  private float pad() {
    return 18 * density;
  }

  private float x(int value) {
    return pad() + (value - MIN) / (float) (MAX - MIN) * (getWidth() - 2 * pad());
  }

  private int value(float x) {
    return Math.round(MIN + (x - pad()) / (getWidth() - 2 * pad()) * (MAX - MIN));
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(62 * density));
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (!isEnabled()) return false;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        dragging = Math.abs(e.getX() - x(far)) <= Math.abs(e.getX() - x(near)) ? 1 : 2;
        getParent().requestDisallowInterceptTouchEvent(true); // Dragging, not scrolling the page.
        drag(e.getX());
        return true;
      case MotionEvent.ACTION_MOVE:
        drag(e.getX());
        return true;
      case MotionEvent.ACTION_UP:
        performClick();
        release();
        return true;
      case MotionEvent.ACTION_CANCEL:
        release();
        return true;
      default:
        return super.onTouchEvent(e);
    }
  }

  private void drag(float x) {
    if (dragging == 1) moveFar(value(x));
    else if (dragging == 2) moveNear(value(x));
  }

  private void release() {
    dragging = 0;
    getParent().requestDisallowInterceptTouchEvent(false);
    invalidate();
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float y = 22 * density, r = 3 * density, left = pad(), right = getWidth() - pad();
    float xf = x(far), xn = x(near);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Palette.IDLE_SOFT);
    rect.set(left, y - r, right, y + r);
    canvas.drawRoundRect(rect, r, r, paint);
    paint.setColor(Palette.LOCK_SOFT);
    rect.set(left, y - r, xf, y + r);
    canvas.drawRoundRect(rect, r, r, paint);
    paint.setColor(Palette.OPEN_SOFT);
    rect.set(xn, y - r, right, y + r);
    canvas.drawRoundRect(rect, r, r, paint);
    handle(canvas, xf, y, Palette.LOCK, dragging == 1);
    handle(canvas, xn, y, Palette.OPEN, dragging == 2);
    // Handle values under each handle; when close they part around the middle instead of overlapping.
    float base = getHeight() - 8 * density, space = 6 * density;
    label.setTypeface(Typeface.DEFAULT_BOLD);
    String farText = dbm(far), nearText = dbm(near);
    float wf = label.measureText(farText), wn = label.measureText(nearText);
    float farLeft = xf - wf / 2, nearLeft = xn - wn / 2;
    if (farLeft + wf + space > nearLeft) {
      float middle = (xf + xn) / 2;
      farLeft = middle - space / 2 - wf;
      nearLeft = middle + space / 2;
    }
    label.setTextAlign(Paint.Align.LEFT);
    label.setColor(Palette.LOCK);
    canvas.drawText(farText, farLeft, base, label);
    label.setColor(Palette.OPEN);
    canvas.drawText(nearText, nearLeft, base, label);
    // Track ends, dropped when a handle value would collide with them.
    label.setTypeface(Typeface.DEFAULT);
    label.setColor(Palette.MUTED);
    String weak = dbm(MIN) + " 약함", strong = "강함 " + dbm(MAX);
    if (label.measureText(weak) + 2 * space < farLeft) canvas.drawText(weak, 0, base, label);
    float ws = label.measureText(strong);
    if (getWidth() - ws - 2 * space > nearLeft + wn)
      canvas.drawText(strong, getWidth() - ws, base, label);
  }

  private void handle(Canvas canvas, float x, float y, int color, boolean active) {
    // 20 dp wide: at the minimum 5 dB gap on a phone-width track the two handles still clear.
    float half = (active ? 11 : 10) * density, corner = 7 * density;
    rect.set(x - half, y - half, x + half, y + half);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Palette.CARD);
    canvas.drawRoundRect(rect, corner, corner, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2.5f * density);
    paint.setColor(color);
    canvas.drawRoundRect(rect, corner, corner, paint);
  }
}
