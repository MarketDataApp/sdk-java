package com.marketdata.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
  void allSubtypesCarryContextAndCause() {
    var ctx = new ErrorContext("RAY-X", "https://api.marketdata.app/v1/test/", 500);
    var cause = new RuntimeException("root cause");

    // The four subtypes not exercised by the other tests in this file.
    var net = new NetworkError("network down", ctx, cause);
    var nf = new NotFoundError("not found", ctx);
    var pe = new ParseError("bad json", ctx, cause);
    var se = new ServerError("internal", ctx);

    assertThat(net.getExceptionType()).isEqualTo("NetworkError");
    assertThat(net.getCause()).isSameAs(cause);
    assertThat(nf.getExceptionType()).isEqualTo("NotFoundError");
    assertThat(nf.getCause()).isNull();
    assertThat(pe.getExceptionType()).isEqualTo("ParseError");
    assertThat(pe.getCause()).isSameAs(cause);
    assertThat(se.getExceptionType()).isEqualTo("ServerError");
    assertThat(se.getCause()).isNull();

    for (MarketDataException ex : List.of(net, nf, pe, se)) {
      assertThat(ex.getStatusCode()).isEqualTo(500);
      assertThat(ex.getRequestId()).isEqualTo("RAY-X");
      assertThat(ex.getRequestUrl()).isEqualTo("https://api.marketdata.app/v1/test/");
      assertThat(ex.getTimestamp()).isNotNull();
    }
  }

  @Test
  void everySubtypeExposesBothConstructors() {
    var ctx = ErrorContext.empty();
    var cause = new RuntimeException("cause");

    // Each subtype has two constructors: (msg, ctx) and (msg, ctx, cause).
    // Exercise the one that the other tests in this file don't already hit.
    List<MarketDataException> exhaustive =
        List.of(
            new AuthenticationError("a", ctx, cause),
            new BadRequestError("b", ctx, cause),
            new NotFoundError("n", ctx, cause),
            new RateLimitError("r", ctx, cause),
            new ServerError("s", ctx, cause),
            new NetworkError("net", ctx), // cause-less variant
            new ParseError("p", ctx)); // cause-less variant

    for (MarketDataException ex : exhaustive) {
      assertThat(ex.getMessage()).isNotBlank();
      assertThat(ex.getTimestamp()).isNotNull();
    }
  }

  @Test
  void supportInfoFormatsNullContextAsNotApplicable() {
    // When the exception is built from ErrorContext.empty() (e.g. client-side validation
    // errors that fire before any HTTP request), getSupportInfo() must render each null
    // field as "(n/a)" instead of literal "null". Covers the null-branches of the three
    // ternaries in MarketDataException.getSupportInfo.
    var error = new BadRequestError("symbol must not be blank", ErrorContext.empty());

    String supportInfo = error.getSupportInfo();

    assertThat(supportInfo)
        .contains("BadRequestError")
        .contains("symbol must not be blank")
        .contains("Status code: (n/a)")
        .contains("Request ID:  (n/a)")
        .contains("Request URL: (n/a)")
        .doesNotContain("null");
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
