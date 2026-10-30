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
  void chainDteSingleValueStillSelectsClosestExpiration() {
    // API 2.0.0 changed dte's query parameter from integer to string; this regression test proves
    // the already-existing single-value form behaves the same against the live API. Picking the
    // front-of-board value would pass even if the server ignored dte entirely — an unfiltered
    // chain also narrows to the front month (docs/options/chain.md) — so pick a value away from
    // the front instead.
    List<Integer> board = discoverDteBoard();
    assertThat(board).as("AAPL has at least 2 distinct expirations").hasSizeGreaterThanOrEqualTo(2);
    int n = board.get(board.size() - 1);

    List<OptionQuote> single =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(n))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(single).isNotEmpty();
    assertThat(single.get(0).dte()).isEqualTo(n);
  }

  @Test
  void chainDteListReturnsClosestExpirationPerValue() {
    // "A comma-separated list (15,30,45) selects the closest expiration for each value" — querying
    // three real dte values (each an exact expiration on the live board) must return all three,
    // since the closest expiration to an exact match is itself.
    List<Integer> board = discoverDteBoard();
    assertThat(board).as("AAPL has at least 3 distinct expirations").hasSizeGreaterThanOrEqualTo(3);
    int a = board.get(0);
    int b = board.get(board.size() / 2);
    int c = board.get(board.size() - 1);

    List<OptionQuote> listResult =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteList(a, b, c))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    List<Integer> returnedDtes = listResult.stream().map(OptionQuote::dte).toList();
    assertThat(returnedDtes).contains(a, b, c);
  }

  @Test
  void chainDteRangeIncludesInclusiveBoundaries() {
    // "A closed range (0-45) ... returns every expiration whose dte falls within the bounds,
    // inclusive." Use the live board's own min/max dte as the range bounds so both boundary values
    // are guaranteed to exist as real expirations.
    List<Integer> board = discoverDteBoard();
    int min = board.get(0);
    int max = board.get(board.size() - 1);

    List<OptionQuote> inRange =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(min, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    List<Integer> returnedDtes = inRange.stream().map(OptionQuote::dte).toList();
    assertThat(returnedDtes).as("the lower bound is inclusive").contains(min);
    assertThat(returnedDtes).as("the upper bound is inclusive").contains(max);
  }

  @Test
  void chainDteRangeExcludesDteBelowRaisedLowerBound() {
    // Narrowing the lower bound past the board's minimum dte must drop the expiration that used to
    // sit exactly on it — proving the bound is honored, not ignored.
    List<Integer> board = discoverDteBoard();
    int min = board.get(0);
    int max = board.get(board.size() - 1);

    List<OptionQuote> narrowed =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(min + 1, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    List<Integer> returnedDtes = narrowed.stream().map(OptionQuote::dte).toList();
    assertThat(returnedDtes)
        .as("raising the lower bound past the board minimum excludes that expiration")
        .doesNotContain(min);
  }

  @Test
  void chainDteComparisonGteIncludesExactBoundaryDte() {
    // The comparison form reuses StrikeFilter.Operator ("the syntax matches strike"): >= keeps the
    // row whose dte equals the bound. Using the board's front-month dte as the bound would pass
    // even if the server ignored dte — the unfiltered front-month response also carries exactly
    // that dte — so use a bound above the front of the board, and require every returned row to
    // respect it (a front-month-only answer would carry a dte below the bound and fail the second
    // assertion).
    List<Integer> board = discoverDteBoard();
    assertThat(board).as("AAPL has at least 2 distinct expirations").hasSizeGreaterThanOrEqualTo(2);
    int bound = board.get(board.size() / 2);

    List<OptionQuote> gte =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, bound))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    List<Integer> dtes = gte.stream().map(OptionQuote::dte).toList();
    assertThat(dtes).as(">= must keep the row whose dte equals the bound").contains(bound);
    assertThat(dtes).as("every returned dte must be >= the bound").allMatch(d -> d >= bound);
  }

  @Test
  void chainDteComparisonGtExcludesExactBoundaryDte() {
    // > is strict, matching StrikeFilter's own ">" semantics ("the syntax matches strike"): it
    // must drop the row whose dte exactly equals the bound, unlike >=.
    int n = discoverDteBoard().get(0);

    List<OptionQuote> gt =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator.GT, n))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(gt.stream().map(OptionQuote::dte).toList())
        .as("> must drop the row whose dte equals the bound")
        .doesNotContain(n);
  }

  @Test
  void chainDteComparisonLteIncludesExactBoundaryDte() {
    List<Integer> board = discoverDteBoard();
    int max = board.get(board.size() - 1);

    List<OptionQuote> lte =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.LTE, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(lte.stream().map(OptionQuote::dte).toList())
        .as("<= must keep the row whose dte equals the bound")
        .contains(max);
  }

  @Test
  void chainDteComparisonLtExcludesExactBoundaryDte() {
    // < is strict, matching StrikeFilter's own "<" semantics ("the syntax matches strike"): it
    // must drop the row whose dte exactly equals the bound, unlike <=. Asserting only
    // doesNotContain(max) would pass even if the server ignored dte — an unfiltered,
    // front-month-only response also excludes max (since max differs from the front) — so also
    // require every returned dte to sit below the bound and more than one distinct expiration to
    // come back, which a front-month-only answer could not satisfy.
    List<Integer> board = discoverDteBoard();
    assertThat(board).as("AAPL has at least 3 distinct expirations").hasSizeGreaterThanOrEqualTo(3);
    int max = board.get(board.size() - 1);

    List<OptionQuote> lt =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator.LT, max))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    List<Integer> dtes = lt.stream().map(OptionQuote::dte).toList();
    assertThat(dtes).as("< must drop the row whose dte equals the bound").doesNotContain(max);
    assertThat(dtes).as("every returned dte must be < the bound").allMatch(d -> d < max);
    assertThat(dtes.stream().distinct().count())
        .as("the filter must span more than the front month alone")
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

  /**
   * Fetch the live, sorted, distinct set of {@code dte} values across every AAPL call expiration —
   * used by the {@code dte}-filter tests to derive real boundary values instead of guessing at
   * today's market calendar. {@code expiration=all} spans the whole board; {@code strikeLimit(1)}
   * keeps the payload to one row per expiration.
   */
  private List<Integer> discoverDteBoard() {
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.all())
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();
    List<Integer> dtes =
        chain.stream().map(OptionQuote::dte).filter(Objects::nonNull).distinct().sorted().toList();
    assertThat(dtes).as("AAPL has at least one live option expiration").isNotEmpty();
    return dtes;
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
