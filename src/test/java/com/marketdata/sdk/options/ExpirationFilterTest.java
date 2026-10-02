package com.marketdata.sdk.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the {@link ExpirationFilter} records' canonical constructors, exercised
 * directly (bypassing the {@code dteList}/{@code dteRange}/{@code dteComparison} factories) to
 * confirm the invariants hold for any caller, not just the factory entry points. The wire-level
 * path is exercised end-to-end in {@code OptionsResourceTest}.
 */
class ExpirationFilterTest {

  @Test
  void dteListCanonicalConstructorRejectsReversedInvariantsLikeFactory() {
    assertThatThrownBy(() -> new ExpirationFilter.DteList(List.of(15, -1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dte must be non-negative");
  }

  @Test
  void dteListCanonicalConstructorDefensivelyCopiesInputList() {
    List<Integer> mutable = new ArrayList<>();
    mutable.add(15);
    mutable.add(30);
    ExpirationFilter.DteList filter = new ExpirationFilter.DteList(mutable);

    mutable.add(999);

    assertThat(filter.days()).containsExactly(15, 30);
  }

  @Test
  void dteListCanonicalConstructorReturnsUnmodifiableList() {
    ExpirationFilter.DteList filter = new ExpirationFilter.DteList(List.of(15, 30));

    assertThat(filter.days()).isUnmodifiable();
  }

  @Test
  void dteRangeCanonicalConstructorRejectsReversedRange() {
    assertThatThrownBy(() -> new ExpirationFilter.DteRange(45, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("min must be <= max");
  }

  @Test
  void dteComparisonCanonicalConstructorRejectsNullOperator() {
    assertThatThrownBy(() -> new ExpirationFilter.DteComparison(null, 30))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operator must not be null");
  }
}
