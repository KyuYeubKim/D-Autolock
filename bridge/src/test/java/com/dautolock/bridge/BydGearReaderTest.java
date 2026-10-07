package com.dautolock.bridge;

import static org.junit.Assert.*;

import com.dautolock.link.LinkProtocol;
import org.junit.Test;

public class BydGearReaderTest {
  public static class Device {
    public static final int GEARBOX_PARK_BREAK_SWITCH_VALID = 17,
        GEARBOX_PARK_BREAK_SWITCH_INVALID = 23;
    Object mode = 1, current = 3, brake = 17;

    public Object getGearboxAutoModeType() {
      return mode;
    }

    public Object getCurrentGear() {
      return current;
    }

    public Object getParkBrakeSwitch() {
      return brake;
    }
  }

  public static class Fallback {
    public int getCurrentGear() {
      return 3;
    }
  }

  public static class Denied {
    public int getCurrentGear() {
      throw new SecurityException();
    }
  }

  @Test
  public void mapsBothGearEncodings() {
    assertEquals(0, BydGearReader.mode(1));
    assertEquals(2, BydGearReader.mode(3));
    assertEquals(0, BydGearReader.current(3));
    assertEquals(2, BydGearReader.current(0));
    assertEquals(1, BydGearReader.mode(2));
    assertEquals(1, BydGearReader.current(1));
    for (int n : new int[] {4, 5, 6}) assertEquals(3, BydGearReader.mode(n));
    assertEquals(3, BydGearReader.current(2));
    assertEquals(-1, BydGearReader.mode(0));
    assertEquals(-1, BydGearReader.current(4));
  }

  @Test
  public void fallbackDoesNotRequirePrimaryMethod() {
    assertEquals(0, new BydGearReader(new Fallback()).read().gear);
  }

  @Test
  public void lostReadNeverRetainsPreviousP() {
    Device d = new Device();
    BydGearReader r = new BydGearReader(d);
    assertEquals(0, r.read().gear);
    d.mode = null;
    d.current = null;
    assertEquals(-1, r.read().gear);
  }

  @Test
  public void inconsistentKnownGettersBlockP() {
    Device d = new Device();
    d.current = 1;
    LinkProtocol.Sample s = new BydGearReader(d).read();
    assertEquals(-1, s.gear);
    assertEquals(3, s.quality);
  }

  @Test
  public void nonIntegerValuesAreNeverCoercedToNOrP() {
    Device d = new Device();
    d.mode = true;
    d.current = 3.1;
    assertEquals(-1, new BydGearReader(d).read().gear);
  }

  @Test
  public void usesFirmwareBrakeConstantsNotGuessedZeroOrOne() {
    Device d = new Device();
    BydGearReader r = new BydGearReader(d);
    assertEquals(1, r.read().brake);
    d.brake = 23;
    assertEquals(0, r.read().brake);
    d.brake = 0;
    assertEquals(-1, r.read().brake);
  }

  @Test
  public void unsupportedBrakeDoesNotClaimEngaged() {
    LinkProtocol.Sample s = new BydGearReader(new Fallback()).read();
    assertEquals(-1, s.brake);
    assertEquals(0, s.quality);
  }

  @Test
  public void deniedReadIsUnknownWithDiagnostic() {
    LinkProtocol.Sample s = new BydGearReader(new Denied()).read();
    assertEquals(-1, s.gear);
    assertEquals(2, s.quality);
  }
}
