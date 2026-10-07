package com.dautolock.link;

import java.security.SecureRandom;
import java.util.UUID;

/** QR contents are a credential: never log them or send them to a web service. */
public final class Pairing {
  private static final String PREFIX = "d-autolock://bridge/v1/";
  public final UUID service;
  public final byte[] key;

  private Pairing(UUID service, byte[] key) {
    this.service = service;
    this.key = key.clone();
  }

  public static Pairing create() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return new Pairing(UUID.randomUUID(), key);
  }

  public String qr() {
    StringBuilder s = new StringBuilder(PREFIX).append(service).append('/');
    for (byte b : key) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  public static Pairing parse(String value) {
    if (value == null
        || !value.matches(
            "d-autolock://bridge/v1/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}"))
      throw new IllegalArgumentException("D-Autolock 차량 보조 앱의 QR 코드가 아닙니다");
    String[] parts = value.substring(PREFIX.length()).split("/");
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++)
      key[i] = (byte) Integer.parseInt(parts[1].substring(i * 2, i * 2 + 2), 16);
    return new Pairing(UUID.fromString(parts[0]), key);
  }
}
