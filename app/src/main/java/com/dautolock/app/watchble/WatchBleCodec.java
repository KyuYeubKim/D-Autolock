package com.dautolock.app.watchble;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The BYD "watch BLE key" wire codec, reimplemented to match the reference ({@code
 * watchble/codec/BydBleCodec} + {@code BydBleCrypto}) byte-for-byte. Pure/stateful per session (no
 * Android, no network) so the frame building, CRC8, AES-CMAC and the SHA-256(dkey‖IV) session-key
 * derivation can be unit-tested. {@link WatchBleClient} drives the GATT transport around it.
 *
 * <p>Frame layout (sent): 20 bytes, header + body + CRC/tail. Plaintext frames (wake-up,
 * random-exchange) carry a CRC8 over bytes [0..16]; encrypted frames (auth, control) carry
 * AES-128-CBC/NoPadding ciphertext with an embedded truncated AES-CMAC. The car replies with
 * plaintext 16-byte payloads (type 0x2A/0x2B/0x24).
 */
public final class WatchBleCodec {

  public static final String SERVICE_UUID = "42594420-4155-544F-E0A9-E50E24DCCA9E";
  public static final String SEND_CHARACTERISTIC_UUID = "42590002-4155-544F-E0A9-E50E24DCCA9E";
  public static final String RECEIVE_CHARACTERISTIC_UUID = "42590003-4155-544F-E0A9-E50E24DCCA9E";
  public static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";

  public static final int RESPONSE_RANDOM_EXCHANGE = 0x2A; // 42
  public static final int RESPONSE_AUTHENTICATION = 0x2B; // 43
  public static final int RESPONSE_CONTROL = 0x24; // 36
  public static final int CONTROL_COMMAND_TYPE = 0xE5; // 229

  // Control codes (reference getControlCode).
  public static final byte UNLOCK = 5;
  public static final byte LOCK = 7;
  public static final byte START_CLIMATE = 3; // remote climate ON = 시동 켜기 (reference START_CLIMATE 9003)
  public static final byte STOP_CLIMATE = 8; // remote climate OFF = 시동 끄기 (reference STOP_CLIMATE 9009)

  private int sequence = 0;
  private byte[] appRandom = new byte[8];
  private byte[] sessionIv = new byte[16];
  private byte[] aesKey = new byte[16];
  private byte[] cmacKey = new byte[16];

  public void clearSession() {
    sequence = 0;
    appRandom = new byte[8];
    sessionIv = new byte[16];
    aesKey = new byte[16];
    cmacKey = new byte[16];
  }

  /** 7-byte plaintext wake-up frame: {@code 5A A5 D5 00 00 F5 FA}. */
  public byte[] createWakeUpFrame() {
    return new byte[] {0x5A, (byte) 0xA5, (byte) 0xD5, 0x00, 0x00, (byte) 0xF5, (byte) 0xFA};
  }

  /** 20-byte plaintext random-exchange frame; stores appRandom for the IV. */
  public byte[] createRandomExchangeFrame(byte keyState) {
    return createRandomExchangeFrame(keyState, randomBytes(8));
  }

  byte[] createRandomExchangeFrame(byte keyState, byte[] appRandom8) {
    byte[] f = new byte[20];
    f[0] = 0x5A;
    f[1] = (byte) 0xA5;
    f[2] = (byte) 0xD6;
    appRandom = Arrays.copyOf(appRandom8, 8);
    System.arraycopy(appRandom8, 0, f, 3, 8);
    f[11] = keyState;
    for (int i = 12; i < 17; i++) f[i] = (byte) 0xFF;
    f[17] = crc8(f, 0, 16);
    f[18] = (byte) 0xF5;
    f[19] = (byte) 0xFA;
    return f;
  }

