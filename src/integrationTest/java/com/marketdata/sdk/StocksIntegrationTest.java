package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.stocks.StockCandle;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarning;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsArticle;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPrice;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuote;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import com.marketdata.sdk.stocks.StockResolution;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for the {@code stocks} resource against the live Market Data API. Gated by the
 * {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} environment variable in {@code build.gradle.kts}; a
 * valid {@code MARKETDATA_TOKEN} is also required.
 *
 * <p>Tests assert <strong>shape</strong> rather than specific values, since live data drifts daily.
 * AAPL/MSFT are used everywhere — large, always-populated tickers. Status is asserted as {@code 200
 * || 203} (203 = cached/delayed data, which the SDK surfaces as success) so the suite doesn't flap
 * with market hours.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StocksIntegrationTest {

  private static final String SYMBOL = "AAPL";

  private MarketDataClient client;

  @BeforeAll
  void setUp() {
    client = new MarketDataClient();
  }

  @AfterAll
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void csvCandlesReturnsRawCsvText() {
    CsvResponse resp =
        client
            .stocks()
            .asCsv()
            .candles(
                StockCandlesRequest.builder(StockResolution.DAILY, SYMBOL)
                    .from(LocalDate.now().minusMonths(1))
                    .to(LocalDate.now())
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.isCsv()).isTrue();
    assertThat(resp.csv()).as("CSV facet returns comma-delimited text").isNotBlank().contains(",");
  }

  @Test
  void candlesReturnsDailyOhlcvSeries() {
    StockCandlesResponse resp =
        client
            .stocks()
            .candles(
                StockCandlesRequest.builder(StockResolution.DAILY, SYMBOL)
                    .from(LocalDate.now().minusMonths(1))
                    .to(LocalDate.now())
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).as("AAPL has daily candles in the last month").isNotEmpty();
    StockCandle first = resp.values().get(0);
    assertThat(first.time()).isNotNull();
    assertThat(first.time().getZone().getId()).isEqualTo("America/New_York");
    assertThat(first.close()).isNotNull();
  }

  @Test
  void quoteFetchesSingleSymbol() {
    StockQuotesResponse resp = client.stocks().quote(StockQuoteRequest.of(SYMBOL));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).hasSize(1);
    StockQuote q = resp.values().get(0);
    assertThat(q.symbol()).isEqualTo(SYMBOL);
  }

  @Test
  void quoteCandleAndWeek52OptInColumnsDecode() {
    StockQuote q =
        client
            .stocks()
            .quote(StockQuoteRequest.builder(SYMBOL).candle(true).week52(true).build())
            .values()
            .get(0);

    // Opt-in columns: present when the market returns them, never a ParseError. Assert any
    // populated value is finite.
    if (q.close() != null) {
      assertThat(q.close()).isFinite();
    }
    if (q.week52High() != null) {
      assertThat(q.week52High()).isFinite();
    }
  }

  @Test
  void quotesBatchesMultipleSymbolsInOneResponse() {
    StockQuotesResponse resp =
        client.stocks().quotes(StockQuotesRequest.builder(SYMBOL, "MSFT").build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(resp.values().stream().map(StockQuote::symbol)).contains(SYMBOL, "MSFT");
  }

  @Test
  void pricesBatchesMultipleSymbols() {
    StockPricesResponse resp = client.stocks().prices(StockPricesRequest.of(SYMBOL, "MSFT"));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).hasSizeGreaterThanOrEqualTo(2);
    StockPrice p = resp.values().get(0);
    assertThat(p.symbol()).isNotNull();
    if (p.mid() != null) {
      assertThat(p.mid()).isFinite();
    }
  }

  @Test
  void newsReturnsArticlesWithScalarUpdated() {
    StockNewsResponse resp = client.stocks().news(StockNewsRequest.of(SYMBOL));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).as("AAPL always has recent news").isNotEmpty();
    StockNewsArticle a = resp.values().get(0);
    assertThat(a.headline()).isNotBlank();
    assertThat(a.publicationDate().getZone().getId()).isEqualTo("America/New_York");
    // Live (non-date-bounded) query carries the scalar `updated`.
    assertThat(resp.updated()).isNotNull();
  }

  @Test
  void earningsReturnsHistoryWithNullableFields() {
    StockEarningsResponse resp = client.stocks().earnings(StockEarningsRequest.of(SYMBOL));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).isNotEmpty();
    // Every row decodes; nullable fundamentals/forward fields never trip a ParseError.
    for (StockEarning e : resp.values()) {
      assertThat(e.symbol()).isEqualTo(SYMBOL);
      if (e.reportedEPS() != null) {
        assertThat(e.reportedEPS()).isFinite();
      }
    }
  }
}
