package com.marketdata.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketDataExceptionTest {

  @Test
  void emptyContextLeavesFieldsNull() {
    var error = new BadRequestError("symbol must not be blank", ErrorContext.empty());

    assertThat(error.getRequestId()).isNull();
    assertThat(error.getRequestUrl()).isNull();
    assertThat(error.getStatusCode()).isNull();
    assertThat(error.getTimestamp()).isNotNull();
    assertThat(error.getExceptionType()).isEqualTo("BadRequestError");
  }

  @Test
  void carriesContextFields() {
    var ctx =
        new ErrorContext(
            "8a1b2c3d4e5f6g7h-SJC", "https://api.marketdata.app/v1/stocks/quotes/AAPL/", 429);

    var error = new RateLimitError("Rate limit exceeded", ctx);

    assertThat(error.getRequestId()).isEqualTo("8a1b2c3d4e5f6g7h-SJC");
    assertThat(error.getStatusCode()).isEqualTo(429);
    assertThat(error.getExceptionType()).isEqualTo("RateLimitError");
  }

  @Test
  void supportInfoIncludesAllRequiredFields() {
    var ctx = new ErrorContext("RAY-1", "https://api.marketdata.app/v1/stocks/quotes/AAPL/", 429);
    var error = new RateLimitError("Rate limit exceeded", ctx);

    String supportInfo = error.getSupportInfo();

    assertThat(supportInfo)
        .contains("RateLimitError")
        .contains("Rate limit exceeded")
        .contains("429")
        .contains("RAY-1")
        .contains("https://api.marketdata.app/v1/stocks/quotes/AAPL/")
        .contains("US/Eastern");
  }

  @Test
  void supportInfoNeverContainsSensitiveData() {
    // The exception itself never receives the token; we just
    // double-check that the canonical message+URL form doesn't leak.
    var ctx = new ErrorContext("RAY-1", "https://api.marketdata.app/v1/user/", 401);
    var error = new AuthenticationError("Invalid token", ctx);

    assertThat(error.getSupportInfo()).doesNotContain("token=").doesNotContain("Bearer ");
  }
}