  /**
   * Parse a random-exchange response payload (16 bytes). On success sets {@code sessionIv =
   * vehicleRandom(8) ‖ appRandom(8)} and returns a 2-element array {keyState, crcCheckResult}; null
   * if not a valid 0x2A/0xD6 response.
   */
  public int[] parseRandomExchange(byte[] p) {
    if (p == null || p.length < 12 || (p[0] & 0xFF) != RESPONSE_RANDOM_EXCHANGE || (p[1] & 0xFF) != 0xD6) {
      return null;
    }
    byte[] vehicleRandom = Arrays.copyOfRange(p, 3, 11);
    byte[] iv = new byte[16];
    System.arraycopy(vehicleRandom, 0, iv, 0, 8);
    System.arraycopy(appRandom, 0, iv, 8, 8);
    sessionIv = iv;
    return new int[] {p[2] & 0xFF, p[11] & 0xFF};
  }

  /** 20-byte encrypted authentication frame; derives aesKey/cmacKey from SHA-256(dkey‖IV). */
  public byte[] createAuthenticationFrame(String dkeyHex) {
    byte[] dkey = decodeHexDkey(dkeyHex);
    byte[] seed = new byte[dkey.length + sessionIv.length];
    System.arraycopy(dkey, 0, seed, 0, dkey.length);
    System.arraycopy(sessionIv, 0, seed, dkey.length, sessionIv.length);
    byte[] digest = sha256(seed);
    aesKey = Arrays.copyOfRange(digest, 0, 16);
    cmacKey = Arrays.copyOfRange(digest, 16, 32);
    byte[] block = new byte[16];
    block[0] = (byte) 0xD8;
    for (int i = 1; i < 12; i++) block[i] = (byte) 0xFF;
    System.arraycopy(aesCmac(cmacKey, block, 12), 0, block, 12, 4);
    return wrapEncrypted(block);
  }

  /** Authentication result byte from a response payload, or -1. */
  public int parseAuthenticationResult(byte[] p) {
    if (p == null || p.length < 3 || (p[0] & 0xFF) != RESPONSE_AUTHENTICATION || (p[1] & 0xFF) != 0xD8) {
      return -1;
    }
    return p[2] & 0xFF;
  }

  /** 20-byte encrypted control frame for the given control code (UNLOCK=5 / LOCK=7). */
  public byte[] createControlFrame(byte controlByte) {
    byte[] block = new byte[16];
    block[0] = (byte) 0xE5;
    block[1] = controlByte;
    sequence = sequence >= 65535 ? 1 : sequence + 1;
    block[2] = (byte) (sequence >>> 8);
    block[3] = (byte) sequence;
    for (int i = 4; i < 12; i++) block[i] = (byte) 0xFF;
    if (controlByte == 3) block[4] = 0;
    System.arraycopy(aesCmac(cmacKey, block, 12), 0, block, 12, 4);
    return wrapEncrypted(block);
  }

  public int parseResponseType(byte[] p) {
    return (p == null || p.length < 1) ? -1 : p[0] & 0xFF;
  }

  public int parseResponseCommandType(byte[] p) {
    return (p == null || p.length < 2) ? -1 : p[1] & 0xFF;
  }

  public int parseControlCode(byte[] p) {
    return (p == null || p.length < 3) ? -1 : p[2] & 0xFF;
  }

  public int parseControlResult(byte[] p) {
    return (p == null || p.length < 4) ? -1 : p[3] & 0xFF;
  }

  public int parseDoorStates(byte[] p) {
    return (p == null || p.length < 5) ? -1 : p[4] & 0xFF;
  }

  private byte[] wrapEncrypted(byte[] block16) {
    byte[] cipher = aesCbcEncryptNoPadding(block16, aesKey, sessionIv);
    byte[] f = new byte[20];
    f[0] = 0x5B;
    f[1] = (byte) 0xB5;
    System.arraycopy(cipher, 0, f, 2, 16);
    f[18] = (byte) 0xF5;
    f[19] = (byte) 0xFA;
    return f;
  }

  // --- helpers -----------------------------------------------------------------------------------

