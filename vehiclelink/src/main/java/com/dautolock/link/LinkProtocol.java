package com.dautolock.link;

import java.io.*;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fixed, bounded binary frames. Each response authenticates a new phone-generated challenge. No
 * remote setters, account data, or commands exist in this protocol.
 */
public final class LinkProtocol {
  public static final int UNKNOWN = -1, P = 0, R = 1, N = 2, D = 3;
  public static final int MISSING = Integer.MIN_VALUE;
  private static final int MAGIC = 0x44414c31;
  private static final SecureRandom RANDOM = new SecureRandom();

  public static final class Sample {
    public final int gear, brake, primary, fallback, rawBrake, quality;

    public Sample(int gear, int brake, int primary, int fallback, int rawBrake, int quality) {
      if (gear < -1 || gear > 3 || brake < -1 || brake > 1 || quality < 0 || quality > 3)
        throw new IllegalArgumentException("Invalid vehicle data");
      this.gear = gear;
      this.brake = brake;
      this.primary = primary;
      this.fallback = fallback;
      this.rawBrake = rawBrake;
      this.quality = quality;
    }

    public String gearLabel() {
      return gear == P ? "P" : gear == R ? "R" : gear == N ? "N" : gear == D ? "D/M/S" : "미확인";
    }

    public String diagnostic() {
      return "gear="
          + gearLabel()
          + " brake="
          + brake
          + " rawMode="
          + primary
          + " rawCurrent="
          + fallback
          + " rawBrake="
          + rawBrake
          + " quality="
          + quality;
    }
  }

  public static byte[] nonce() {
    byte[] n = new byte[16];
    RANDOM.nextBytes(n);
    return n;
  }

  private static byte[] body(int type, byte[] nonce, Sample sample) throws IOException {
    if (nonce.length != 16) throw new IOException("Invalid challenge");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(bytes);
    d.writeInt(MAGIC);
    d.writeByte(type);
    d.write(nonce);
    if (sample != null) {
      d.writeByte(sample.gear);
      d.writeByte(sample.brake);
      d.writeByte(sample.quality);
      d.writeInt(sample.primary);
      d.writeInt(sample.fallback);
      d.writeInt(sample.rawBrake);
    }
    return bytes.toByteArray();
  }

  private static byte[] mac(byte[] key, byte[] body) throws IOException {
    try {
      if (key.length != 32) throw new GeneralSecurityException();
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key, "HmacSHA256"));
      return m.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IOException("Authentication unavailable");
    }
  }

  private static void write(OutputStream stream, byte[] key, byte[] body) throws IOException {
    DataOutputStream out = new DataOutputStream(stream);
    out.writeShort(body.length + 32);
    out.write(body);
    out.write(mac(key, body));
    out.flush();
  }

  private static DataInputStream read(InputStream stream, byte[] key, int type) throws IOException {
    DataInputStream in = new DataInputStream(stream);
    int size = in.readUnsignedShort(), expected = type == 1 ? 53 : 68;
    if (size != expected) throw new IOException("Unsupported frame");
    byte[] b = new byte[size - 32], tag = new byte[32];
    in.readFully(b);
    in.readFully(tag);
    if (!MessageDigest.isEqual(tag, mac(key, b))) throw new IOException("Authentication failed");
    DataInputStream body = new DataInputStream(new ByteArrayInputStream(b));
    if (body.readInt() != MAGIC || body.readByte() != type)
      throw new IOException("Unsupported protocol");
    return body;
  }

  public static void request(OutputStream out, byte[] key, byte[] nonce) throws IOException {
    write(out, key, body(1, nonce, null));
  }

  public static byte[] readRequest(InputStream in, byte[] key) throws IOException {
    byte[] n = new byte[16];
    read(in, key, 1).readFully(n);
    return n;
  }

  public static void respond(OutputStream out, byte[] key, byte[] nonce, Sample sample)
      throws IOException {
    write(out, key, body(2, nonce, sample));
  }

  public static Sample readResponse(InputStream in, byte[] key, byte[] nonce) throws IOException {
    DataInputStream d = read(in, key, 2);
    byte[] received = new byte[16];
    d.readFully(received);
    if (!MessageDigest.isEqual(received, nonce)) throw new IOException("Old response");
    int gear = d.readByte(), brake = d.readByte(), quality = d.readByte();
    try {
      return new Sample(gear, brake, d.readInt(), d.readInt(), d.readInt(), quality);
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid vehicle data");
    }
  }
}
