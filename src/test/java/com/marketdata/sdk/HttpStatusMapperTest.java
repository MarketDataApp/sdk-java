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
  @ValueSource(ints = {402, 403, 405, 418})
  void maps_unhandled_four_xx_to_bad_request_error(int statusCode) {
    @Nullable MarketDataException exception = HttpStatusMapper.map(statusCode, context(statusCode));

    assertThat(exception).isExactlyInstanceOf(BadRequestError.class);
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
