package com.dautolock.app.core;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.Test;

public class LogDisplayTest {
  @Test
  public void koreaTimeRollsDateAndNewestRowsComeFirstRegardlessOfPhoneTimezone() {
    TimeZone previous = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
      String output =
          LogDisplay.newestKorean(
              "D-Autolock diagnostics · timestamps UTC\n"
                  + "2026-10-07T14:59:59Z | OLD | older\n2026-10-07T15:00:01Z | NEW | newer\n",
              24000);
      assertTrue(output.contains("2026-10-08 00:00:01.000 | NEW"));
      assertTrue(output.indexOf("NEW") < output.indexOf("OLD"));
      assertEquals("00:00:01", LogDisplay.clock(Instant.parse("2026-10-07T15:00:01Z")));
      assertFalse(output.contains("timestamps UTC"));
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  @Test
  public void truncationPreservesNewestWholeRowsAndEqualTimestampsReverseInRecordingOrder() {
    String raw = "2026-10-07T15:00:01Z | FIRST | a\n2026-10-07T15:00:01Z | SECOND | b\n";
    String all = LogDisplay.newestKorean(raw, 24000);
    assertTrue(all.indexOf("SECOND") < all.indexOf("FIRST"));
    String limited = LogDisplay.newestKorean(raw, 75);
    assertTrue(limited.contains("SECOND"));
    assertFalse(limited.contains("FIRST"));
    assertTrue(limited.contains("파일로"));
  }
}
