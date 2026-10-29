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

  // A fixed, recent US trading day (not a weekend or market holiday) that the dte tests below
  // pin their baseline and filtered calls to, so the chain's dte values can't shift with the
  // wall clock the way LocalDate.now() would.
  private static final LocalDate DTE_FIXED_TRADING_DAY = LocalDate.of(2026, 10, 27);

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
  void chainExpirationDteSingleValueMatchesClosestExpiration() {
    // Request an available dte value exactly — its own closest expiration is unambiguous (zero
    // distance), so the result's dte must equal it. Picking the farthest-out value keeps it
    // distinct from the unfiltered default, which is the front-month (nearest) expiration.
    // The baseline and the filtered call share one fixed trading day so dte is computed against
    // the same session for both, instead of a wall-clock "today" that would shift with the
    // calendar and could land on a weekend or market holiday.
    LocalDate date = DTE_FIXED_TRADING_DAY;
    List<Integer> baseline = baselineDtes(date);
    int farthest = baseline.get(baseline.size() - 1);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(farthest))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .date(date)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      assertThat(q.dte()).isEqualTo(farthest);
    }
  }

  @Test
  void chainExpirationDteListMatchesClosestExpirationPerValue() {
    // Request two distinct, available dte values exactly: each is its own unambiguous closest
    // candidate (zero distance), so the result can only ever contain those two values, and both
    // must be present. Distinct from the unfiltered default, which carries a single expiration.
    // The baseline and the filtered call share one fixed trading day so dte is computed against
    // the same session for both, instead of a wall-clock "today" that would shift with the
    // calendar and could land on a weekend or market holiday.
    LocalDate date = DTE_FIXED_TRADING_DAY;
    List<Integer> baseline = baselineDtes(date);
    int low = baseline.get(0);
    int high = baseline.get(baseline.size() - 1);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteList(List.of(low, high)))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .date(date)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      assertThat(q.dte()).isIn(low, high);
    }
    assertThat(chain.stream().map(OptionQuote::dte).collect(java.util.stream.Collectors.toSet()))
        .as("each requested value's own candidate must be present")
        .contains(low, high);
  }

  @Test
  void chainExpirationDteRangeReturnsEveryExpirationWithinBounds() {
    // A closed range spanning several available expirations: every row's dte must fall within
    // [min, max], and the span must cover more than one distinct value to differ from the
    // unfiltered (single front-month) default.
    // The baseline and the filtered call share one fixed trading day so dte is computed against
    // the same session for both, instead of a wall-clock "today" that would shift with the
    // calendar and could land on a weekend or market holiday.
    LocalDate date = DTE_FIXED_TRADING_DAY;
    List<Integer> baseline = baselineDtes(date);
    int min = baseline.get(baseline.size() / 4);
    int max = baseline.get(3 * baseline.size() / 4);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(min, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .date(date)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      assertThat(q.dte()).isBetween(min, max);
    }
    long distinctDtes = chain.stream().map(OptionQuote::dte).distinct().count();
    assertThat(distinctDtes)
        .as("a range spanning several expirations holds more than one distinct value")
        .isGreaterThan(1);
  }

  @Test
  void chainExpirationDteComparisonGteReturnsExpirationsAtOrAboveBound() {
    // >= N: every row's dte is >= N, asserted in a way that holds whether the server treats the
    // bound as inclusive or exclusive. The bound sits a third of the way up the baseline so the
    // qualifying set spans more than one expiration, distinguishing it from the unfiltered
    // default (a single front-month row).
    // The baseline and the filtered call share one fixed trading day so dte is computed against
    // the same session for both, instead of a wall-clock "today" that would shift with the
    // calendar and could land on a weekend or market holiday.
    LocalDate date = DTE_FIXED_TRADING_DAY;
    List<Integer> baseline = baselineDtes(date);
    int bound = baseline.get(baseline.size() / 3);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(ExpirationFilter.Operator.GTE, bound))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .date(date)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      assertThat(q.dte()).isGreaterThanOrEqualTo(bound);
    }
    long distinctDtes = chain.stream().map(OptionQuote::dte).distinct().count();
    assertThat(distinctDtes)
        .as("a >= bound below the top of the chain qualifies more than one expiration")
        .isGreaterThan(1);
  }

  @Test
  void chainExpirationDteComparisonLtReturnsExpirationsBelowBound() {
    // < N: every row's dte is <= N, asserted in a way that holds whether the server treats the
    // bound as inclusive or exclusive. The bound sits two-thirds of the way up the baseline so the
    // qualifying set spans more than one expiration, distinguishing it from the unfiltered
    // default (a single front-month row).
    // The baseline and the filtered call share one fixed trading day so dte is computed against
    // the same session for both, instead of a wall-clock "today" that would shift with the
    // calendar and could land on a weekend or market holiday.
    LocalDate date = DTE_FIXED_TRADING_DAY;
    List<Integer> baseline = baselineDtes(date);
    int bound = baseline.get(2 * baseline.size() / 3);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(ExpirationFilter.Operator.LT, bound))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .date(date)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      assertThat(q.dte()).isLessThanOrEqualTo(bound);
    }
    long distinctDtes = chain.stream().map(OptionQuote::dte).distinct().count();
    assertThat(distinctDtes)
        .as("a < bound above the bottom of the chain qualifies more than one expiration")
        .isGreaterThan(1);
  }

  /**
   * Every distinct dte present across AAPL's full expiration chain, ascending — the baseline a
   * dte-filter test derives its candidate values from, per the suite's own convention of deriving
   * expectations from a baseline call rather than from values assumed in advance. {@code date} is
   * the fixed trading day the caller also passes to its filtered call, so both calls see the same
   * session and dte can't shift between them.
   */
  private List<Integer> baselineDtes(LocalDate date) {
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.all())
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .date(date)
                    .build())
            .values();
    List<Integer> dtes =
        chain.stream()
            .map(OptionQuote::dte)
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.toList());
    assertThat(dtes)
        .as("AAPL has at least two distinct expirations")
        .hasSizeGreaterThanOrEqualTo(2);
    return dtes;
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
