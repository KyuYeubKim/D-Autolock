package com.dautolock.app.watchble;

import static org.junit.Assert.*;

import org.junit.Test;

/** Pure tests for the watch BLE codec: frame layout, CRC8, AES-CMAC (NIST vectors), extraction. */
public class WatchBleCodecTest {

  private static byte[] hex(String s) {
    s = s.replace(" ", "");
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return b;
  }

  private static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  @Test
  public void wakeUpFrameIsTheFixedSevenBytes() {
    byte[] f = new WatchBleCodec().createWakeUpFrame();
    assertArrayEquals(
        new byte[] {0x5A, (byte) 0xA5, (byte) 0xD5, 0x00, 0x00, (byte) 0xF5, (byte) 0xFA}, f);
  }

  @Test
  public void randomExchangeFrameLayoutAndCrc() {
    WatchBleCodec codec = new WatchBleCodec();
    byte[] appRandom = hex("0102030405060708");
    byte[] f = codec.createRandomExchangeFrame((byte) 1, appRandom);
    assertEquals(20, f.length);
    assertEquals((byte) 0x5A, f[0]);
    assertEquals((byte) 0xA5, f[1]);
    assertEquals((byte) 0xD6, f[2]);
    for (int i = 0; i < 8; i++) assertEquals(appRandom[i], f[3 + i]);
    assertEquals((byte) 1, f[11]);
    for (int i = 12; i < 17; i++) assertEquals((byte) 0xFF, f[i]);
    assertEquals(WatchBleCodec.crc8(f, 0, 16), f[17]);
    assertEquals((byte) 0xF5, f[18]);
    assertEquals((byte) 0xFA, f[19]);
  }

  @Test
  public void parseRandomExchangeReturnsKeyStateAndCrc() {
    WatchBleCodec codec = new WatchBleCodec();
    codec.createRandomExchangeFrame((byte) 1, hex("1111111111111111"));
    byte[] payload = new byte[16];
    payload[0] = (byte) 0x2A;
    payload[1] = (byte) 0xD6;
    payload[2] = 1; // keyState
    System.arraycopy(hex("2222222222222222"), 0, payload, 3, 8); // vehicleRandom
    payload[11] = 1; // crcCheckResult
    int[] r = codec.parseRandomExchange(payload);
    assertNotNull(r);
    assertEquals(1, r[0]);
    assertEquals(1, r[1]);
  }

  @Test
  public void parseRandomExchangeRejectsWrongHeader() {
    WatchBleCodec codec = new WatchBleCodec();
    byte[] payload = new byte[16];
    payload[0] = (byte) 0x24; // not 0x2A
    assertNull(codec.parseRandomExchange(payload));
  }

  @Test
  public void aesCmacMatchesNistVectors() {
    // NIST SP 800-38B examples, AES-128.
    byte[] key = hex("2b7e151628aed2a6abf7158809cf4f3c");
    assertEquals("bb1d6929e9593728 7fa37d129b756746".replace(" ", ""), hex(WatchBleCodec.aesCmac(key, new byte[0], 0)));
    byte[] m = hex("6bc1bee22e409f96e93d7e117393172a");
    assertEquals("070a16b46b4d4144 f79bdd9dd04a287c".replace(" ", ""), hex(WatchBleCodec.aesCmac(key, m, 16)));
  }

  @Test
  public void authAndControlFramesAreWrappedEncrypted() {
    WatchBleCodec codec = new WatchBleCodec();
    // Prime the session IV via a random exchange so the IV is set.
    codec.createRandomExchangeFrame((byte) 1, hex("0102030405060708"));
    byte[] rx = new byte[16];
    rx[0] = (byte) 0x2A;
    rx[1] = (byte) 0xD6;
    rx[2] = 1;
    System.arraycopy(hex("aabbccddeeff0011"), 0, rx, 3, 8);
    rx[11] = 1;
    codec.parseRandomExchange(rx);

    byte[] auth = codec.createAuthenticationFrame("00112233445566778899aabbccddeeff");
    assertEquals(20, auth.length);
    assertEquals((byte) 0x5B, auth[0]);
    assertEquals((byte) 0xB5, auth[1]);
    assertEquals((byte) 0xF5, auth[18]);
    assertEquals((byte) 0xFA, auth[19]);

    byte[] c1 = codec.createControlFrame(WatchBleCodec.UNLOCK);
    byte[] c2 = codec.createControlFrame(WatchBleCodec.LOCK);
    assertEquals(20, c1.length);
    assertEquals((byte) 0x5B, c1[0]);
    assertEquals((byte) 0xB5, c1[1]);
    assertFalse("sequence must change between control frames", java.util.Arrays.equals(c1, c2));
  }

  @Test
  public void extractPayloadReadsOneFrameAndAdvances() {
    byte[] frame = new byte[20];
    frame[0] = (byte) 0xA5; // HEADER_A big-endian 0xA55A
    frame[1] = (byte) 0x5A;
    frame[2] = (byte) 0x24; // payload[0] = control response type
    frame[3] = (byte) 0xE5;
    frame[4] = WatchBleCodec.LOCK;
    frame[5] = 1; // result
    frame[6] = 0x0F; // doors
    frame[17] = WatchBleCodec.crc8(frame, 0, 16);
    frame[18] = (byte) 0xF5;
    frame[19] = (byte) 0xFA;

    int[] consumed = new int[1];
    byte[] payload = WatchBleCodec.extractPayload(frame, 20, consumed);
    assertNotNull(payload);
    assertEquals(16, payload.length);
    assertEquals(20, consumed[0]);
    assertEquals((byte) 0x24, payload[0]);
    assertEquals(1, payload[3] & 0xFF);
  }

  @Test
  public void extractPayloadReturnsNullWhenIncomplete() {
    byte[] buf = new byte[] {(byte) 0xA5, (byte) 0x5A, 0x01, 0x02};
    int[] consumed = new int[1];
    assertNull(WatchBleCodec.extractPayload(buf, 4, consumed));
    assertEquals(0, consumed[0]); // keep the partial frame
  }
}
