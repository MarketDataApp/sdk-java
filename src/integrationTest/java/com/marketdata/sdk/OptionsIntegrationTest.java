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
  void chainDteSingleValueSelectsExactExpirationWhenAvailable() {
    // Pick the furthest real expiration (far from the front-month the unfiltered call would
    // return) and request it by its own dte: the "closest expiration" to a value that is itself
    // a real expiration is that expiration exactly, with zero distance.
    List<Integer> liveDtes = liveDteValues();
    assertThat(liveDtes)
        .as("AAPL has at least two live expirations")
        .hasSizeGreaterThanOrEqualTo(2);
    int farthest = liveDtes.get(liveDtes.size() - 1);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(farthest))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    assertThat(chain.stream().map(OptionQuote::dte).distinct().toList())
        .as(
            "dte=%d selects exactly the expiration %d days out, not the front month",
            farthest, farthest)
        .containsExactly(farthest);
  }

  @Test
  void chainDteListSelectsClosestExpirationForEachValue() {
    // Three real, widely-spaced expirations: requesting them by dte should return all three
    // distinct expirations, which the unfiltered (front-month-only) answer never does.
    List<Integer> liveDtes = liveDteValues();
    assertThat(liveDtes)
        .as("AAPL has at least three live expirations")
        .hasSizeGreaterThanOrEqualTo(3);
    int low = liveDtes.get(0);
    int mid = liveDtes.get(liveDtes.size() / 2);
    int high = liveDtes.get(liveDtes.size() - 1);

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteList(List.of(low, mid, high)))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    assertThat(chain.stream().map(OptionQuote::dte).distinct().toList())
        .as(
            "dte=%d,%d,%d selects all three expirations, unlike the single front-month default",
            low, mid, high)
        .containsExactlyInAnyOrder(low, mid, high);
  }

  @Test
  void chainDteRangeIncludesEveryExpirationWithinBoundsInclusive() {
    // A closed range starting after the front month: every expiration from the second-smallest
    // live dte through the largest must come back, and the front month (the unfiltered answer)
    // must be excluded — proving both the inclusive bounds and that the filter actually narrowed
    // the result away from the default.
    List<Integer> liveDtes = liveDteValues();
    assertThat(liveDtes)
        .as("AAPL has at least three live expirations")
        .hasSizeGreaterThanOrEqualTo(3);
    int frontMonth = liveDtes.get(0);
    int low = liveDtes.get(1);
    int high = liveDtes.get(liveDtes.size() - 1);
    List<Integer> expectedInRange = liveDtes.stream().filter(d -> d >= low && d <= high).toList();

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(low, high))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();

    List<Integer> distinctDtes = chain.stream().map(OptionQuote::dte).distinct().sorted().toList();
    assertThat(distinctDtes)
        .as(
            "dte=%d-%d keeps every expiration in [%d, %d] inclusive, including both bounds",
            low, high, low, high)
        .containsExactlyInAnyOrderElementsOf(expectedInRange)
        .contains(low, high);
    assertThat(distinctDtes)
        .as("the range excludes the front month %d", frontMonth)
        .doesNotContain(frontMonth);
  }

  @Test
  void chainDteComparisonGteIncludesBoundaryLtExcludesIt() {
    // Anchor on the furthest real expiration M: ">=M" must keep exactly M (nothing exceeds the
    // maximum, so the bound is kept), while "<M" must return more than one expiration and never
    // include M itself — demonstrating the inclusive/exclusive boundary on real data rather than
    // on the unfiltered (single front-month) answer, which satisfies neither assertion.
    List<Integer> liveDtes = liveDteValues();
    assertThat(liveDtes)
        .as("AAPL has at least three live expirations")
        .hasSizeGreaterThanOrEqualTo(3);
    int anchor = liveDtes.get(liveDtes.size() - 1);

    List<OptionQuote> gteChain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, anchor))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    assertThat(gteChain.stream().map(OptionQuote::dte).distinct().toList())
        .as("dte>=%d keeps exactly the boundary expiration %d", anchor, anchor)
        .containsExactly(anchor);

    List<OptionQuote> ltChain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.LT, anchor))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .build())
            .values();
    List<Integer> ltDtes = ltChain.stream().map(OptionQuote::dte).distinct().toList();
    assertThat(ltDtes)
        .as(
            "dte<%d excludes the boundary expiration %d and returns more than the front month",
            anchor, anchor)
        .doesNotContain(anchor)
        .hasSizeGreaterThan(1);
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
   * Every distinct dte (days-to-expiration) across AAPL's full expiration calendar today, sorted
   * ascending — used as real anchors for the dte filter tests above instead of values guessed from
   * today's date, which expirations drift around daily.
   */
  private List<Integer> liveDteValues() {
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
    return chain.stream()
        .map(OptionQuote::dte)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
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
