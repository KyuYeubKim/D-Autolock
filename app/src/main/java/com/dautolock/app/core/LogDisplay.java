package com.dautolock.app.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Storage/export remains UTC; the reader uses Korea time regardless of phone timezone. */
public final class LogDisplay {
  private static final class Row {
    final Instant time;
    final String detail;

    Row(Instant time, String detail) {
      this.time = time;
      this.detail = detail;
    }
  }

  private static final DateTimeFormatter KOREAN =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)
          .withZone(ZoneId.of("Asia/Seoul"));
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss", Locale.KOREA).withZone(ZoneId.of("Asia/Seoul"));

  public static String clock(Instant instant) {
    return CLOCK.format(instant);
  }

  public static String newestKorean(String raw, int limit) {
    List<Row> entries = new ArrayList<>();
    for (String line : raw.split("\\r?\\n")) {
      int separator = line.indexOf(" | ");
      if (separator < 0) continue;
      try {
        entries.add(
            new Row(Instant.parse(line.substring(0, separator)), line.substring(separator)));
      } catch (Exception ignored) {
      }
    }
    // Reverse first so equal timestamps retain reverse recording order (sort is stable).
    Collections.reverse(entries);
    entries.sort((a, b) -> b.time.compareTo(a.time));
    StringBuilder out = new StringBuilder("한국 시간 (KST, UTC+9) · 최신순\n\n");
    for (Row entry : entries) {
      String line = KOREAN.format(entry.time) + entry.detail + "\n";
      if (out.length() + line.length() > limit) {
        out.append("\n이전 기록은 진단 로그 파일로 확인하세요.");
        break;
      }
      out.append(line);
    }
    if (entries.isEmpty()) out.append("저장된 기록이 없습니다.");
    return out.toString();
  }
}
