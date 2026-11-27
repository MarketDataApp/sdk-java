package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookupRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import com.marketdata.sdk.stocks.StockResolution;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Request-builder optional setters and cross-field validation that the resource-level tests don't
 * exercise directly. Asserts the reachable validation throws and the opt-in column setters.
 */
class RequestValidationTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 6, 22);

  @Test
  void stockQuotesOptInColumnSettersChain() {
    StockQuotesRequest r =
        StockQuotesRequest.builder("AAPL").extended(true).candle(true).week52(true).build();

    assertThat(r.extended()).isTrue();
    assertThat(r.candle()).isTrue();
    assertThat(r.week52()).isTrue();
  }

  @Test
  void stockQuotesOfFactoryCarriesSymbolsAndDefaultsOptionals() {
    StockQuotesRequest r = StockQuotesRequest.of("AAPL", "MSFT");

    assertThat(r.symbols()).containsExactly("AAPL", "MSFT");
    assertThat(r.extended()).isNull();
    assertThat(r.candle()).isNull();
    assertThat(r.week52()).isNull();
  }

  @Test
  void stockCandlesCountbackSetterIsCarried() {
    StockCandlesRequest r =
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL").to(TODAY).countback(5).build();

    assertThat(r.countback()).isEqualTo(5);
  }

  @Test
  void stockEarningsDateSetterIsCarried() {
    StockEarningsRequest r = StockEarningsRequest.builder("AAPL").date(TODAY).build();

    assertThat(r.date()).isEqualTo(TODAY);
  }

  @Test
  void stockNewsDateAndCountbackSettersAreCarried() {
    assertThat(StockNewsRequest.builder("AAPL").date(TODAY).build().date()).isEqualTo(TODAY);
    assertThat(StockNewsRequest.builder("AAPL").to(TODAY).countback(3).build().countback())
        .isEqualTo(3);
  }

  @Test
  void stockWindowRejectsDatePlusRange() {
    assertThatThrownBy(
            () ->
                StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
                    .date(TODAY)
                    .from(TODAY)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void stockWindowRejectsNonPositiveCountback() {
    assertThatThrownBy(
            () -> StockCandlesRequest.builder(StockResolution.DAILY, "AAPL").countback(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("countback must be positive");
  }

  @Test
  void optionsChainOptionalSettersChain() {
    OptionsChainRequest r =
        OptionsChainRequest.builder("AAPL").am(true).pm(true).delta(0.5).date(TODAY).build();

    assertThat(r.am()).isTrue();
    assertThat(r.pm()).isTrue();
    assertThat(r.date()).isEqualTo(TODAY);
  }

  @Test
  void optionsChainRejectsNegativeMinVolume() {
    assertThatThrownBy(() -> OptionsChainRequest.builder("AAPL").minVolume(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minVolume must be non-negative");
  }

  @Test
  void optionsChainRejectsEmptySymbol() {
    assertThatThrownBy(() -> OptionsChainRequest.builder("").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("symbol must be non-empty");
  }

  @Test
  void optionsQuotesFromSetterIsCarried() {
    OptionsQuotesRequest r =
        OptionsQuotesRequest.builder("AAPL250620C00200000")
            .from(TODAY.minusDays(1))
            .to(TODAY)
            .build();

    assertThat(r.from()).isEqualTo(TODAY.minusDays(1));
  }

  @Test
  void optionsQuotesOfFactoryCarriesSymbolsAndDefaultsOptionals() {
    OptionsQuotesRequest r = OptionsQuotesRequest.of("AAPL250620C00200000", "AAPL250620P00200000");

    assertThat(r.optionSymbols()).containsExactly("AAPL250620C00200000", "AAPL250620P00200000");
    assertThat(r.from()).isNull();
  }

  @Test
  void optionsRequestsRejectEmptyInputs() {
    assertThatThrownBy(() -> OptionsExpirationsRequest.of(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OptionsLookupRequest.of(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OptionsQuoteRequest.of(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fundWindowRejectsNonPositiveCountback() {
    assertThatThrownBy(
            () -> FundCandlesRequest.builder(FundResolution.DAILY, "VFINX").countback(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("countback must be positive");
  }
}
