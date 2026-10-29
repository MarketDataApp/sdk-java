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
import java.util.Objects;
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
  void chainDteSingleValueSelectsTheClosestExpiration() {
    // Pick the farthest real expiration (not the front-month) so a server that silently ignored
    // the filter — falling back to its own front-month default — would be caught: that default
    // would not match "target" here.
    List<Integer> dtes = liveDistinctDtes();
    int target = dtes.get(dtes.size() - 1);

    List<OptionQuote> result =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(target))
                    .side(OptionSide.CALL)
                    .build())
            .values();

    assertThat(result).isNotEmpty();
    assertThat(result.stream().map(OptionQuote::dte).distinct().toList())
        .as("dte=%d resolves to the single expiration closest to that value", target)
        .containsExactly(target);
  }

  @Test
  void chainDteListResolvesToEachValuesClosestExpiration() {
    List<Integer> dtes = liveDistinctDtes();
    int first = dtes.get(0);
    int last = dtes.get(dtes.size() - 1);

    List<OptionQuote> result =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteList(List.of(first, last)))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(result.stream().map(OptionQuote::dte).distinct().toList())
        .as("dte list resolves to exactly the two requested expirations, nothing else")
        .containsExactlyInAnyOrder(first, last);
  }

  @Test
  void chainDteRangeKeepsExpirationsWithinInclusiveBounds() {
    // An interior subrange (second through second-to-last baseline dte) so the requested range is
    // a strict subset of the full chain — unlike the full span, this distinguishes a working range
    // filter from one that was ignored (expiration=all's own front-to-back span would otherwise
    // look
    // identical to the filtered answer).
    List<Integer> dtes = liveDistinctDtes();
    int min = dtes.get(1);
    int max = dtes.get(dtes.size() - 2);
    int belowRange = dtes.get(0);
    int aboveRange = dtes.get(dtes.size() - 1);

    List<OptionQuote> result =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(min, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    List<Integer> resultDtes = result.stream().map(OptionQuote::dte).distinct().toList();
    assertThat(resultDtes)
        .as("every returned expiration falls within the inclusive [%d, %d] bound", min, max)
        .allMatch(d -> d >= min && d <= max);
    assertThat(resultDtes)
        .as("the range's own endpoints are kept (bounds are inclusive)")
        .contains(min, max);
    assertThat(resultDtes)
        .as("expirations outside the requested range are excluded, not just the ones inside kept")
        .doesNotContain(belowRange, aboveRange);
  }

  @Test
  void chainDteComparisonBoundaryIsInclusiveAtGteAndExclusiveAtLt() {
    // Compare each comparison answer against the exact baseline slice it ought to produce (not
    // just "contains" / "does not contain" the boundary): a server that ignored the dte filter
    // entirely would fall back to its own front-month default, which happens not to carry
    // "boundary" either, so a bare doesNotContain(boundary) on the "<" half would pass whether or
    // not the filter was honored. The exact-match on both halves proves inclusion and exclusion at
    // once: nothing below the boundary leaks into ">=", and nothing at or above it leaks into "<".
    List<Integer> dtes = liveDistinctDtes();
    int boundary = dtes.get(dtes.size() / 2);
    List<Integer> atOrAboveBoundary = dtes.stream().filter(d -> d >= boundary).toList();
    List<Integer> belowBoundary = dtes.stream().filter(d -> d < boundary).toList();

    List<OptionQuote> included =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(ExpirationFilter.Operator.GTE, boundary))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    List<Integer> includedDtes =
        included.stream().map(OptionQuote::dte).distinct().sorted().toList();
    assertThat(includedDtes)
        .as(
            ">=%d keeps every baseline expiration at or above %d, boundary included",
            boundary, boundary)
        .allMatch(d -> d >= boundary)
        .containsExactlyElementsOf(atOrAboveBoundary);

    List<OptionQuote> excluded =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(ExpirationFilter.Operator.LT, boundary))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    List<Integer> excludedDtes =
        excluded.stream().map(OptionQuote::dte).distinct().sorted().toList();
    assertThat(excludedDtes)
        .as(
            "<%d keeps exactly the baseline expirations below %d, boundary excluded",
            boundary, boundary)
        .containsExactlyElementsOf(belowBoundary);
  }

  /**
   * Ground truth for the dte-filter assertions: the distinct dte values present in today's full
   * AAPL call chain, discovered via the already-covered {@code expiration=all} filter rather than
   * computed from the local clock. AAPL carries far more than four standard expirations at any
   * time, so the index picks used by the dte tests (first / second / middle / second-to-last /
   * last) are always distinct, and the range test's interior subrange always has at least one
   * baseline dte strictly outside it on each side.
   */
  private List<Integer> liveDistinctDtes() {
    List<OptionQuote> all =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.all())
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    List<Integer> dtes =
        all.stream().map(OptionQuote::dte).filter(Objects::nonNull).distinct().sorted().toList();
    assertThat(dtes).as("AAPL carries several distinct expirations").hasSizeGreaterThanOrEqualTo(4);
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
