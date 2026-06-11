package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MarketsResourceTest {

  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
  private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (a, b) -> true);

  private static MarketsResource resourceWith(HttpClient client) {
    HttpTransport transport =
        new HttpTransport(
            "http://localhost",
            "v1",
            "test/0.0",
            "secret-token",
            new HttpDispatcher(client, HttpTransport.CONCURRENCY_LIMIT),
            new RetryExecutor(NO_RETRY),
            () -> null,
            Clock.systemUTC());
    return new MarketsResource(transport, new JsonResponseParser());
  }

  // ---------- canned bodies ----------

  // Fri (open), Sat (closed), Sun (closed) — 1705039200 = 2024-01-12 (mock uses midnight ET).
  private static final String STATUS_BODY =
      "{\"s\":\"ok\","
          + "\"date\":[1705035600,1705122000,1705208400],"
          + "\"status\":[\"open\",\"closed\",\"closed\"]}";

  // ---------- status ----------

  @Test
  void statusHitsVersionedEndpointWithNoRequiredParams() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);

    markets.statusAsync(MarketStatusRequest.of()).join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/markets/status/");
    assertThat(client.captured.get(0).method()).isEqualTo("GET");
  }

  @Test
  void statusAttachesAllParams() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);

    markets
        .statusAsync(
            MarketStatusRequest.builder()
                .country("US")
                .from(LocalDate.of(2025, Month.JANUARY, 1))
                .to(LocalDate.of(2025, Month.JANUARY, 31))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("/v1/markets/status/")
        .contains("country=US")
        .contains("from=2025-01-01")
        .contains("to=2025-01-31");
  }

  @Test
  void statusAttachesDateAndCountbackWindows() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);

    markets.status(
        MarketStatusRequest.builder().date(LocalDate.of(2025, Month.JANUARY, 17)).build());
    markets.status(
        MarketStatusRequest.builder()
            .to(LocalDate.of(2025, Month.JANUARY, 31))
            .countback(30)
            .build());

    assertThat(client.captured.get(0).uri().toString()).contains("date=2025-01-17");
    assertThat(client.captured.get(1).uri().toString())
        .contains("to=2025-01-31")
        .contains("countback=30");
  }

  @Test
  void statusDecodesRowsWithOpenClosedPredicates() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);

    List<MarketStatus> days = markets.status(MarketStatusRequest.of()).values();

    assertThat(days).hasSize(3);
    MarketStatus first = days.get(0);
    assertThat(first.status()).isEqualTo("open");
    assertThat(first.isOpen()).isTrue();
    assertThat(first.isClosed()).isFalse();
    assertThat(first.date()).isNotNull();
    assertThat(first.date().getZone().getId()).isEqualTo("America/New_York");
    assertThat(days.get(1).isClosed()).isTrue();
    assertThat(days.get(1).isOpen()).isFalse();
  }

  @Test
  void statusAcceptsDateOnlyTimestampStrings() {
    // Under dateformat=timestamp the `date` column comes back date-only ("2025-01-17"); the
    // tolerant parser lifts it to a market-zone midnight rather than failing on the missing time.
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"date\":[\"2025-01-17\"],\"status\":[\"open\"]}");
    MarketsResource markets = resourceWith(client);

    MarketStatus day =
        markets.dateFormat(DateFormat.TIMESTAMP).status(MarketStatusRequest.of()).values().get(0);

    assertThat(day.date().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 17));
    assertThat(day.date().getHour()).isZero();
  }

  @Test
  void statusNullCellsOutsideCalendarCoverageDecodeToNull() {
    // The backend emits a null status CELL for days outside its holiday-calendar coverage. The
    // column is present, so Option A is satisfied — the cell decodes to null, not a ParseError.
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"date\":[1705035600,1705122000],\"status\":[\"open\",null]}");
    MarketsResource markets = resourceWith(client);

    List<MarketStatus> days = markets.status(MarketStatusRequest.of()).values();

    assertThat(days.get(0).isOpen()).isTrue();
    assertThat(days.get(1).status()).isNull();
    assertThat(days.get(1).isOpen()).isFalse();
    assertThat(days.get(1).isClosed()).isFalse();
  }

  @Test
  void statusNoDataEnvelopeYieldsEmptyList() {
    CapturingClient client = okWith("{\"s\":\"no_data\"}");
    MarketsResource markets = resourceWith(client);

    assertThat(markets.status(MarketStatusRequest.builder().country("XX").build()).values())
        .isEmpty();
  }

  @Test
  void statusErrorEnvelopeSurfacesAsParseError() {
    CapturingClient client = okWith("{\"s\":\"error\",\"errmsg\":\"Invalid date\"}");
    MarketsResource markets = resourceWith(client);

    assertThatThrownBy(() -> markets.status(MarketStatusRequest.of()))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Invalid date");
  }

  @Test
  void statusSyncMirrorsAsync() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);
    assertThat(markets.status(MarketStatusRequest.of()).values()).hasSize(3);
  }

  // ---------- columns projection + Option A ----------

  @Test
  void columnsProjectionDecodesRequestedAndNullsTheRest() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"status\":[\"open\"]}");
    MarketsResource markets = resourceWith(client);

    MarketStatus day = markets.columns("status").status(MarketStatusRequest.of()).values().get(0);

    assertThat(day.status()).isEqualTo("open");
    assertThat(day.date()).isNull();
    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url).contains("columns=status");
  }

  @Test
  void columnsRequestedButOmittedByApiThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"status\":[\"open\"]}");
    MarketsResource markets = resourceWith(client);

    assertThatThrownBy(() -> markets.columns("date", "status").status(MarketStatusRequest.of()))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("date");
  }

  @Test
  void noColumnsFilterStillRequiresAllStructuralColumns() {
    // Body is missing `status` with no projection requested — must throw, never silently null.
    CapturingClient client = okWith("{\"s\":\"ok\",\"date\":[1705035600]}");
    MarketsResource markets = resourceWith(client);

    assertThatThrownBy(() -> markets.status(MarketStatusRequest.of()))
        .isInstanceOf(ParseError.class);
  }

  @Test
  void universalParamsReachTheWire() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);

    markets
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .status(MarketStatusRequest.of());

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("limit=50")
        .contains("offset=10");
  }

  // ---------- CSV / HTML facets ----------

  @Test
  void asCsvSendsFormatCsvAndReturnsRawText() {
    CapturingClient client = okWith("date,status\n1705035600,open\n1705122000,closed");
    MarketsResource markets = resourceWith(client);

    CsvResponse csv = markets.asCsv().status(MarketStatusRequest.of());

    assertThat(csv.csv()).contains("date,status");
    assertThat(csv.values()).isEqualTo(csv.csv());
    assertThat(csv.isCsv()).isTrue();
    assertThat(client.captured.get(0).uri().toString()).contains("format=csv");
  }

  @Test
  void csvFacetUniversalAndShapingParamsReachTheWire() {
    CapturingClient client = okWith("a,b\n1,2");
    MarketsResource markets = resourceWith(client);

    markets
        .asCsv()
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .columns("date", "status")
        .human(true)
        .headers(true)
        .status(MarketStatusRequest.of());

    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url)
        .contains("format=csv")
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("limit=50")
        .contains("offset=10")
        .contains("columns=date,status")
        .contains("human=true")
        .contains("headers=true");
  }

  @Test
  void htmlFacetSendsFormatHtml() {
    CapturingClient client = okWith("<html>x</html>");
    MarketsHtmlResource html = resourceWith(client).asHtml();

    assertThat(html.status(MarketStatusRequest.of()).html()).contains("<html>");
    assertThat(client.captured.get(0).uri().toString()).contains("format=html");
  }

  // ---------- §8.2 per-response rate-limit snapshot ----------

  @Test
  void responseExposesPerResponseRateLimitSnapshot() {
    HttpHeaders rl =
        TestHttpClients.headersOf(
            Map.of(
                "x-api-ratelimit-limit", "100",
                "x-api-ratelimit-remaining", "95",
                "x-api-ratelimit-reset", "1705500000",
                "x-api-ratelimit-consumed", "5"));
    CapturingClient client =
        new CapturingClient(200, STATUS_BODY.getBytes(StandardCharsets.UTF_8), rl);
    MarketsResource markets = resourceWith(client);

    RateLimitSnapshot snap = markets.status(MarketStatusRequest.of()).rateLimit();
    assertThat(snap).isNotNull();
    assertThat(snap.limit()).isEqualTo(100);
    assertThat(snap.remaining()).isEqualTo(95);
    assertThat(snap.consumed()).isEqualTo(5);
    assertThat(snap.reset()).isNotNull();
  }

  @Test
  void responseRateLimitIsNullWhenHeadersAbsent() {
    CapturingClient client = okWith(STATUS_BODY);
    MarketsResource markets = resourceWith(client);
    assertThat(markets.status(MarketStatusRequest.of()).rateLimit()).isNull();
  }

  // ---------- request validation ----------

  @Test
  void statusRequestRejectsDateWithRange() {
    assertThatThrownBy(
            () ->
                MarketStatusRequest.builder()
                    .date(LocalDate.of(2025, Month.JANUARY, 1))
                    .from(LocalDate.of(2024, Month.DECEMBER, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void statusRequestRejectsCountbackWithFrom() {
    assertThatThrownBy(
            () ->
                MarketStatusRequest.builder()
                    .from(LocalDate.of(2024, Month.JANUARY, 1))
                    .countback(3)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("countback and from");
  }

  @Test
  void statusRequestRejectsNonPositiveCountback() {
    assertThatThrownBy(() -> MarketStatusRequest.builder().countback(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  // ---------- helpers ----------

  private static CapturingClient okWith(String body) {
    return new CapturingClient(200, body.getBytes(StandardCharsets.UTF_8), EMPTY_HEADERS);
  }

  private static final class CapturingClient extends TestHttpClients.StubHttpClient {
    final List<HttpRequest> captured = new ArrayList<>();
    final int status;
    final byte[] body;
    final HttpHeaders headers;

    CapturingClient(int status, byte[] body, HttpHeaders headers) {
      this.status = status;
      this.body = body;
      this.headers = headers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      captured.add(request);
      HttpResponse<byte[]> resp =
          TestHttpClients.response(status, body, headers, URI.create("http://localhost"));
      return (CompletableFuture) CompletableFuture.completedFuture(resp);
    }
  }
}
