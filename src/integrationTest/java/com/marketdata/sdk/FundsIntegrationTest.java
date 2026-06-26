package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.funds.FundCandle;
import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for the {@code funds} resource against the live Market Data API. Gated by the
 * {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} environment variable in {@code build.gradle.kts}; a
 * valid {@code MARKETDATA_TOKEN} is also required.
 *
 * <p>Tests assert <strong>shape</strong> rather than specific values, since live data drifts daily.
 * VFINX (Vanguard 500 Index) is used everywhere — a large, always-populated mutual fund. Status is
 * asserted as {@code 200 || 203} (203 = cached/delayed data, which the SDK surfaces as success).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FundsIntegrationTest {

  private static final String SYMBOL = "VFINX";

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
            .funds()
            .asCsv()
            .candles(
                FundCandlesRequest.builder(FundResolution.DAILY, SYMBOL)
                    .from(LocalDate.now().minusMonths(1))
                    .to(LocalDate.now())
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.isCsv()).isTrue();
    assertThat(resp.csv()).as("CSV facet returns comma-delimited text").isNotBlank().contains(",");
  }

  @Test
  void candlesReturnsDailyOhlcSeries() {
    FundCandlesResponse resp =
        client
            .funds()
            .candles(
                FundCandlesRequest.builder(FundResolution.DAILY, SYMBOL)
                    .from(LocalDate.now().minusMonths(1))
                    .to(LocalDate.now())
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).as("VFINX has daily candles in the last month").isNotEmpty();
    FundCandle first = resp.values().get(0);
    assertThat(first.time()).isNotNull();
    assertThat(first.time().getZone().getId()).isEqualTo("America/New_York");
    assertThat(first.close()).isNotNull();
    assertThat(first.close()).isFinite();
  }

  @Test
  void candlesCountbackWindowDecodes() {
    FundCandlesResponse resp =
        client
            .funds()
            .candles(
                FundCandlesRequest.builder(FundResolution.DAILY, SYMBOL)
                    .to(LocalDate.now())
                    .countback(5)
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).isNotEmpty();
    // Every row decodes with finite prices; funds carry no volume column to assert on.
    for (FundCandle c : resp.values()) {
      if (c.close() != null) {
        assertThat(c.close()).isFinite();
      }
    }
  }
}
