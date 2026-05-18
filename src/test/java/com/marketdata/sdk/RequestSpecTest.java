package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestSpecTest {

  @Test
  void buildPreservesPathAndOmitsNullQueryParams() {
    // Covers both branches of `if (value != null)` in Builder.query.
    RequestSpec spec =
        RequestSpec.get("markets/status")
            .query("date", "2024-05-01")
            .query("ignored", null)
            .query("from", "2024-01-01")
            .build();

    assertThat(spec.path()).isEqualTo("markets/status");
    assertThat(spec.queryParams())
        .containsExactly(
            java.util.Map.entry("date", "2024-05-01"), java.util.Map.entry("from", "2024-01-01"));
    assertThat(spec.queryParams()).doesNotContainKey("ignored");
  }

  @Test
  void buildWithNoQueryParamsProducesEmptyMap() {
    RequestSpec spec = RequestSpec.get("markets/status").build();

    assertThat(spec.path()).isEqualTo("markets/status");
    assertThat(spec.queryParams()).isEmpty();
  }

  @Test
  void queryParamsAreImmutable() {
    RequestSpec spec = RequestSpec.get("markets/status").query("date", "2024-05-01").build();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> spec.queryParams().put("hacked", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void queryConvertsNonStringValuesViaToString() {
    RequestSpec spec =
        RequestSpec.get("markets/candles")
            .query("countback", 5)
            .query("limit", Long.valueOf(100L))
            .build();

    assertThat(spec.queryParams()).containsEntry("countback", "5").containsEntry("limit", "100");
  }

  // ---------- universal params ----------

  @Test
  void defaultFormatIsJsonAndNotWrittenToQuery() {
    // No-op default: an explicit `?format=json` is redundant and adds query noise.
    RequestSpec spec = RequestSpec.get("markets/status").build();
    assertThat(spec.format()).isEqualTo(Format.JSON);
    assertThat(spec.queryParams()).doesNotContainKey("format");
  }

  @Test
  void formatSetterWritesQueryParamAndUpdatesField() {
    RequestSpec spec = RequestSpec.get("stocks/candles").format(Format.CSV).build();
    assertThat(spec.format()).isEqualTo(Format.CSV);
    assertThat(spec.queryParams()).containsEntry("format", "csv");
  }

  @Test
  void htmlFormatWiresThroughEvenThoughItIsNotUserVisible() {
    // Format.HTML is package-private — no SDK consumer can reference it — but the transport
    // pipeline must accept it end-to-end so the day the server lights up HTML responses, the
    // only change is exposing a `...AsHtml()` method on the relevant resource.
    RequestSpec spec = RequestSpec.get("stocks/candles").format(Format.HTML).build();
    assertThat(spec.format()).isEqualTo(Format.HTML);
    assertThat(spec.queryParams()).containsEntry("format", "html");
    assertThat(Format.HTML.mediaType()).isEqualTo("text/html");
  }

  @Test
  void dateformatWritesQueryParam() {
    RequestSpec spec = RequestSpec.get("stocks/candles").dateformat(DateFormat.SPREADSHEET).build();
    assertThat(spec.queryParams()).containsEntry("dateformat", "spreadsheet");
  }

  @Test
  void modeWritesQueryParam() {
    RequestSpec spec = RequestSpec.get("stocks/quotes").mode(Mode.DELAYED).build();
    assertThat(spec.queryParams()).containsEntry("mode", "delayed");
  }

  @Test
  void headersAndHumanWriteBooleansAsStrings() {
    RequestSpec spec = RequestSpec.get("stocks/candles").headers(false).human(true).build();
    assertThat(spec.queryParams()).containsEntry("headers", "false").containsEntry("human", "true");
  }

  @Test
  void columnsCommaJoinsTheList() {
    RequestSpec spec =
        RequestSpec.get("stocks/quotes").columns(List.of("symbol", "last", "volume")).build();
    assertThat(spec.queryParams()).containsEntry("columns", "symbol,last,volume");
  }

  @Test
  void columnsEmptyListIsNoOp() {
    // Sending `?columns=` (empty value) would risk the server interpreting it as "no columns"
    // rather than "all columns". Easier to omit.
    RequestSpec spec = RequestSpec.get("stocks/quotes").columns(List.of()).build();
    assertThat(spec.queryParams()).doesNotContainKey("columns");
  }

  @Test
  void limitAndOffsetWriteInts() {
    RequestSpec spec = RequestSpec.get("stocks/news").limit(50).offset(100).build();
    assertThat(spec.queryParams()).containsEntry("limit", "50").containsEntry("offset", "100");
  }

  // ---------- versioned / unversioned ----------

  @Test
  void specsAreVersionedByDefault() {
    // Every business endpoint lives under /v1/, so the default has to be the common case.
    RequestSpec spec = RequestSpec.get("markets/status").build();
    assertThat(spec.versioned()).isTrue();
  }

  @Test
  void unversionedFlipsThePrefixOff() {
    // /status/ and /headers/ live at the API root, not under /v1/.
    RequestSpec spec = RequestSpec.get("status").unversioned().build();
    assertThat(spec.versioned()).isFalse();
    assertThat(spec.path()).isEqualTo("status");
  }

  @Test
  void universalParamsAccumulateAlongsideArbitraryQueryParams() {
    // The universal-setter API does not replace `.query(...)` — both coexist, ordered by
    // insertion.
    RequestSpec spec =
        RequestSpec.get("stocks/candles")
            .query("symbol", "AAPL")
            .format(Format.CSV)
            .dateformat(DateFormat.UNIX)
            .build();

    assertThat(spec.queryParams())
        .containsExactly(
            java.util.Map.entry("symbol", "AAPL"),
            java.util.Map.entry("format", "csv"),
            java.util.Map.entry("dateformat", "unix"));
  }
}
