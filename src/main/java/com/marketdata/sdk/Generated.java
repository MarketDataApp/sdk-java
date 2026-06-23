package com.marketdata.sdk;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Internal marker that excludes a constructor, method, or type from JaCoCo line-coverage
 * accounting. JaCoCo automatically ignores any element annotated with an annotation whose simple
 * name contains {@code "Generated"} (since 0.8.2), so this hand-written marker rides that mechanism
 * to satisfy SDK requirements §15.3 ("100% line coverage with explicit ignore on untestable lines")
 * without weakening the threshold for genuinely reachable code.
 *
 * <p>Apply it <strong>only</strong> to code that cannot be exercised by a hermetic unit test:
 * defensive guards unreachable through the public API, fail-safe {@code catch} blocks for failures
 * that cannot be provoked deterministically, and constructor paths that require a live network
 * call. Every use should be accompanied by a comment naming why the element is untestable.
 *
 * <p>{@link RetentionPolicy#CLASS} keeps it out of the runtime-reflective surface; it is not part
 * of the consumer API contract.
 */
@Retention(RetentionPolicy.CLASS)
@Target({TYPE, METHOD, CONSTRUCTOR})
public @interface Generated {}
