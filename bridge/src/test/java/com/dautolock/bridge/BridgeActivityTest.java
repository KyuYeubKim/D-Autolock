package com.dautolock.bridge;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 33})
public class BridgeActivityTest {
  private Button find(View v, String text) {
    if (v instanceof Button && text.contentEquals(((Button) v).getText())) return (Button) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        Button b = find(((ViewGroup) v).getChildAt(i), text);
        if (b != null) return b;
      }
    return null;
  }

  @Test
  public void diagnosticSaveWithoutDocumentPickerFallsBackInsteadOfCrashing() {
    try (org.robolectric.android.controller.ActivityController<BridgeActivity> a =
        Robolectric.buildActivity(BridgeActivity.class).setup()) {
      // Head units without a document picker: an implicit CREATE_DOCUMENT has no handler.
      Shadows.shadowOf(a.get().getApplication()).checkActivities(true);
      find(a.get().getWindow().getDecorView(), "차량 진단 파일 저장").performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(dialog);
      TextView text = findText(dialog.getWindow().getDecorView());
      assertNotNull(text);
      assertTrue(text.getText().toString().contains("D-Autolock Bridge " + BridgeActivity.VERSION));
      assertTrue(text.getText().toString().contains("저장"));
    }
  }

  @Test
  public void missingBluetoothSettingsScreenShowsMessageInsteadOfCrashing() {
    try (org.robolectric.android.controller.ActivityController<BridgeActivity> a =
        Robolectric.buildActivity(BridgeActivity.class).setup()) {
      Shadows.shadowOf(a.get().getApplication()).checkActivities(true);
      find(a.get().getWindow().getDecorView(), "Bluetooth 설정").performClick();
      assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
  }

  private TextView findText(View v) {
    if (v instanceof TextView && ((TextView) v).getText().toString().contains("Bridge"))
      return (TextView) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        TextView t = findText(((ViewGroup) v).getChildAt(i));
        if (t != null) return t;
      }
    return null;
  }
}