  public static boolean isValidDkey(String s) {
    if (s == null || s.trim().isEmpty()) return false;
    try {
      return decodeHexDkey(s.trim()).length > 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  static byte[] decodeHexDkey(String s) {
    s = s.trim();
    if ((s.length() & 1) != 0 || s.length() > 64) {
      throw new IllegalArgumentException("dkey must be an even number of hex chars (max 64)");
    }
    int n = s.length() / 2;
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++) {
      int hi = Character.digit(s.charAt(i * 2), 16);
      int lo = Character.digit(s.charAt(i * 2 + 1), 16);
      if (hi < 0 || lo < 0) throw new IllegalArgumentException("dkey must be hexadecimal");
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  static byte crc8(byte[] data, int from, int to) {
    int crc = 0;
    for (int i = from; i <= to && i < data.length; i++) {
      crc ^= data[i] & 0xFF;
      for (int b = 0; b < 8; b++) {
        crc = (crc & 0x80) != 0 ? (crc << 1) ^ 7 : crc << 1;
      }
    }
    return (byte) crc;
  }

  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static byte[] aesCbcEncryptNoPadding(byte[] data, byte[] key, byte[] iv) {
    try {
      Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
      return c.doFinal(data);
    } catch (Exception e) {
      throw new IllegalStateException("AES-CBC encryption failed", e);
    }
  }

  private static byte[] aesEcb(byte[] block, byte[] key) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
      return c.doFinal(block);
    } catch (Exception e) {
      throw new IllegalStateException("AES-ECB encryption failed", e);
    }
  }

  static byte[] aesCmac(byte[] key, byte[] data, int len) {
    byte[] l = aesEcb(new byte[16], key);
    byte[] k1 = leftShift(l);
    if ((l[0] & 0x80) != 0) k1[15] ^= (byte) 0x87;
    byte[] k2 = leftShift(k1);
    if ((k1[0] & 0x80) != 0) k2[15] ^= (byte) 0x87;

    byte[] last = new byte[16];
    int blocks = Math.max(1, (len + 15) / 16) - 1;
    int off = blocks * 16;
    if (len > 0 && len % 16 == 0) {
      System.arraycopy(data, off, last, 0, 16);
      xor(last, k1);
    } else {
      int rem = len - off;
      if (rem > 0) System.arraycopy(data, off, last, 0, rem);
      last[rem] = (byte) 0x80;
      xor(last, k2);
    }
    byte[] x = new byte[16];
    for (int i = 0; i < blocks; i++) {
      byte[] blk = Arrays.copyOfRange(data, i * 16, i * 16 + 16);
      xor(blk, x);
      x = aesEcb(blk, key);
    }
    xor(last, x);
    return aesEcb(last, key);
  }

  private static byte[] leftShift(byte[] in) {
    byte[] out = new byte[16];
    int carry = 0;
    for (int i = 15; i >= 0; i--) {
      int v = in[i] & 0xFF;
      out[i] = (byte) (carry | (v << 1));
      carry = v >>> 7;
    }
    return out;
  }

  private static void xor(byte[] a, byte[] b) {
    for (int i = 0; i < a.length; i++) a[i] ^= b[i];
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  /**
   * Scan {@code buf[0..len)} for the first valid 20-byte receive frame and return its 16-byte
   * payload (frame bytes [2..17]). Received frames: header 0xA55A or 0xB55B, tail 0xF5FA, CRC8 over
   * [0..16] at [17]. {@code consumed[0]} is set to how many leading bytes of {@code buf} the caller
   * should drop (past the returned frame, or past scanned garbage when none is found yet). Returns
   * null when no complete valid frame is present yet.
   */
  public static byte[] extractPayload(byte[] buf, int len, int[] consumed) {
    int i = 0;
    while (len - i >= 20) {
      int header = ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
      if (header != 0xA55A && header != 0xB55B) {
        i++;
        continue;
      }
      int tail = ((buf[i + 18] & 0xFF) << 8) | (buf[i + 19] & 0xFF);
      if (tail != 0xF5FA) {
        i++;
        continue;
      }
      byte[] frame = Arrays.copyOfRange(buf, i, i + 20);
      if (crc8(frame, 0, 16) != frame[17]) {
        i++;
        continue;
      }
      consumed[0] = i + 20;
      return Arrays.copyOfRange(frame, 2, 18); // bytes [2..17] = 16-byte payload
    }
    consumed[0] = i; // leading bytes that cannot begin a frame; keep the (<20-byte) remainder
    return null;
  }
}
