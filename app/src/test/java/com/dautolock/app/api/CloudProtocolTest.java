package com.dautolock.app.api;

import static org.junit.Assert.*;

import com.dautolock.app.api.crypto.BangcleCodec;
import org.json.*;
import org.junit.Test;

public class CloudProtocolTest {
  @Test
  public void climatePulseUsesKoreanParameters() throws Exception {
    org.json.JSONObject p = CloudClient.climateParams(14);
    assertEquals(9, p.getInt("mainSettingTemp")); // 23 °C on the 15–31 °C scale (offset 14).
    assertEquals(9, p.getInt("copilotSettingTemp"));
    assertEquals(1, p.getInt("timeSpan"));
    assertEquals(2, p.getInt("airAccuracy"));
    assertEquals("CLOSEWINDOW", CloudClient.Command.CLOSE_WINDOWS.wire);
    assertEquals("OPENWINDOW", CloudClient.Command.OPEN_WINDOWS.wire);
    assertEquals("FINDCAR", CloudClient.Command.FIND_CAR.wire);
    assertEquals("1007", CloudClient.Command.FIND_CAR.feature);
  }

  @Test
  public void climateTemperatureFollowsTheCarsScale() throws Exception {
    assertEquals(7, CloudClient.climateParams(16).getInt("mainSettingTemp")); // HAN 17–33 °C.
    assertEquals(17, CloudClient.climateParams(0).getInt("mainSettingTemp")); // Clamped to 1–17.
    assertEquals(1, CloudClient.climateParams(30).getInt("mainSettingTemp"));
  }

  @Test
  public void pendingIsNotSuccess() throws Exception {
    assertEquals(0, CloudClient.resultState(new JSONObject("{\"res\":1}")));
    assertEquals(0, CloudClient.resultState(new JSONObject()));
    assertEquals(0, CloudClient.resultState(new JSONObject("{\"controlState\":0,\"res\":2}")));
  }

  @Test
  public void terminalOutcomesAreDistinct() throws Exception {
    assertEquals(1, CloudClient.resultState(new JSONObject("{\"res\":2}")));
    assertEquals(-1, CloudClient.resultState(new JSONObject("{\"controlState\":2}")));
    assertEquals(-1, CloudClient.resultState(new JSONObject("{\"res\":3}")));
  }

  @Test
  public void capabilitiesMustHaveActualFunctionNumber() throws Exception {
    JSONObject c =
        new JSONObject(
            "{\"cfFixedList\":[{\"functionNo\":\"1002\",\"cfFixedSecondLevelList\":[{\"functionNo\":\"1005\"}]}]}");
    assertTrue(CloudClient.hasFeature(c, "1005"));
    assertFalse(CloudClient.hasFeature(c, "1031"));
    assertFalse(CloudClient.hasFeature(new JSONObject("{\"label\":\"1031\"}"), "1031"));
  }

  @Test
  public void pinHashMatchesProtocol() {
    assertEquals("E10ADC3949BA59ABBE56E057F20F883E", CryptoUtils.md5Hex("123456"));
  }

  @Test
  public void aesRoundtripSupportsKorean() {
    String k = CryptoUtils.pwdLoginKey("test-only");
    assertEquals("돌핀 테스트", CryptoUtils.aesDecryptUtf8(CryptoUtils.aesEncryptHex("돌핀 테스트", k), k));
  }

  @Test
  public void bangcleTablesArePackagedAndReversible() throws Exception {
    BangcleCodec c = new BangcleCodec();
    String input = "{\"test\":\"D-Autolock\"}";
    String decoded = c.decodeEnvelope(c.encodeEnvelope(input));
    assertTrue(decoded.equals(input) || decoded.equals("F" + input));
  }

  @Test
  public void shutdownUsesPowerCommand() {
    assertEquals("TURNOFFENGINE", CloudClient.Command.STOP.wire);
  }
}
