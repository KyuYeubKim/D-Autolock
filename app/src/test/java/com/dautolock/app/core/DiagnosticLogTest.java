package com.dautolock.app.core;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.Test;

public class DiagnosticLogTest {
  @Test
  public void masksIdentifiersAndCredentials() {
    String s =
        DiagnosticLog.redact(
            "a@example.com 01:23:45:67:89:AB LGX12345678901234 password=secret123 pin=123456"
                + " token=private-token");
    assertFalse(s.contains("secret123"));
    assertFalse(s.contains("123456"));
    assertFalse(s.contains("example.com"));
    assertFalse(s.contains("01:23"));
    assertFalse(s.contains("LGX"));
    assertFalse(s.contains("private-token"));
  }

  @Test
  public void persistsAndExportsInOrder() throws Exception {
    Path dir = Files.createTempDirectory("diagnostics-test");
    DiagnosticLog log = new DiagnosticLog(dir.toFile());
    log.record("START", "raw=-65");
    log.record("BLOCK", "speed=1");
    CompletableFuture<String> f = new CompletableFuture<>();
    log.snapshot(f::complete);
    String data = f.get(5, TimeUnit.SECONDS);
    assertTrue(data.indexOf("START") < data.indexOf("BLOCK"));
    log.close();
    DiagnosticLog restored = new DiagnosticLog(dir.toFile());
    CompletableFuture<String> r = new CompletableFuture<>();
    restored.snapshot(r::complete);
    assertTrue(r.get(5, TimeUnit.SECONDS).contains("speed=1"));
    restored.close();
  }

  @Test
  public void rotationIsBounded() throws Exception {
    Path dir = Files.createTempDirectory("diagnostics-rotation");
    DiagnosticLog log = new DiagnosticLog(dir.toFile(), 250);
    for (int i = 0; i < 100; i++) log.record("SAMPLE", "raw=-70 count=" + i);
    log.close();
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      assertTrue(files.count() <= 3);
    }
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      for (Path p : (Iterable<Path>) files::iterator) assertTrue(Files.size(p) <= 250);
    }
  }
}
