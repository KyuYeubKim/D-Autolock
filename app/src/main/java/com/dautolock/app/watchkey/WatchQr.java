package com.dautolock.app.watchkey;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.EnumMap;
import java.util.Map;

/** Renders the approval QR for display (zxing is already a dependency for scanning). */
public final class WatchQr {

  private WatchQr() {}

  public static Bitmap bitmap(String content, int sizePx) {
    try {
      Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
      hints.put(EncodeHintType.MARGIN, 1);
      BitMatrix matrix =
          new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
      int w = matrix.getWidth(), h = matrix.getHeight();
      Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
          bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        }
      }
      return bmp;
    } catch (Exception e) {
      return null;
    }
  }
}
