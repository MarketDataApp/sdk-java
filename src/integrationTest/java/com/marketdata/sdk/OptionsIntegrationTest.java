package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.options.DteFilter;
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

  @Test
  void chainExpirationDteSingleValueSelectsClosestExpiration() {
    // A single dte value narrows to the one expiration closest to it, same as before the API's
    // dte type change — but 365 is far enough from AAPL's front-month expiration (always well
    // under 180 days for a name this liquid) that an unfiltered call could never satisfy the
    // assertions below.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(365))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as("a single dte value selects exactly one expiration")
        .isEqualTo(1);
    Integer dte = chain.get(0).dte();
    assertThat(dte).isNotNull();
    assertThat(dte)
        .as(
            "the expiration closest to the requested 365 dte is far from the front-month"
                + " expiration an unfiltered call would return")
        .isGreaterThan(180);
  }

  @Test
  void chainExpirationDteValuesSelectsClosestPerValueDeduplicated() {
    // Three widely-spaced dte values each resolve to their own closest expiration and dedupe —
    // unlike a single dte value (or no filter at all), which always resolves to one expiration.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(DteFilter.values(30, 180, 365))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as(
            "three widely-spaced dte values resolve to multiple distinct expirations, unlike the"
                + " single front-month expiration an unfiltered call returns")
        .isGreaterThan(1);
  }

  @Test
  void chainExpirationDteRangeReturnsAllExpirationsWithinBounds() {
    // A wide closed range spans multiple expirations, unlike no filter (front-month only); every
    // returned contract's dte must also fall within the bounds, inclusive.
    int max = 180;
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(DteFilter.range(0, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    long distinctExpirations = chain.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as(
            "a wide closed range spans multiple expirations, unlike the single front-month"
                + " expiration an unfiltered call returns")
        .isGreaterThan(1);
    for (OptionQuote q : chain) {
      assertThat(q.dte()).isNotNull();
      assertThat(q.dte())
          .as("every contract's dte falls within the closed range")
          .isBetween(0, max);
    }
  }

  @Test
  void chainExpirationDteComparisonIsInclusiveAtLowerBound() {
    // Anchor N on an actual expiration's dte, discovered via the range form (not the unfiltered
    // call) — then probe the comparison form's boundary against it: >=N must keep the expiration
    // whose dte is exactly N, <N must exclude it.
    List<OptionQuote> withinRange =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(DteFilter.range(0, 365))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    assertThat(withinRange).isNotEmpty();
    int n =
        withinRange.stream()
            .map(OptionQuote::dte)
            .filter(java.util.Objects::nonNull)
            .max(Integer::compareTo)
            .orElseThrow();

    List<OptionQuote> atLeastN =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(DteFilter.comparison(StrikeFilter.Operator.GTE, n))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    assertThat(atLeastN)
        .as(">=N keeps the expiration whose dte is exactly N")
        .extracting(OptionQuote::dte)
        .contains(n);
    for (OptionQuote q : atLeastN) {
      assertThat(q.dte()).isGreaterThanOrEqualTo(n);
    }

    List<OptionQuote> lessThanN =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(DteFilter.comparison(StrikeFilter.Operator.LT, n))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    assertThat(lessThanN)
        .as("<N excludes the expiration whose dte is exactly N")
        .extracting(OptionQuote::dte)
        .doesNotContain(n);
    // An empty answer, or the unfiltered single front-month expiration, would also pass
    // doesNotContain(n) above without proving the server applied the filter — require a
    // non-empty, multi-expiration result where every row is strictly below N.
    assertThat(lessThanN).as("<N is not vacuously satisfied by an empty answer").isNotEmpty();
    for (OptionQuote q : lessThanN) {
      assertThat(q.dte()).isLessThan(n);
    }
    long lessThanDistinctExpirations =
        lessThanN.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(lessThanDistinctExpirations)
        .as(
            "<N spans multiple expirations below N, unlike the single front-month expiration an"
                + " unfiltered call returns")
        .isGreaterThan(1);
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
