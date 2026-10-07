package com.dautolock.app.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded local logs. Only status summaries, never account fields or API bodies. */
public final class DiagnosticLog {
  private final File directory;
  private final long limit;
  private final ExecutorService writer = Executors.newSingleThreadExecutor();
  private volatile String failure = "";

  public DiagnosticLog(File directory) {
    this(directory, 512 * 1024);
  }

  public DiagnosticLog(File directory, long limit) {
    this.directory = directory;
    this.limit = limit;
  }

  public static String redact(String value) {
    if (value == null) return "unknown";
    return value
        .replaceAll("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}", "[BT-address]")
        .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]")
        .replaceAll("\\b[A-HJ-NPR-Z0-9]{17}\\b", "[VIN]")
        .replaceAll(
            "(?i)(password|pin|token|commandPwd|signToken|encryToken)\\s*[=:]\\s*\\S+",
            "$1=[redacted]")
        .replaceAll("[A-Za-z0-9_+/=-]{32,}", "[long-value]")
        .replace('\n', ' ')
        .replace('\r', ' ');
  }

  public void record(String event, String detail) {
    String safe = redact(detail);
    if (safe.length() > 1400) safe = safe.substring(0, 1400) + " [truncated]";
    String line = Instant.now() + " | " + event + " | " + safe + "\n";
    writer.execute(
        () -> {
          try {
            append(line);
          } catch (Exception e) {
            failure = "진단 로그 저장 실패: " + e.getClass().getSimpleName();
          }
        });
  }

  private void append(String line) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("log directory");
    File current = new File(directory, "current.log");
    if (current.length() + line.getBytes(StandardCharsets.UTF_8).length > limit) {
      Files.deleteIfExists(new File(directory, "previous-2.log").toPath());
      File previous = new File(directory, "previous-1.log");
      if (previous.exists())
        Files.move(previous.toPath(), new File(directory, "previous-2.log").toPath());
      if (current.exists()) Files.move(current.toPath(), previous.toPath());
    }
    try (Writer w =
        new OutputStreamWriter(new FileOutputStream(current, true), StandardCharsets.UTF_8)) {
      w.write(line);
    }
  }

  public void snapshot(Consumer<String> callback) {
    writer.execute(
        () -> {
          StringBuilder out =
              new StringBuilder(
                  "D-Autolock diagnostics · timestamps UTC\n"
                      + "Local status and RSSI only. No credentials or raw cloud responses.\n");
          try {
            for (String name : new String[] {"previous-2.log", "previous-1.log", "current.log"}) {
              File f = new File(directory, name);
              if (f.exists())
                out.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            }
          } catch (Exception e) {
            out.append("Read failed: ").append(e.getClass().getSimpleName());
          }
          if (!failure.isEmpty()) out.append('\n').append(failure);
          callback.accept(out.toString());
        });
  }

  public String failure() {
    return failure;
  }

  public void clear(Runnable done) {
    writer.execute(
        () -> {
          for (String name : new String[] {"current.log", "previous-1.log", "previous-2.log"})
            try {
              Files.deleteIfExists(new File(directory, name).toPath());
            } catch (IOException e) {
              failure = "진단 로그 삭제 실패";
            }
          done.run();
        });
  }

  public void close() throws InterruptedException {
    writer.shutdown();
    writer.awaitTermination(10, TimeUnit.SECONDS);
  }
}
