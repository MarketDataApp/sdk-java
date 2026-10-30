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

  // ---------- chain: dte parameter forms (v2.0.0 — dte became a string parameter) ----------

  @Test
  void chainDteSingleValueMovesExpirationFurtherOutAsValueGrows() {
    // dte's wire type changed from integer to string; the single-value form must still pick the
    // expiration closest to the given value. Compared against another value of the same
    // parameter (not the unfiltered default) so an ignored filter would fail this assertion.
    int nearDte = java.util.Objects.requireNonNull(chainRowForDte(ExpirationFilter.dte(7)).dte());
    int farDte = java.util.Objects.requireNonNull(chainRowForDte(ExpirationFilter.dte(180)).dte());

    assertThat(farDte)
        .as("dte(180) must resolve to a later expiration than dte(7)")
        .isGreaterThan(nearDte);
  }

  @Test
  void chainDteListReturnsClosestExpirationPerValueDeduplicated() {
    LocalDate nearExpiration =
        java.util.Objects.requireNonNull(chainRowForDte(ExpirationFilter.dte(7)).expiration())
            .toLocalDate();
    LocalDate farExpiration =
        java.util.Objects.requireNonNull(chainRowForDte(ExpirationFilter.dte(180)).expiration())
            .toLocalDate();

    List<OptionQuote> combined =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(7, 180))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    java.util.Set<LocalDate> distinctExpirations =
        combined.stream()
            .map(q -> java.util.Objects.requireNonNull(q.expiration()).toLocalDate())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(distinctExpirations)
        .as("dte(7, 180) must select exactly the two closest expirations, one per value")
        .containsExactlyInAnyOrder(nearExpiration, farExpiration);
  }

  @Test
  void chainDteRangeDegenerateToSingleDayIncludesBothInclusiveBounds() {
    // The anchor comes from a dte(...) call itself — not the unfiltered default — and is seeded
    // far enough out (180 days) that an answer which merely echoed the unfiltered front-month
    // default would fail the dte-equality assertion below, catching an ignored parameter.
    int anchorDte = anchorDte(180);

    List<OptionQuote> rows =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(anchorDte, anchorDte))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(rows)
        .as("a min==max range must still include the expiration at exactly that dte")
        .isNotEmpty();
    assertThat(rows).allSatisfy(q -> assertThat(q.dte()).isEqualTo(anchorDte));
  }

  @Test
  void chainDteRangeSpansMultipleExpirationsUnlikeASingleValue() {
    List<OptionQuote> ranged =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(0, 400))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    long distinctExpirations = ranged.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as("a closed range must span every expiration within bounds, not just the closest one")
        .isGreaterThan(1);
    assertThat(ranged)
        .as("every row's dte must fall within the inclusive [0, 400] bound")
        .allSatisfy(q -> assertThat(q.dte()).isBetween(0, 400));
  }

  @Test
  void chainDteComparisonGteIncludesTheBoundaryValue() {
    // Seeded far out (180 days), like the range test above, so an ignored filter (which would
    // echo the near-term unfiltered default) fails both assertions below.
    int anchorDte = anchorDte(180);

    List<OptionQuote> rows =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, anchorDte))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(rows)
        .as(">=N must keep the expiration at exactly N")
        .anySatisfy(q -> assertThat(q.dte()).isEqualTo(anchorDte));
    assertThat(rows)
        .as("every row returned by >=N must satisfy dte >= N")
        .allSatisfy(q -> assertThat(q.dte()).isGreaterThanOrEqualTo(anchorDte));
    long distinctExpirations = rows.stream().map(OptionQuote::expiration).distinct().count();
    assertThat(distinctExpirations)
        .as(">=N must span every expiration at or beyond N, not just the closest one")
        .isGreaterThan(1);
  }

  @Test
  void chainDteComparisonGteExcludesExpirationsBelowTheBoundary() {
    int anchorDte = anchorDte(7);
    int threshold = anchorDte + 60;

    List<OptionQuote> rows =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, threshold))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(rows)
        .as(">=N must exclude an expiration below N — proves the filter is not ignored")
        .allSatisfy(q -> assertThat(q.dte()).isGreaterThanOrEqualTo(threshold));
  }

  @Test
  void chainDteComparisonLtExcludesTheBoundaryValue() {
    int anchorDte = anchorDte(7);

    List<OptionQuote> rows =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(
                        ExpirationFilter.dteComparison(StrikeFilter.Operator.LT, anchorDte))
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();

    assertThat(rows)
        .as("<N must leave out the expiration at exactly N — proves the filter is not ignored")
        .allSatisfy(q -> assertThat(q.dte()).isLessThan(anchorDte));
  }

  /** The nearest-the-money call under {@code filter}; asserts the chain isn't empty. */
  private OptionQuote chainRowForDte(ExpirationFilter filter) {
    List<OptionQuote> rows =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(filter)
                    .side(OptionSide.CALL)
                    .strikeLimit(1)
                    .strikeRange(StrikeRange.ITM)
                    .build())
            .values();
    assertThat(rows).isNotEmpty();
    return rows.get(0);
  }

  /**
   * The live dte of the expiration closest to {@code seedDays} — built from a {@code dte(...)} call
   * itself (the parameter under test), never from the unfiltered default, per the live-test rule
   * that a baseline must exercise the parameter it's anchoring.
   */
  private int anchorDte(int seedDays) {
    return java.util.Objects.requireNonNull(chainRowForDte(ExpirationFilter.dte(seedDays)).dte());
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
