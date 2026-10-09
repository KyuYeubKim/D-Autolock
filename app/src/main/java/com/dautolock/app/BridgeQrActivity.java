package com.dautolock.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import java.util.Collections;

/**
 * QR scanner without AndroidX. The library's CaptureActivity/CaptureManager and IntentIntegrator
 * need androidx.core/fragment, which this app does not ship (that crashed the scan on open).
 */
public final class BridgeQrActivity extends Activity {
  static final String RESULT = "qr";
  private static final int CAMERA_REQUEST = 1;
  private DecoratedBarcodeView scanner;
  private boolean done;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    scanner = new DecoratedBarcodeView(this);
    scanner.setStatusText("차량 D-Autolock Bridge의 QR 코드를 스캔하세요");
    scanner
        .getBarcodeView()
        .setDecoderFactory(
            new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
    setContentView(scanner);
    scanner.decodeSingle(
        result -> {
          if (done || result == null || result.getText() == null) return;
          done = true;
          setResult(RESULT_OK, new Intent().putExtra(RESULT, result.getText()));
          finish();
        });
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_REQUEST);
  }

  private boolean cameraAllowed() {
    return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (cameraAllowed()) resumeCamera();
  }

  private void resumeCamera() {
    try {
      scanner.resume();
    } catch (RuntimeException e) {
      fail("카메라를 열지 못했습니다");
    }
  }

  @Override
  protected void onPause() {
    try {
      scanner.pause();
    } catch (RuntimeException ignored) {
    }
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request != CAMERA_REQUEST) return;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) resumeCamera();
    else fail("QR 스캔에는 카메라 권한이 필요합니다");
  }

  private void fail(String message) {
    if (done) return;
    done = true;
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    setResult(RESULT_CANCELED);
    finish();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return scanner.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
  }
}
