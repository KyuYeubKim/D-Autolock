package com.dautolock.app;

import static org.junit.Assert.*;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorWidgetTest {
  private DApplication app;
  private Controller c;
  private CommandFlowTest.Protocol protocol;
  private ShadowAppWidgetManager widgets;
  private int widgetId;

  @Before
  public void setUp() throws Exception {
    DoorWidget.reset();
    app = (DApplication) RuntimeEnvironment.getApplication();
    c = app.controller();
    AccountPersistenceTest.await(c);
    protocol = new CommandFlowTest.Protocol();
    protocol.locked = true;
    c.cloud = new com.dautolock.app.api.CloudClient(protocol);
    c.vin = "test-car";
    c.pinHash = "test-pin";
    c.capabilities =
        new org.json.JSONObject()
            .put(
                "items",
                new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("functionNo", "1005"))
                    .put(new org.json.JSONObject().put("functionNo", "1006")));
    widgets = Shadows.shadowOf(AppWidgetManager.getInstance(app));
    widgetId = widgets.createWidget(DoorWidget.class, R.layout.widget_doors);
  }

  @After
  public void tearDown() {
    DoorWidget.reset();
  }

  private String label(int id) {
    View root = widgets.getViewFor(widgetId);
    return String.valueOf(((TextView) root.findViewById(id)).getText());
  }

  private void tap(String action) {
    DoorWidget.onTap(app, action, null);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  /** The second tap's command starts after a short hand-off thread; let it reach the worker. */
  private void awaitCommand() throws Exception {
    long limit = System.currentTimeMillis() + 10000;
    while (protocol.commands.isEmpty() && System.currentTimeMillis() < limit) {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      Thread.sleep(20);
    }
    AccountPersistenceTest.await(c);
  }

  @Test
  public void aButtonArmsOnTheFirstTapAndSendsOnTheSecond() throws Exception {
    assertEquals("도어 열기", label(R.id.widget_unlock_label));
    tap(DoorWidget.TAP_UNLOCK);
    assertEquals("다시 누르면 열기", label(R.id.widget_unlock_label));
    Thread.sleep(300);
    assertTrue(protocol.commands.isEmpty()); // One tap never sends.
    tap(DoorWidget.TAP_UNLOCK);
    awaitCommand();
    assertEquals(java.util.Collections.singletonList("OPENDOOR"), protocol.commands);
  }

  @Test
  public void eachTapEndsItsBroadcastAtOnceSoTheSecondTapIsNotHeldBack() throws Exception {
    // Real log 11-11 12:15/12:27: no WIDGET_ACTION at all. A manifest receiver gets one broadcast
    // at a time; the arming tap held its broadcast for 3.1 s, so the second tap only arrived after
    // the 3 s window and armed again — the widget could never send.
    protocol.locked = false;
    java.util.concurrent.atomic.AtomicInteger ended = new java.util.concurrent.atomic.AtomicInteger();
    DoorWidget.onTap(app, DoorWidget.TAP_LOCK, ended::incrementAndGet);
    assertEquals(1, ended.get()); // Ended at once, not after the window.
    assertEquals("다시 누르면 잠금", label(R.id.widget_lock_label));
    // The next broadcast is delivered only now, and still within the window.
    DoorWidget.onTap(app, DoorWidget.TAP_LOCK, ended::incrementAndGet);
    assertEquals(2, ended.get()); // Handed to the controller; the broadcast is not held meanwhile.
    awaitCommand();
    assertEquals(java.util.Collections.singletonList("LOCKDOOR"), protocol.commands);
  }

  @Test
  public void tappingTheOtherButtonOrWaitingTooLongOnlyArmsAgain() throws Exception {
    tap(DoorWidget.TAP_UNLOCK);
    tap(DoorWidget.TAP_LOCK); // A different button: arms that one instead.
    assertEquals("다시 누르면 잠금", label(R.id.widget_lock_label));
    assertEquals("도어 열기", label(R.id.widget_unlock_label));
    org.robolectric.shadows.ShadowSystemClock.advanceBy(
        java.time.Duration.ofMillis(DoorWidget.ARM_MS + 500));
    tap(DoorWidget.TAP_LOCK); // Too late: just armed again.
    Thread.sleep(300);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(protocol.commands.isEmpty());
    assertEquals("다시 누르면 잠금", label(R.id.widget_lock_label));
  }

  @Test
  public void theExportedProviderIgnoresTapActionsFromOutside() throws Exception {
    // The launcher must reach the provider, so it is exported; only the private receiver acts.
    DoorWidget provider = new DoorWidget();
    for (int i = 0; i < 2; i++) provider.onReceive(app, new Intent(DoorWidget.TAP_UNLOCK));
    Thread.sleep(300);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(protocol.commands.isEmpty());
    assertNull(DoorWidget.armedAction(android.os.SystemClock.elapsedRealtime()));
  }
}
