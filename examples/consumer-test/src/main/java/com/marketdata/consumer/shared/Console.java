package com.marketdata.consumer.shared;

import com.marketdata.sdk.exception.MarketDataException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Pretty-print helpers shared across every demo app. The output is plain
 * text, no ANSI colors — these demos are meant to be readable in any
 * terminal and copy-pastable into bug reports.
 */
public final class Console {

  private Console() {}

  /** Print a section header to make demo output scan-able. */
  public static void header(String title) {
    System.out.println();
    System.out.println("==== " + title + " ====");
  }

  /** Print a sub-step under the current header. */
  public static void step(String description) {
    System.out.println();
    System.out.println("  -- " + description);
  }

  /** Indented success line. */
  public static void ok(String message) {
    System.out.println("    ✓ " + message);
  }

  /** Indented failure line — used when an exception is the expected outcome. */
  public static void fail(String message) {
    System.out.println("    ✗ " + message);
  }

  /** Indented info line. */
  public static void info(String message) {
    System.out.println("    · " + message);
  }

  /**
   * Run {@code body} and either print {@code expected} on a thrown
   * {@link MarketDataException}, or "no exception" if it succeeds. The full
   * support-info dump is printed under the exception line so consumers can
   * see the §6 shape end-to-end.
   */
  public static void expectException(String expected, Runnable body) {
    try {
      body.run();
      fail("expected " + expected + " but call returned normally");
    } catch (MarketDataException e) {
      ok("got " + e.getExceptionType() + " — message: " + e.getMessage());
      System.out.println(e.getSupportInfo().indent(6).stripTrailing());
    } catch (RuntimeException e) {
      fail("expected " + expected + " but got " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Run {@code body}, print {@code printer.toString(result)} on success, or the exception on failure. */
  public static <T> void run(Supplier<T> body, java.util.function.Function<T, String> printer) {
    try {
      ok(printer.apply(body.get()));
    } catch (MarketDataException e) {
      fail(e.getExceptionType() + ": " + e.getMessage());
    }
  }

  /** Same as {@link #run} but prints elapsed wall-clock — useful for retry/backoff demos. */
  public static <T> void runTimed(Supplier<T> body, java.util.function.Function<T, String> printer) {
    long startNanos = System.nanoTime();
    try {
      ok(printer.apply(body.get()));
    } catch (MarketDataException e) {
      fail(e.getExceptionType() + ": " + e.getMessage());
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    info("wall-time: " + elapsed.toMillis() + " ms");
  }
}
