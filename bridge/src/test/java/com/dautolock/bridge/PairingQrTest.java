package com.dautolock.bridge;

import static org.junit.Assert.*;

import com.dautolock.link.Pairing;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import org.junit.Test;

public class PairingQrTest {
  @Test
  public void displayedQrDecodesToExactPairingWithoutAnInternetUrl() throws Exception {
    Pairing p = Pairing.create();
    BitMatrix qr = new QRCodeWriter().encode(p.qr(), BarcodeFormat.QR_CODE, 560, 560);
    int[] pixels = new int[560 * 560];
    for (int y = 0; y < 560; y++)
      for (int x = 0; x < 560; x++) pixels[y * 560 + x] = qr.get(x, y) ? 0xff000000 : 0xffffffff;
    Result r =
        new QRCodeReader()
            .decode(
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(560, 560, pixels))));
    assertEquals(p.qr(), r.getText());
    assertArrayEquals(p.key, Pairing.parse(r.getText()).key);
  }
}
