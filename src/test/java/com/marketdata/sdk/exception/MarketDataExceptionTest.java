package com.marketdata.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketDataExceptionTest {

  private static final Instant TS = Instant.parse("2026-05-15T12:00:00Z");

  private static ErrorContext sampleContext() {
    return ErrorContext.forResponse("https://api.example/v1/markets/status", 401, "req-abc", TS);
  }

  @Test
  void exposes_all_context_fields_via_getters() {
    AuthenticationError error = new AuthenticationError("Token invalid", sampleContext());

    assertThat(error.getMessage()).isEqualTo("Token invalid");
    assertThat(error.getStatusCode()).isEqualTo(401);
    assertThat(error.getRequestUrl()).isEqualTo("https://api.example/v1/markets/status");
    assertThat(error.getRequestId()).isEqualTo("req-abc");
    assertThat(error.getTimestamp()).isEqualTo(TS);
    assertThat(error.getExceptionType()).isEqualTo("AuthenticationError");
    assertThat(error.getContext()).isEqualTo(sampleContext());
  }

  @Test
  void preserves_cause_when_provided() {
    IOException cause = new IOException("connection refused");

    NetworkError error = new NetworkError("Network failure", sampleContext(), cause);

    assertThat(error.getCause()).isSameAs(cause);
  }

  @Test
  void support_info_matches_spec_format() {
    AuthenticationError error = new AuthenticationError("Token invalid", sampleContext());

    String info = error.getSupportInfo();

    assertThat(info)
        .contains("--- MARKET DATA SUPPORT INFO ---")
        .contains("--------------------------------")
        .contains("request_id:")
        .contains("request_url:")
        .contains("status_code:")
        .contains("timestamp:")
        .contains("message:")
        .contains("exception_type:")
        .contains("AuthenticationError")
        .contains("Token invalid")
        .contains("401")
        .contains("https://api.example/v1/markets/status")
        .contains("req-abc");
  }

  @Test
  void support_info_renders_timestamp_in_us_eastern() {
    // 2026-05-15T12:00:00Z is during EDT (UTC-4): expected 2026-05-15 08:00:00
    AuthenticationError summer = new AuthenticationError("x", sampleContext());
    assertThat(summer.getSupportInfo()).contains("2026-05-15 08:00:00");

    // 2026-01-15T12:00:00Z is during EST (UTC-5): expected 2026-01-15 07:00:00
    ErrorContext winterCtx =
        ErrorContext.forResponse(
            "https://api.example", 500, "r", Instant.parse("2026-01-15T12:00:00Z"));
    ServerError winter = new ServerError("x", winterCtx);
    assertThat(winter.getSupportInfo()).contains("2026-01-15 07:00:00");
  }

  @Test
  void support_info_preserves_field_order_per_spec() {
    AuthenticationError error = new AuthenticationError("Token invalid", sampleContext());

    String info = error.getSupportInfo();

    assertThat(info.indexOf("request_id:")).isLessThan(info.indexOf("request_url:"));
    assertThat(info.indexOf("request_url:")).isLessThan(info.indexOf("status_code:"));
    assertThat(info.indexOf("status_code:")).isLessThan(info.indexOf("timestamp:"));
    assertThat(info.indexOf("timestamp:")).isLessThan(info.indexOf("message:"));
    assertThat(info.indexOf("message:")).isLessThan(info.indexOf("exception_type:"));
  }

  @Test
  void support_info_handles_missing_request_id() {
    ErrorContext ctx = ErrorContext.forResponse("https://api.example", 500, null, TS);
    ServerError error = new ServerError("Server boom", ctx);

    String info = error.getSupportInfo();

    assertThat(info).contains("request_id:     (not provided)");
    assertThat(info).doesNotContain("request_id:     null");
  }

  @Test
  void support_info_handles_no_response_context() {
    ErrorContext ctx = ErrorContext.forNoResponse("https://api.example", TS);
    NetworkError error = new NetworkError("Connection refused", ctx);

    String info = error.getSupportInfo();

    assertThat(info)
        .contains("exception_type: NetworkError")
        .contains("status_code:    0")
        .contains("request_id:     (not provided)");
  }

  @Test
  void support_info_uses_sixteen_char_column_padding() {
    AuthenticationError error = new AuthenticationError("Token invalid", sampleContext());

    String info = error.getSupportInfo();

    // exception_type: is 15 chars + 1 space = 16; value starts at column 16
    assertThat(info).contains("exception_type: AuthenticationError");
    // request_id: is 11 chars + 5 spaces = 16
    assertThat(info).contains("request_id:     req-abc");
  }

  @Test
  void can_be_thrown_and_caught_as_market_data_exception() {
    assertThatThrownBy(
            () -> {
              throw new RateLimitError("Quota exceeded", sampleContext());
            })
        .isInstanceOf(MarketDataException.class)
        .isInstanceOf(RateLimitError.class)
        .hasMessage("Quota exceeded");
  }

  @Test
  void supports_instanceof_dispatch_over_sealed_hierarchy() {
    MarketDataException exception = new RateLimitError("rate limited", sampleContext());

    String label;
    if (exception instanceof AuthenticationError) {
      label = "auth";
    } else if (exception instanceof BadRequestError) {
      label = "bad";
    } else if (exception instanceof NotFoundError) {
      label = "notfound";
    } else if (exception instanceof RateLimitError) {
      label = "rate";
    } else if (exception instanceof ServerError) {
      label = "server";
    } else if (exception instanceof NetworkError) {
      label = "network";
    } else if (exception instanceof ParseError) {
      label = "parse";
    } else {
      label = "unknown";
    }

    assertThat(label).isEqualTo("rate");
  }

  @Test
  void request_url_redacts_query_string() {
    ErrorContext ctx =
        ErrorContext.forResponse("https://api.example/v1/stocks/quote?token=secret", 200, "r", TS);
    ServerError error = new ServerError("x", ctx);

    assertThat(error.getRequestUrl()).isEqualTo("https://api.example/v1/stocks/quote?…");
  }

  @Test
  void request_url_returns_malformed_url_verbatim() {
    // A space makes new URI(...) throw URISyntaxException; the getter must not propagate it.
    ErrorContext ctx = ErrorContext.forResponse("http://exa mple.com/x?q=1", 200, "r", TS);
    ServerError error = new ServerError("x", ctx);

    assertThat(error.getRequestUrl()).isEqualTo("http://exa mple.com/x?q=1");
  }

  @Test
  void support_info_renders_empty_message_when_null() {
    ServerError error = new ServerError(null, sampleContext());

    assertThat(error.getSupportInfo()).contains("message:").doesNotContain("message:        null");
  }

  @Test
  void rate_limit_error_three_arg_constructor_carries_cause_without_retry_after() {
    IOException cause = new IOException("boom");
    RateLimitError error = new RateLimitError("limited", sampleContext(), cause);

    assertThat(error.getCause()).isSameAs(cause);
    assertThat(error.getRetryAfter()).isEmpty();
  }

  @Test
  void server_error_three_arg_constructor_carries_cause_without_retry_after() {
    IOException cause = new IOException("boom");
    ServerError error = new ServerError("server boom", sampleContext(), cause);

    assertThat(error.getCause()).isSameAs(cause);
    assertThat(error.getRetryAfter()).isEmpty();
  }
}
