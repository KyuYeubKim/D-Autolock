package com.dautolock.app;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CarHeroViewTest {
  private static final int WIDTH = 1000;

  private static CarHeroView laidOut() {
    CarHeroView view = new CarHeroView(RuntimeEnvironment.getApplication());
    view.measure(
        View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    view.layout(0, 0, WIDTH, view.getMeasuredHeight());
    return view;
  }

  /** A tap at a point of the picture (car_dolphin pixels). */
  private static void tap(CarHeroView view, float px, float py) {
    float carW = WIDTH * CarHeroView.CAR_FRACTION, s = carW / CarHeroView.IMAGE_W;
    float x = (WIDTH - carW) / 2 + px * s, y = view.getPaddingTop() + py * s;
    long t = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
    view.dispatchTouchEvent(down);
    view.dispatchTouchEvent(up);
    down.recycle();
    up.recycle();
  }

  @Test
  public void onlyTheBonnetIsAButton() {
    CarHeroView view = laidOut();
    assertEquals(
        Math.round(WIDTH * CarHeroView.CAR_FRACTION * CarHeroView.IMAGE_H / CarHeroView.IMAGE_W),
        view.getMeasuredHeight());
    int[] clicks = {0};
    view.setOnClickListener(v -> clicks[0]++);
    tap(view, 300, 185); // Middle of the bonnet.
    assertEquals(1, clicks[0]);
    tap(view, 220, 245); // The light bar under it counts, so the target is not a sliver.
    assertEquals(2, clicks[0]);
    tap(view, 700, 440); // Front wheel.
    tap(view, 500, 40); // Roof.
    tap(view, 850, 250); // Rear door.
    tap(view, 120, 340); // Number plate.
    assertEquals(2, clicks[0]);
    view.setEnabled(false); // Before setup or without 차량 찾기 support: the picture is inert.
    tap(view, 300, 185);
    assertEquals(2, clicks[0]);
  }

  @Test
  public void armedStateIsOnlyAMark() {
    CarHeroView view = laidOut();
    assertFalse(view.armed());
    view.armed(true);
    assertTrue(view.armed());
    view.armed(false);
    assertFalse(view.armed());
  }

  @Test
  public void polygonTestIsEvenOdd() {
    float[] square = {0, 0, 10, 0, 10, 10, 0, 10};
    assertTrue(CarHeroView.inside(square, 5, 5));
    assertFalse(CarHeroView.inside(square, 15, 5));
    assertFalse(CarHeroView.inside(square, 5, -1));
  }
}
