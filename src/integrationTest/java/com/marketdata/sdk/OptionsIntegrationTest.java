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
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

  /**
   * A fixed historical trading day for every dte* test below. All {@code dte} forms count days from
   * the chain request's {@code date} when one is set, so pinning it keeps the results stable
   * regardless of when the test runs — unlike omitting {@code date}, which computes dte from
   * "today" and can change at the Eastern-midnight rollover between two requests in the same test.
   */
  private static final LocalDate FIXED_CHAIN_DATE = LocalDate.of(2025, 6, 2);

  @Test
  void chainDteSingleValueNarrowsToOneExpiration() {
    // A single dte value selects the one expiration closest to it. 365 days is far past the
    // front-month expiration the unfiltered chain returns, so comparing against the unfiltered
    // baseline settles whether the dte syntax actually reached the server: an ignored dte would
    // fall back to that same front-month expiration instead of a distinct, far-dated one.
    Set<ZonedDateTime> baseline = unfilteredChainExpirations();
    Set<ZonedDateTime> farDte = singleDteExpirations(365);

    assertThat(farDte)
        .as("a single dte value narrows the chain to its one closest expiration")
        .hasSize(1);
    assertThat(farDte)
        .as("dte(365) must resolve to an expiration the unfiltered (front-month) chain does not")
        .isNotEqualTo(baseline);
  }

  @Test
  void chainDteCommaListMatchesUnionOfSingleDteRequests() {
    // A comma-separated dte list selects the closest expiration for each value, deduplicated.
    // Near/mid/far values that must resolve to more than one expiration close the loophole an
    // ignored dte parameter would otherwise slip through: an ignored list falls back to the single
    // front-month expiration, failing both the "> 1" and the union-equality assertions below.
    Set<ZonedDateTime> union = new HashSet<>();
    union.addAll(singleDteExpirations(15));
    union.addAll(singleDteExpirations(180));
    union.addAll(singleDteExpirations(365));

    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteList(15, 180, 365))
                    .side(OptionSide.CALL)
                    .date(FIXED_CHAIN_DATE)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    Set<ZonedDateTime> listExpirations =
        chain.stream().map(OptionQuote::expiration).collect(Collectors.toSet());
    assertThat(listExpirations)
        .as("a list spanning near/mid/far dte values must resolve to more than one expiration")
        .hasSizeGreaterThan(1);
    assertThat(listExpirations)
        .as("dte list expirations equal the union of the single-value requests for 15, 180, 365")
        .isEqualTo(union);
  }

  /**
   * The distinct expirations a single {@code dte(days)} request resolves to, on {@link
   * #FIXED_CHAIN_DATE}.
   */
  private Set<ZonedDateTime> singleDteExpirations(int days) {
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dte(days))
                    .side(OptionSide.CALL)
                    .date(FIXED_CHAIN_DATE)
                    .build())
            .values();
    return chain.stream().map(OptionQuote::expiration).collect(Collectors.toSet());
  }

  /**
   * The distinct expirations the chain resolves to with no expiration filter at all, on {@link
   * #FIXED_CHAIN_DATE} — the front-month default that an ignored dte parameter would fall back to.
   */
  private Set<ZonedDateTime> unfilteredChainExpirations() {
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .side(OptionSide.CALL)
                    .date(FIXED_CHAIN_DATE)
                    .build())
            .values();
    assertThat(chain).isNotEmpty();
    return chain.stream().map(OptionQuote::expiration).collect(Collectors.toSet());
  }

  @Test
  void chainDteRangeReturnsOnlyExpirationsWithinBounds() {
    // The lower bound is set above the front-month window so an ignored dte would return rows
    // outside [90, 365] and fail the per-row assertion below, rather than passing by coincidence.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteRange(90, 365))
                    .side(OptionSide.CALL)
                    .date(FIXED_CHAIN_DATE)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      Integer dte = q.dte();
      assertThat(dte).isNotNull();
      assertThat(dte.intValue()).isBetween(90, 365);
    }
  }

  @Test
  void chainDteComparisonReturnsOnlyExpirationsSatisfyingOperator() {
    // The API documents comparison bounds as inclusive. The threshold is set above the
    // front-month window so an ignored dte would return rows that violate the >= 90 bound below,
    // rather than passing by coincidence the way a loose "<= 45" would against the front month.
    List<OptionQuote> chain =
        client
            .options()
            .chain(
                OptionsChainRequest.builder(UNDERLYING)
                    .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, 90))
                    .side(OptionSide.CALL)
                    .date(FIXED_CHAIN_DATE)
                    .build())
            .values();

    assertThat(chain).isNotEmpty();
    for (OptionQuote q : chain) {
      Integer dte = q.dte();
      assertThat(dte).isNotNull();
      assertThat(dte.intValue()).isGreaterThanOrEqualTo(90);
    }
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
