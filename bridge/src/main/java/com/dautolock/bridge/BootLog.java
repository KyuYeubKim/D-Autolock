package com.dautolock.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Tiny persistent ring of boot/auto-start events so the vehicle-side diagnostic can show whether
 * BOOT_COMPLETED / ACL_CONNECTED arrived and whether the service start was allowed — this cannot be
 * seen from the phone side, and in-memory status is lost across a reboot.
 */
final class BootLog {
  private static final int MAX = 40;

  static synchronized void add(Context c, String line) {
    try {
      SharedPreferences p = c.getSharedPreferences("bridge_boot", 0);
      String stamp =
          new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
      String prev = p.getString("log", "");
      String[] rows = prev.isEmpty() ? new String[0] : prev.split("\n");
      StringBuilder out = new StringBuilder();
      int start = Math.max(0, rows.length - (MAX - 1));
      for (int i = start; i < rows.length; i++) out.append(rows[i]).append('\n');
      out.append(stamp).append(' ').append(line);
      p.edit().putString("log", out.toString()).apply();
    } catch (Throwable ignored) {
    }
  }

  static synchronized String read(Context c) {
    try {
      String log = c.getSharedPreferences("bridge_boot", 0).getString("log", "");
      return log.isEmpty() ? "부팅/자동시작 기록 없음" : log;
    } catch (Throwable e) {
      return "부팅 기록 읽기 실패";
    }
  }
}
