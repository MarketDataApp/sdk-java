package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.funds.FundResolution;
import com.marketdata.sdk.stocks.StockResolution;
import org.junit.jupiter.api.Test;

/** Value-object behaviour of the {@code StockResolution} / {@code FundResolution} token types. */
class ResolutionTest {

  @Test
  void stockResolutionFactoriesEmitWireTokens() {
    assertThat(StockResolution.weeks(2).wireValue()).isEqualTo("2W");
    assertThat(StockResolution.months(3).wireValue()).isEqualTo("3M");
    assertThat(StockResolution.years(1).wireValue()).isEqualTo("1Y");
  }

  @Test
  void stockResolutionOfRejectsBlank() {
    assertThatThrownBy(() -> StockResolution.of("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
  }

  @Test
  void stockResolutionValueSemantics() {
    StockResolution a = StockResolution.days(1);
    StockResolution b = StockResolution.days(1);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(StockResolution.weeks(1));
    assertThat(a).isNotEqualTo("1D");
    assertThat(a.toString()).isEqualTo("StockResolution[1D]");
  }

  @Test
  void fundResolutionValueSemantics() {
    FundResolution a = FundResolution.days(1);
    FundResolution b = FundResolution.days(1);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(FundResolution.WEEKLY);
    assertThat(a).isNotEqualTo("1D");
    assertThat(a.toString()).isEqualTo("FundResolution[1D]");
  }
}
