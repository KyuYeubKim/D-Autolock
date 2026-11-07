package com.dautolock.app.watchkey;

import static org.junit.Assert.*;

import com.dautolock.app.api.CryptoUtils;
import java.util.Locale;
import java.util.TreeMap;
import org.junit.Test;

/** Pure tests for the watch request envelope (no Android, no network). */
public class WatchEnvelopeTest {

  @Test
  public void sortedJsonIsKeyOrderedAndEscaped() {
    TreeMap<String, String> m = new TreeMap<>();
    m.put("b", "2");
    m.put("a", "1");
    m.put("q", "a\"b");
    assertEquals("{\"a\":\"1\",\"b\":\"2\",\"q\":\"a\\\"b\"}", WatchEnvelope.sortedJson(m));
  }

  @Test
  public void encryDataRoundTripsWithTheCountryKey() {
    TreeMap<String, String> m = new TreeMap<>();
    m.put("timeStamp", "1700000000000");
    m.put("random", "ABCDEF");
    m.put("networkType", "wifi");
    String country = "KR";
    String cipher = WatchEnvelope.encryData(m, country);
    String keyHex = CryptoUtils.md5Hex(country).toLowerCase(Locale.ROOT);
    assertEquals(WatchEnvelope.sortedJson(m), CryptoUtils.aesDecryptUtf8(cipher, keyHex));
  }

  @Test
  public void signIsDeterministicAndDependsOnParamsAndSecret() {
    TreeMap<String, String> a = new TreeMap<>();
    a.put("x", "1");
    a.put("y", "2");
    TreeMap<String, String> b = new TreeMap<>();
    b.put("x", "1");
    b.put("y", "3");
    assertEquals(WatchEnvelope.sign(a, "KR"), WatchEnvelope.sign(a, "KR"));
    assertNotEquals(WatchEnvelope.sign(a, "KR"), WatchEnvelope.sign(b, "KR"));
    assertNotEquals(WatchEnvelope.sign(a, "KR"), WatchEnvelope.sign(a, "token"));
  }

  @Test
  public void signSkipsEmptyKeys() {
    TreeMap<String, String> a = new TreeMap<>();
    a.put("x", "1");
    TreeMap<String, String> withEmptyKey = new TreeMap<>(a);
    withEmptyKey.put("", "junk");
    assertEquals(WatchEnvelope.sign(a, "KR"), WatchEnvelope.sign(withEmptyKey, "KR"));
  }
}
