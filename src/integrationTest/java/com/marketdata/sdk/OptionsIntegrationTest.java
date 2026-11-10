package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.options.ExpirationFilter;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionSide;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookupRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.options.StrikeFilter;
import com.marketdata.sdk.options.StrikeRange;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for the {@code options} resource against the live Market Data API. Gated by the
 * {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} environment variable in {@code build.gradle.kts}; a
 * valid {@code MARKETDATA_TOKEN} is also required (the {@link MarketDataClient} constructor's
 * startup validation will surface a clear error if missing).
 *
 * <p>Tests assert <strong>shape</strong> ("AAPL has at least one expiration", "every quote row
 * carries finite greeks") rather than specific values, since the live data drifts daily. AAPL is
 * used as the underlying everywhere — it is the largest options market by volume so the response is
 * always non-empty during market hours and historical queries are well-populated outside them.
 *
 * <p>Status is asserted as {@code 200 || 203}: the API returns <strong>203 Non-Authoritative
 * Information</strong> when it serves cached/delayed data (outside market hours, or on a
 * cached/delayed plan), which the SDK surfaces as a normal success. Pinning to 200 would make these
 * tests flap with market hours.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OptionsIntegrationTest {

  private static final String UNDERLYING = "AAPL";

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
  void csvExpirationsReturnsRawCsvText() {
    CsvResponse resp =
        client.options().asCsv().expirations(OptionsExpirationsRequest.of(UNDERLYING));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.isCsv()).isTrue();
    // expirations CSV is a single date column, so assert non-blank text rather than a delimiter.
    assertThat(resp.csv()).isNotBlank();
  }

  @Test
  void lookupConvertsHumanDescriptionToOccSymbol() {
    // A far-future date keeps the test stable against expiration drift — the endpoint converts
    // the description regardless of whether such a contract actually exists today.
    OptionsLookupResponse resp =
        client.options().lookup(OptionsLookupRequest.of("AAPL 1/16/2026 $200 Call"));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values())
        .as("OCC symbol shape: 4-6 letter root + YYMMDD + C/P + 8-digit strike")
        .matches("[A-Z]{1,6}\\d{6}[CP]\\d{8}");
  }

  @Test
  void expirationsReturnsAtLeastOneFutureDate() {
    OptionsExpirationsResponse resp =
        client.options().expirations(OptionsExpirationsRequest.of(UNDERLYING));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).as("AAPL has options expirations year-round").isNotEmpty();
    assertThat(resp.updated()).isNotNull();
    assertThat(resp.updated().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void chainReturnsFilteredContracts() {
    // Light filter: a narrow strike-limit window keeps the response small without depending on
    // a specific dte that might fall on a non-trading day.
    OptionsChainResponse resp =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .side(OptionSide.CALL)
                    .strikeLimit(5)
                    .strikeRange(StrikeRange.ITM)
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).isNotEmpty();
    OptionQuote first = resp.values().get(0);
    assertThat(first.optionSymbol()).startsWith(UNDERLYING);
    assertThat(first.side()).isEqualTo("call");
    assertThat(first.strike()).isGreaterThan(0.0);
  }

  @Test
  void chainExpirationAllSpansMultipleExpirations() {
    // expiration=all is the distinguishing case: omitting the filter returns only the front-month
    // expiration, whereas all() returns the full chain. strikeLimit(1) keeps it to ~one contract
    // per expiration so the payload stays small while still proving the span.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.all())
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as("expiration=all returns every expiration, not just the front-month")
        .isGreaterThan(1);
  }

  // ---------- chain: dte query param (integer -> string) ----------

  @Test
  void chainDteSingleValueSelectsOneExpirationFarFromFrontMonth() {
    // A far-dated single dte value selects the one expiration closest to it — a window chosen
    // well past where the front-month (a bare, unfiltered chain call's default) sits, so the
    // filter is provably doing the selecting rather than just reproducing the default.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(300))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as("a single dte value selects exactly one expiration")
        .isEqualTo(1);
    assertThat(chain.get(0).dte())
        .as("the selected expiration lands well past the front month a bare call would return")
        .isGreaterThan(200);
  }

  @Test
  void chainDteListSelectsOneExpirationPerValue() {
    // A comma-separated dte list spans several widely-separated windows; a bare chain call
    // returns only the single front-month expiration, so more than one distinct expiration here
    // proves the list reached the server rather than being collapsed to one value.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteList(30, 120, 300))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    // 30/120/300 are far enough apart that dedup can't collapse them — the API documents one
    // closest expiration selected per value, so this must land on exactly three, not merely
    // "more than the one expiration a bare call returns" (which a range or single-value misread
    // of the list would also satisfy).
    assertThat(distinctExpirations)
        .as("a comma-separated dte list selects the closest expiration for each of the 3 values")
        .isEqualTo(3);
  }

  @Test
  void chainDteRangeReturnsEveryExpirationWithinBounds() {
    // A closed dte range should return every expiration inside it (inclusive), not just the
    // closest one — AAPL's weekly cycle guarantees more than one expiration lands in a 90-day
    // window that starts well past the front month.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(60, 150))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    assertThat(chain)
        .as("every row's dte falls within the closed, inclusive range")
        .allSatisfy(q -> assertThat(q.dte()).isBetween(60, 150));
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as("the range spans more than the single front-month expiration a bare call returns")
        .isGreaterThan(1);
  }

  @Test
  void chainDteComparisonGteExcludesTheFrontMonth() {
    // >=180 excludes the near-term front-month expiration a bare call returns — every row must
    // sit at or beyond the bound.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, 180))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    assertThat(chain)
        .as(">=180 keeps only expirations at or beyond the bound")
        .allSatisfy(q -> assertThat(q.dte()).isGreaterThanOrEqualTo(180));
    // A single closest-to-180 expiration would also satisfy the per-row bound above — the
    // documented comparison-form behavior ("every expiration whose dte falls within the bounds")
    // is only distinguished from the single-value form by spanning more than one expiration.
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as(">=180 spans more than the single front-month expiration a bare call returns")
        .isGreaterThan(1);
  }

  @Test
  void chainDteComparisonLtSpansMultipleExpirationsUnderBound() {
    // <365 stays under the bound on every row, but spans many expirations — unlike the single
    // front-month expiration a bare call returns.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator.LT, 365))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    assertThat(chain)
        .as("every row stays under the bound")
        .allSatisfy(q -> assertThat(q.dte()).isLessThan(365));
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as("<365 spans many expirations, unlike the single front-month a bare call returns")
        .isGreaterThan(1);
  }

  @Test
  void chainDteComparisonBoundaryIsInclusiveAtGteAndExclusiveAtLt() {
    // Discover a real dte value via the range filter (a call made with the parameter, not the
    // unfiltered chain), then prove the inclusive/exclusive boundary the comparison operators
    // claim: the exact same dte is kept by >=N and left out by <N.
    List<OptionQuote> withinRange =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(60, 150))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    assertThat(withinRange).isNotEmpty();
    int boundaryDte = withinRange.stream().mapToInt(OptionQuote::dte).min().orElseThrow();

    List<OptionQuote> atOrAfter =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, boundaryDte))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    List<OptionQuote> strictlyBefore =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.LT, boundaryDte))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(atOrAfter)
        .as(">=N keeps the expiration at exactly N")
        .anySatisfy(q -> assertThat(q.dte()).isEqualTo(boundaryDte));
    // noneSatisfy below passes vacuously on an empty list — assert there's actually something
    // returned for <N (the near-term/front-month expirations below the [60,150] window) before
    // checking that none of them is the boundary itself.
    assertThat(strictlyBefore).as("<N returns the expirations below the boundary").isNotEmpty();
    assertThat(strictlyBefore)
        .as("<N excludes the expiration at exactly N")
        .noneSatisfy(q -> assertThat(q.dte()).isEqualTo(boundaryDte));
  }

  @Test
  void chainDecodesOptionalRhoColumn() {
    // rho is an optional column: the live feed may or may not populate it. Assert the SDK decodes
    // whatever comes back without error — every row's rho is either null (omitted) or a finite
    // double, never a ParseError from a missing required column.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .side(OptionSide.CALL)
                    .strikeLimit(3)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      Double rho = q.rho();
      if (rho != null) {
        assertThat(rho.doubleValue()).isFinite();
      }
    }
  }

  @Test
  void quoteCountbackBoundsHistoricalSeries() {
    // countback=N with to caps the EOD series to at most N rows. A front-month ITM AAPL call has
    // ample recent history, so we expect between 1 and 5 rows — the upper bound is the real
    // assertion that countback reached the wire.
    String optionSymbol = sampleOptionSymbol();

    OptionsQuotesResponse resp =
        client
            .options()
            .quote(
                OptionsQuoteRequest.builder(optionSymbol).to(LocalDate.now()).countback(5).build());

    assertThat(resp.values())
        .as("countback caps the series to at most 5 rows")
        .hasSizeBetween(1, 5);
  }

  @Test
  void quoteFetchesSingleContract() {
    // Derive a real option symbol from the chain so the quote endpoint has a live contract to
    // resolve — lookup gives a well-formed symbol but doesn't guarantee one exists in the API's
    // data set.
    String optionSymbol = sampleOptionSymbol();

    OptionsQuotesResponse resp = client.options().quote(OptionsQuoteRequest.of(optionSymbol));

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).hasSize(1);
    OptionQuote q = resp.values().get(0);
    assertThat(q.optionSymbol()).isEqualTo(optionSymbol);
    assertThat(q.underlying()).isEqualTo(UNDERLYING);
  }

  @Test
  void quotesFansOutToMultipleContracts() {
    // Use the first two contracts from the chain so both are guaranteed to exist.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .side(OptionSide.CALL)
                    .strikeLimit(2)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();
    assertThat(chain).hasSizeGreaterThanOrEqualTo(2);
    // optionSymbol is @Nullable on the row (every field is, to support columns projection); on a
    // live chain it's always present, so assert that and keep the symbols non-null for the fan-out.
    String first = java.util.Objects.requireNonNull(chain.get(0).optionSymbol());
    String second = java.util.Objects.requireNonNull(chain.get(1).optionSymbol());

    Map<String, OptionsQuotesResponse> resp =
        client.options().quotes(OptionsQuotesRequest.builder(first, second).build());

    assertThat(resp.keySet()).containsExactly(first, second);
    assertThat(resp.get(first).values().get(0).optionSymbol()).isEqualTo(first);
    assertThat(resp.get(second).values().get(0).optionSymbol()).isEqualTo(second);
  }

  /** Fetch one real option symbol — used by {@link #quoteFetchesSingleContract()}. */
  private String sampleOptionSymbol() {
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();
    assertThat(chain).isNotEmpty();
    return chain.get(0).optionSymbol();
  }
}
