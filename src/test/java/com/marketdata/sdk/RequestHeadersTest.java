package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.utilities.RequestHeaders;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the {@link RequestHeaders} record's canonical constructor. The wire-level path
 * (a server returning a JSON-{@code null} body) is exercised end-to-end in {@link
 * UtilitiesResourceTest}; this class documents the public-API contract that consumers see when
 * constructing the record directly.
 */
class RequestHeadersTest {

  @Test
  void constructorRejectsNullMapWithNamedFieldMessage() {
    // The package is @NullMarked, so `null` violates the public contract. Pre-checking with
    // requireNonNull yields a clear "headers" message; without it, Map.copyOf(null) throws a bare
    // NPE that leaves the consumer hunting for which constructor argument was null.
    assertThatThrownBy(() -> new RequestHeaders(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("headers");
  }

  @Test
  void constructorAcceptsEmptyMap() {
    // An empty map is a legitimate (if unusual) value — Map.copyOf preserves emptiness and the
    // result is still immutable.
    RequestHeaders rh = new RequestHeaders(Map.of());

    assertThat(rh.headers()).isEmpty();
  }

  @Test
  void constructorDefensivelyCopiesTheInputMap() {
    // Map.copyOf snapshots the input. A consumer mutating the original after construction must
    // not be able to mutate the record's view — this is the defensive-copy guarantee the Javadoc
    // promises.
    Map<String, String> mutable = new HashMap<>();
    mutable.put("accept", "*/*");
    RequestHeaders rh = new RequestHeaders(mutable);

    mutable.put("authorization", "Bearer leaked");

    assertThat(rh.headers()).containsOnlyKeys("accept");
  }

  @Test
  void headersAccessorReturnsImmutableView() {
    // The Map.copyOf result is unmodifiable; consumers attempting to mutate get UOE rather than
    // silently corrupting the record's invariant.
    RequestHeaders rh = new RequestHeaders(Map.of("accept", "*/*"));

    assertThat(rh.headers()).isUnmodifiable();
  }
}
