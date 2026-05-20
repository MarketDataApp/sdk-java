package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpStatusMapperTest {

  private static final Instant TS = Instant.parse("2026-05-15T12:00:00Z");

  private static ErrorContext context(int statusCode) {
    return ErrorContext.forResponse("https://api.example", statusCode, "req-1", TS);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 203, 204, 299})
  void returns_null_for_two_xx_status(int statusCode) {
    assertThat(HttpStatusMapper.map(statusCode, context(statusCode))).isNull();
  }

  @ParameterizedTest
  @MethodSource("statusToExceptionType")
  void maps_status_code_to_expected_subtype(
      int statusCode, Class<? extends MarketDataException> expectedType) {
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(expectedType);
    assertThat(exception.getStatusCode()).isEqualTo(statusCode);
  }

  static Stream<Arguments> statusToExceptionType() {
    return Stream.of(
        arguments(400, BadRequestError.class),
        arguments(401, AuthenticationError.class),
        arguments(404, NotFoundError.class),
        arguments(429, RateLimitError.class),
        arguments(500, ServerError.class),
        arguments(501, ServerError.class),
        arguments(502, ServerError.class),
        arguments(503, ServerError.class),
        arguments(599, ServerError.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {402, 403, 405, 418, 422, 451})
  void maps_unhandled_four_xx_to_bad_request_error(int statusCode) {
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(BadRequestError.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {402, 403, 405, 418})
  void unhandled_four_xx_message_includes_the_status_code(int statusCode) {
    // The mapper differentiates the failure mode within the message (e.g. "Client error: HTTP
    // 403") so consumers can branch on getMessage() / getStatusCode() even though the type is the
    // shared BadRequestError bucket dictated by ADR-002's canonical 7-permit hierarchy.
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isNotNull();
    assertThat(exception.getMessage())
        .contains("Client error")
        .contains(String.valueOf(statusCode));
  }

  // ---------- 3xx redirects ----------

  @ParameterizedTest
  @ValueSource(ints = {301, 302, 303, 304, 307, 308})
  void maps_three_xx_to_bad_request_with_redirect_message(int statusCode) {
    // HttpClient is configured with followRedirects(NORMAL); a 3xx escaping that means the
    // redirect could not be followed (cross-protocol, max-redirects hit, etc.). Treat as
    // BadRequestError so the retry layer does not loop on the same redirect, with a message
    // that points the user at the likely cause.
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(BadRequestError.class);
    assertThat(exception.getMessage())
        .contains("Unhandled redirect")
        .contains(String.valueOf(statusCode))
        .contains("baseUrl");
    assertThat(exception.getStatusCode()).isEqualTo(statusCode);
  }

  // ---------- 1xx informational ----------

  @ParameterizedTest
  @ValueSource(ints = {100, 101, 102})
  void maps_one_xx_to_bad_request_with_informational_message(int statusCode) {
    // HttpClient handles 100 Continue internally — reaching the mapper with a 1xx means the
    // server is doing something protocol-weird. Surface with a clear "informational" message
    // rather than the generic "Unexpected status code".
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(BadRequestError.class);
    assertThat(exception.getMessage())
        .contains("informational")
        .contains(String.valueOf(statusCode));
  }

  // ---------- out-of-range fallback ----------

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 600, 999})
  void maps_out_of_range_to_bad_request_with_unexpected_message(int statusCode) {
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(BadRequestError.class);
    assertThat(exception.getMessage())
        .contains("Unexpected HTTP status")
        .contains(String.valueOf(statusCode));
  }

  // ---------- 5xx messages include the actual status ----------

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 599})
  void server_error_message_includes_the_actual_status(int statusCode) {
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(ServerError.class);
    assertThat(exception.getMessage()).contains(String.valueOf(statusCode));
  }

  // ---------- §9.4 Retry-After on 429 (RFC 6585) ----------

  @Test
  void rate_limit_error_carries_retry_after_when_present() {
    Duration retryAfter = Duration.ofSeconds(45);

    @Nullable MarketDataException exception = HttpStatusMapper.map(429, context(429), retryAfter);

    assertThat(exception).isExactlyInstanceOf(RateLimitError.class);
    RateLimitError rle = (RateLimitError) exception;
    assertThat(rle.getRetryAfter()).contains(retryAfter);
  }

  @Test
  void rate_limit_error_retry_after_is_empty_when_absent() {
    @Nullable MarketDataException exception = HttpStatusMapper.map(429, context(429), null);

    assertThat(exception).isExactlyInstanceOf(RateLimitError.class);
    RateLimitError rle = (RateLimitError) exception;
    assertThat(rle.getRetryAfter()).isEmpty();
  }

  @Test
  void error_carries_the_full_context() {
    ErrorContext ctx = context(401);

    MarketDataException exception = HttpStatusMapper.map(401, ctx);

    assertThat(exception).isNotNull();
    assertThat(exception.getContext()).isEqualTo(ctx);
    assertThat(exception.getRequestUrl()).isEqualTo("https://api.example");
    assertThat(exception.getRequestId()).isEqualTo("req-1");
    assertThat(exception.getTimestamp()).isEqualTo(TS);
  }
}
