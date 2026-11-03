package com.dautolock.app.core;

import static org.junit.Assert.*;

import com.dautolock.app.core.ThresholdCalibrator.Pair;
import com.dautolock.app.core.ThresholdCalibrator.Result;
import com.dautolock.app.core.ThresholdCalibrator.Spot;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ThresholdCalibratorTest {
  private static final double LOST = Double.NaN;
  private static final Pair CURRENT = new Pair(-78, -83), CURRENT_BEACON = new Pair(-78, -85);

  private static List<Spot> spots(double[][] values) {
    List<Spot> out = new ArrayList<>();
    for (double[] v : values) out.add(new Spot(v[0], v[1]));
    return out;
  }

  @Test
  public void clearSeparationOpensHalfwayAndLeavesTenDecibelsBeforeLocking() {
    List<Spot> open = spots(new double[][] {{-60, LOST}, {-62, LOST}, {-65, LOST}, {-58, LOST}});
    List<Spot> lock = spots(new double[][] {{-85, LOST}, {-88, LOST}, {-90, LOST}, {-84, LOST}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, null);
    // Reference −85: the strongest lock spot (−84) is set aside with four of them.
    assertEquals(-75, r.primary.near); // Between −85 and −65 (weakest open).
    assertEquals(-85, r.primary.far); // CAL_GAP under near.
    assertNull(r.beacon);
    assertEquals(4, r.openCovered);
    assertEquals(3, r.lockCovered); // −84 locks a step further out.
  }

  @Test
  public void trunkSpotsAreLeftToTheBeaconAndFrontSpotsToTheVehicleBle() {
    // Front doors: vehicle BLE strong, beacon weak. Trunk: the other way round.
    List<Spot> open = spots(new double[][] {{-60, -80}, {-62, -82}, {-82, -55}, {-84, -58}});
    List<Spot> lock = spots(new double[][] {{-88, -90}, {-90, -92}, {-86, -89}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertEquals(-75, r.primary.near); // The trunk's −82/−84 cannot be told from −88: ignored.
    assertEquals(-86, r.primary.far);
    // The beacon is only given the trunk spots (−55/−58), so its weak front readings
    // (−80/−82) do not drag its near down to where it would open from far away.
    assertEquals(-74, r.beacon.near);
    assertEquals(-88, r.beacon.far);
    assertEquals(4, r.openCovered); // Every open spot has one signal over its near.
    assertEquals(3, r.lockCovered); // No lock spot has either signal near.
  }

  @Test
  public void openSpotsThatCannotBeToldApartAreReportedAsNotCovered() {
    List<Spot> open = spots(new double[][] {{-80, LOST}, {-60, LOST}});
    List<Spot> lock = spots(new double[][] {{-78, LOST}, {-90, LOST}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, null);
    assertTrue(r.primary.near >= -78 + ProximityEngine.MIN_GAP_DB); // Never opens at a lock spot.
    assertEquals(1, r.openCovered); // −80 sits below the strongest lock spot.
    assertEquals(1, r.lockCovered); // −78 is inside the band left under near: locks further out.
    assertTrue(r.primary.near - r.primary.far >= ThresholdCalibrator.CAL_GAP);
  }

  @Test
  public void aVehicleBleLostAtTheLockSpotsStillLocksButALostBeaconDoesNot() {
    List<Spot> open = spots(new double[][] {{-62, -60}, {-66, -64}});
    List<Spot> lock = spots(new double[][] {{LOST, -90}, {-88, LOST}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertEquals(2, r.lockCovered); // Lost vehicle BLE locks; −88 is far.
    assertTrue(ThresholdCalibrator.locks(new Spot(LOST, LOST), r.primary, r.beacon));
    assertFalse( // Vehicle BLE in its middle band, beacon lost: nothing says far.
        ThresholdCalibrator.locks(new Spot(r.primary.near - 1, LOST), r.primary, r.beacon));
  }

  @Test
  public void aSignalNeverHeardKeepsItsCurrentThresholds() {
    List<Spot> open = spots(new double[][] {{-60, LOST}});
    List<Spot> lock = spots(new double[][] {{-90, LOST}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertTrue(r.primaryMeasured);
    assertFalse(r.beaconMeasured);
    assertSame(CURRENT_BEACON, r.beacon);
  }

  @Test
  public void resultsStayInsideTheSliderRangeWithTheMinimumGap() {
    Pair strong = ThresholdCalibrator.pair(List.of(-20, -25), List.of(-20, -25), List.of(-30));
    assertEquals(ThresholdCalibrator.MAX_NEAR, strong.near);
    assertTrue(strong.far <= strong.near - ProximityEngine.MIN_GAP_DB);
    Pair weak = ThresholdCalibrator.pair(List.of(-99), List.of(-99), List.of(-110));
    assertTrue(weak.near >= ThresholdCalibrator.MIN_NEAR);
    assertTrue(weak.far >= ThresholdCalibrator.MIN_FAR);
    assertTrue(weak.far <= weak.near - ProximityEngine.MIN_GAP_DB);
  }

  @Test
  public void aWeakSpotTheOtherSignalHearsFarBetterDoesNotLowerThisSignalsNear() {
    // Rear quarter: vehicle BLE −80 (barely above its −87 lock spots), beacon −60 (very clear).
    List<Spot> open =
        spots(new double[][] {{-60, -82}, {-63, -80}, {-80, -60}, {-66, -78}, {-62, -85}});
    List<Spot> lock = spots(new double[][] {{-88, -90}, {-90, -88}, {-87, -91}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertEquals(-77, r.primary.near); // Not −83: the −80 spot belongs to the beacon.
    assertEquals(5, r.openCovered);
    assertEquals(3, r.lockCovered);
  }

  @Test
  public void bothSignalsKeepOpeningWhereBothHearThePhoneClearly() {
    // Both clear their lock spots by ≥ 15 dB: each can open alone (a dead beacon still opens).
    List<Spot> open = spots(new double[][] {{-62, -60}, {-66, -64}});
    List<Spot> lock = spots(new double[][] {{-88, -90}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertTrue(ThresholdCalibrator.opens(new Spot(-66, LOST), r.primary, r.beacon));
    assertTrue(ThresholdCalibrator.opens(new Spot(LOST, -64), r.primary, r.beacon));
  }

  @Test
  public void realLogSpotsNoLongerHugTheCar() {
    // 11-03 19:15–19:18 wizard run (approximate medians). It saved near −61 / far −66 and beacon
    // −64 / −69: the −66 spot in front of the bonnet set the car BLE's bar, the −69 spot behind
    // the boot the beacon's, and the phone in the driver's seat then read −56…−78.
    List<Spot> open =
        spots(
            new double[][] {
              {-61, -79}, {-58, -66}, {-88, -70}, {-68, -67}, {-77, -72}, {-63, -71}
            });
    List<Spot> lock =
        spots(
            new double[][] {
              {-74, -80}, {-76, -69}, {-75, -78}, {-66, -74}, {-75, -78}, {-80, -86}
            });
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertEquals(-68, r.primary.near);
    assertEquals(-78, r.primary.far); // Below everything seen in the seat but one −78 blip.
    assertEquals(-69, r.beacon.near);
    assertEquals(-79, r.beacon.far);
  }

  @Test
  public void theDriversSeatReadingKeepsFarWellBelowIt() {
    List<Spot> open = spots(new double[][] {{-60, -80}, {-62, -82}});
    List<Spot> inside = spots(new double[][] {{-80, -50}}); // Car BLE shadowed in the seat.
    List<Spot> lock = spots(new double[][] {{-88, -90}, {-90, -92}, {-86, -89}});
    Result r = ThresholdCalibrator.calibrate(open, inside, lock, CURRENT, CURRENT_BEACON);
    assertEquals(-92, r.primary.far); // −80 − 12: the car BLE no longer locks at these spots …
    assertEquals(3, r.lockCovered); // … the beacon does (−89 and weaker), and never in the seat.
    assertEquals(1, r.insideKept);
    // A car BLE that is not heard in the seat would lock there after its loss wait.
    Result lost =
        ThresholdCalibrator.calibrate(
            open, spots(new double[][] {{LOST, -50}}), lock, CURRENT, CURRENT_BEACON);
    assertEquals(0, lost.insideKept);
  }

  @Test
  public void oneUnusuallyStrongLockSpotIsSetAsideOnlyWithThreeOrMore() {
    assertEquals(Integer.valueOf(-66), ThresholdCalibrator.reference(List.of(-80, -66)));
    assertEquals(Integer.valueOf(-74), ThresholdCalibrator.reference(List.of(-80, -66, -74)));
    assertNull(ThresholdCalibrator.reference(new ArrayList<>()));
  }
}
