package com.dautolock.app.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Plain-language activity rows for the dashboard. Text is the same note() wording; no secrets. */
public final class ActivityFeed {
  public enum Kind {
    SUCCESS("완료"),
    FAILURE("실패"),
    WAIT("보류"),
    INFO("알림");

    public final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  public static final class Entry {
    public final long time;
    public final String text;
    public final int count;

    public Entry(long time, String text) {
      this(time, text, 1);
    }

    private Entry(long time, String text, int count) {
      this.time = time;
      this.text = text;
      this.count = count;
    }
  }

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss", Locale.KOREA).withZone(KST);
  private static final DateTimeFormatter DAY_CLOCK =
      DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.KOREA).withZone(KST);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd", Locale.KOREA).withZone(KST);

  /** Failure wins over waiting, which wins over success, so mixed messages never look successful. */
  public static Kind kind(String text) {
    if (containsAny(text, "못했", "실패", "오류", "거부", "불가", "찾지 못", "삭제할 수 없")) return Kind.FAILURE;
    if (containsAny(text, "보류", "대기", "처리 중", "확인 중", "재연결 중", "다시 시도", "먼저", "필요", "확인하세요"))
      return Kind.WAIT;
    if (containsAny(text, "완료", "확인됨", "확인했습니다", "복원했습니다", "연결 ·", "켜짐", "시작", "저장", "삭제했습니다"))
      return Kind.SUCCESS;
    return Kind.INFO;
  }

  /** Same door wording as the dashboard: an unlocked door is shown as "도어 열기". */
  public static String friendly(String text) {
    return text.replace("잠금 해제", "도어 열기").replace('\n', ' ').trim();
  }

  /** Newest first; consecutive identical messages are folded into one row with a count. */
  public static List<Entry> collapse(List<Entry> newestFirst, int limit) {
    List<Entry> out = new ArrayList<>();
    for (Entry e : newestFirst) {
      Entry last = out.isEmpty() ? null : out.get(out.size() - 1);
      if (last != null && last.text.equals(e.text))
        out.set(out.size() - 1, new Entry(last.time, last.text, last.count + e.count));
      else if (out.size() < limit) out.add(e);
      else break;
    }
    return out;
  }

  /** Korea time regardless of phone timezone, with a short relative hint. */
  public static String time(long time, long now) {
    Instant at = Instant.ofEpochMilli(time);
    long age = Math.max(0, now - time) / 1000;
    if (!DAY.format(at).equals(DAY.format(Instant.ofEpochMilli(now))))
      return DAY_CLOCK.format(at) + " KST";
    String relative =
        age < 60 ? "방금" : age < 3600 ? age / 60 + "분 전" : age / 3600 + "시간 전";
    return CLOCK.format(at) + " KST · " + relative;
  }

  private static boolean containsAny(String text, String... words) {
    for (String w : words) if (text.contains(w)) return true;
    return false;
  }
}
