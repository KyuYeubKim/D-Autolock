package com.dautolock.app;

import android.app.Application;
import java.io.File;
import java.nio.charset.StandardCharsets;

public final class DApplication extends Application {
  private Controller controller;

  public void onCreate() {
    super.onCreate();
    installCrashRecorder();
    controller = new Controller(this);
    reportPreviousExit();
  }

  public Controller controller() {
    return controller;
  }

  private File crashFile() {
    return new File(getFilesDir(), "last-crash.txt");
  }

  /**
   * The diagnostic log writes asynchronously, so a crash is written synchronously to a small file
   * and reported on the next start. Only the exception class and stack frames: no messages.
   */
  private void installCrashRecorder() {
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            StringBuilder trace = new StringBuilder(error.getClass().getName());
            for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
              if (t != error) trace.append(" <- ").append(t.getClass().getName());
              StackTraceElement[] frames = t.getStackTrace();
              for (int i = 0; i < Math.min(6, frames.length); i++)
                trace.append(" @").append(frames[i].getClassName()).append('.')
                    .append(frames[i].getMethodName()).append(':').append(frames[i].getLineNumber());
            }
            java.nio.file.Files.write(
                crashFile().toPath(),
                ("thread=" + thread.getName() + " " + trace).getBytes(StandardCharsets.UTF_8));
          } catch (Throwable ignored) {
          }
          if (previous != null) previous.uncaughtException(thread, error);
        });
  }

  private void reportPreviousExit() {
    try {
      File crash = crashFile();
      if (crash.exists()) {
        String text = new String(java.nio.file.Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8);
        controller.diagnostics.record("APP_CRASH_PREVIOUS", text);
        crash.delete();
      }
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.content.SharedPreferences prefs = getSharedPreferences("settings", 0);
        long seen = prefs.getLong("exitInfoSeen", 0), newest = seen;
        for (android.app.ApplicationExitInfo info :
            getSystemService(android.app.ActivityManager.class)
                .getHistoricalProcessExitReasons(getPackageName(), 0, 5)) {
          if (info.getTimestamp() <= seen) continue;
          newest = Math.max(newest, info.getTimestamp());
          controller.diagnostics.record(
              "APP_EXIT_PREVIOUS",
              "at="
                  + java.time.Instant.ofEpochMilli(info.getTimestamp())
                  + " reason="
                  + info.getReason()
                  + " importance="
                  + info.getImportance()
                  + " status="
                  + info.getStatus());
        }
        if (newest != seen) prefs.edit().putLong("exitInfoSeen", newest).apply();
      }
    } catch (Throwable ignored) {
    }
  }
}
