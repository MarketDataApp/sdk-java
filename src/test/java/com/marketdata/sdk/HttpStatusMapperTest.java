package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import org.junit.jupiter.api.Test;

class HttpStatusMapperTest {

  private static final String URL = "https://api.marketdata.app/v1/test/";
  private static final String RAY = "ray-1";

  // ---------- switch coverage: each case + default ----------

  @Test
  void status400MapsToBadRequest() {
    MarketDataException e = HttpStatusMapper.toException(400, URL, RAY);
    assertThat(e).isInstanceOf(BadRequestError.class);
    assertThat(e.getStatusCode()).isEqualTo(400);
    assertThat(e.getMessage()).contains("400");
  }

  @Test
  void status422AlsoMapsToBadRequest() {
    // Same case-arm as 400; without exercising 422 explicitly, half the multi-label arm is
    // unrecorded by JaCoCo.
    MarketDataException e = HttpStatusMapper.toException(422, URL, RAY);
    assertThat(e).isInstanceOf(BadRequestError.class);
    assertThat(e.getStatusCode()).isEqualTo(422);
    assertThat(e.getMessage()).contains("422");
  }

  @Test
  void status401MapsToAuthenticationError() {
    MarketDataException e = HttpStatusMapper.toException(401, URL, RAY);
    assertThat(e).isInstanceOf(AuthenticationError.class);
    assertThat(e.getStatusCode()).isEqualTo(401);
  }

  @Test
  void status429MapsToRateLimitError() {
    MarketDataException e = HttpStatusMapper.toException(429, URL, RAY);
    assertThat(e).isInstanceOf(RateLimitError.class);
    assertThat(e.getStatusCode()).isEqualTo(429);
  }

  @Test
  void everyOtherStatusFallsThroughToServerError() {
    // Any status not explicitly handled (402, 500, 502, 503, 504, weird ones) maps to
    // ServerError. Covers the `default ->` arm.
    for (int code : new int[] {402, 500, 502, 503, 504, 599}) {
      MarketDataException e = HttpStatusMapper.toException(code, URL, RAY);
      assertThat(e).as("status %d", code).isInstanceOf(ServerError.class);
      assertThat(e.getStatusCode()).isEqualTo(code);
    }
  }

  // ---------- emptyToNull: null vs blank vs valid ----------

  @Test
  void nullRequestIdIsPropagatedAsNull() {
    MarketDataException e = HttpStatusMapper.toException(500, URL, null);
    assertThat(e.getRequestId()).isNull();
  }

  @Test
  void blankRequestIdIsTreatedAsNull() {
    // emptyToNull's `s == null || s.isBlank()` short-circuits — without an explicit blank
    // input, the right-hand isBlank() branch is never evaluated.
    MarketDataException e = HttpStatusMapper.toException(500, URL, "   ");
    assertThat(e.getRequestId()).isNull();
  }

  @Test
  void validRequestIdIsPreserved() {
    MarketDataException e = HttpStatusMapper.toException(500, URL, "ray-abc");
    assertThat(e.getRequestId()).isEqualTo("ray-abc");
  }
}
