package com.marketdata.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SealedHierarchyTest {

  @Test
  void permits_exactly_the_seven_canonical_subtypes() {
    // ADR-002 fixes the canonical list at exactly these 7 permits. Expanding requires an ADR
    // amendment — adding a permit silently would break consumers compiling against the documented
    // shape on JDK 21+ (pattern matching for switch). This snapshot is the regression guard.
    Class<?>[] permitted = MarketDataException.class.getPermittedSubclasses();

    assertThat(permitted)
        .containsExactlyInAnyOrder(
            AuthenticationError.class,
            BadRequestError.class,
            NotFoundError.class,
            RateLimitError.class,
            ServerError.class,
            NetworkError.class,
            ParseError.class);
  }

  @Test
  void base_class_is_sealed() {
    assertThat(MarketDataException.class.isSealed()).isTrue();
  }

  @Test
  void all_subtypes_are_final() {
    for (Class<?> subtype : MarketDataException.class.getPermittedSubclasses()) {
      assertThat(java.lang.reflect.Modifier.isFinal(subtype.getModifiers()))
          .as("Subtype %s must be final", subtype.getSimpleName())
          .isTrue();
    }
  }
}
