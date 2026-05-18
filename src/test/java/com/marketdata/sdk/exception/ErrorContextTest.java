package com.marketdata.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ErrorContextTest {

  @Test
  void for_response_carries_all_fields() {
    Instant ts = Instant.parse("2026-05-15T12:00:00Z");

    ErrorContext ctx =
        ErrorContext.forResponse("https://api.example/v1/markets/status", 401, "req-1", ts);

    assertThat(ctx.requestUrl()).isEqualTo("https://api.example/v1/markets/status");
    assertThat(ctx.statusCode()).isEqualTo(401);
    assertThat(ctx.requestId()).isEqualTo("req-1");
    assertThat(ctx.timestamp()).isEqualTo(ts);
  }

  @Test
  void for_response_allows_null_request_id() {
    ErrorContext ctx =
        ErrorContext.forResponse(
            "https://api.example", 500, null, Instant.parse("2026-05-15T12:00:00Z"));

    assertThat(ctx.requestId()).isNull();
  }

  @Test
  void for_no_response_uses_zero_status_and_null_request_id() {
    Instant ts = Instant.parse("2026-05-15T12:00:00Z");

    ErrorContext ctx = ErrorContext.forNoResponse("https://api.example/v1/markets/status", ts);

    assertThat(ctx.statusCode()).isZero();
    assertThat(ctx.requestId()).isNull();
    assertThat(ctx.requestUrl()).isEqualTo("https://api.example/v1/markets/status");
    assertThat(ctx.timestamp()).isEqualTo(ts);
  }
}
