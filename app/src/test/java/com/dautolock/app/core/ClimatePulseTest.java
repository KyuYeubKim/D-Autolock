package com.dautolock.app.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ClimatePulseTest {
  @Test
  public void onAckThenTwoSecondsThenOff() throws Exception {
    List<String> calls = new ArrayList<>();
    ClimatePulse.run(
        sent -> {
          calls.add("ON");
          sent.run();
        },
        () -> calls.add("OFF"),
        ms -> calls.add("WAIT " + ms));
    assertEquals(Arrays.asList("ON", "WAIT 2000", "OFF"), calls);
  }

  @Test
  public void uncertainOnStillAttemptsOffOnce() {
    List<String> calls = new ArrayList<>();
    try {
      ClimatePulse.run(
          sent -> {
            calls.add("ON");
            sent.run();
            throw new Exception("timeout");
          },
          () -> calls.add("OFF"),
          ms -> calls.add("WAIT"));
      fail();
    } catch (Exception expected) {
      assertEquals(Arrays.asList("ON", "OFF"), calls);
    }
  }

  @Test
  public void canceledBeforeSendDoesNotTurnOffUnrelatedClimate() {
    List<String> calls = new ArrayList<>();
    try {
      ClimatePulse.run(
          sent -> {
            throw new Exception("cancel");
          },
          () -> calls.add("OFF"),
          ms -> calls.add("WAIT"));
      fail();
    } catch (Exception expected) {
      assertTrue(calls.isEmpty());
    }
  }

  @Test
  public void offFailureIsVisible() {
    try {
      ClimatePulse.run(
          Runnable::run,
          () -> {
            throw new Exception("timeout");
          },
          ms -> {});
      fail();
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("OFF"));
    }
  }
}
