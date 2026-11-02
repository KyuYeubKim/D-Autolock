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
  public void clearSeparationPutsNearHalfwayAndFarJustAboveTheStrongestLockSpot() {
    List<Spot> open = spots(new double[][] {{-60, LOST}, {-62, LOST}, {-65, LOST}, {-58, LOST}});
    List<Spot> lock = spots(new double[][] {{-85, LOST}, {-88, LOST}, {-90, LOST}, {-84, LOST}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, null);
    assertEquals(-74, r.primary.near); // Between −84 (strongest lock) and −65 (weakest open).
    assertEquals(-82, r.primary.far); // −84 + 2, still ≥ 5 dB under near.
    assertNull(r.beacon);
    assertEquals(4, r.openCovered);
    assertEquals(4, r.lockCovered);
  }

  @Test
  public void trunkSpotsAreLeftToTheBeaconAndFrontSpotsToTheVehicleBle() {
    // Front doors: vehicle BLE strong, beacon weak. Trunk: the other way round.
    List<Spot> open = spots(new double[][] {{-60, -80}, {-62, -82}, {-82, -55}, {-84, -58}});
    List<Spot> lock = spots(new double[][] {{-88, -90}, {-90, -92}, {-86, -89}});
    Result r = ThresholdCalibrator.calibrate(open, lock, CURRENT, CURRENT_BEACON);
    assertEquals(-74, r.primary.near); // The trunk's −82/−84 cannot be told from −86: ignored.
    assertEquals(-84, r.primary.far);
    // The beacon is only given the trunk spots (−55/−58), so its weak front readings
    // (−80/−82) do not drag its near down to where it would open from far away.
    assertEquals(-73, r.beacon.near);
    assertEquals(-87, r.beacon.far);
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
    assertEquals(2, r.lockCovered);
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
    assertEquals(-76, r.primary.near); // Not −82: the −80 spot belongs to the beacon.
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
}
