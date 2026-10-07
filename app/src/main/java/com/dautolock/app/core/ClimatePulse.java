package com.dautolock.app.core;

/** Always attempts OFF once if ON was dispatched, even if its result is uncertain. */
public final class ClimatePulse {
  public interface Start {
    void run(Runnable dispatched) throws Exception;
  }

  public interface Stop {
    void run() throws Exception;
  }

  public interface Delay {
    void sleep(long milliseconds) throws Exception;
  }

  public static void run(Start start, Stop stop, Delay delay) throws Exception {
    boolean[] dispatched = {false};
    Exception failure = null;
    try {
      start.run(() -> dispatched[0] = true);
      if (!dispatched[0]) throw new Exception("공조 시작 요청이 전송되지 않았습니다");
      delay.sleep(2000);
    } catch (Exception e) {
      failure = e;
    }
    if (dispatched[0]) {
      try {
        stop.run();
      } catch (Exception off) {
        throw new Exception("공조 OFF 결과 미확인 · 공식 앱에서 공조 상태를 확인하세요", off);
      }
    }
    if (failure != null) throw failure;
  }
}
