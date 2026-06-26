package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Error/edge branches of the package-private date-field parsers, exercised directly. */
class MarketDataDatesTest {

  private static final JsonNodeFactory N = JsonNodeFactory.instance;

  @Test
  void parseDateFieldRejectsMissingNode() {
    assertThatThrownBy(() -> MarketDataDates.parseDateField(null, NullNode.getInstance(), "d"))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("missing field");
  }

  @Test
  void parseDateOrTimestampRejectsMissingNode() {
    assertThatThrownBy(
            () -> MarketDataDates.parseDateOrTimestampField(null, NullNode.getInstance(), "t"))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("missing field");
  }

  @Test
  void parseDateOrTimestampParsesFullTimestampString() throws Exception {
    ZonedDateTime z =
        MarketDataDates.parseDateOrTimestampField(
            null, N.textNode("2026-06-03 10:00:00 -04:00"), "t");
    assertThat(z).isNotNull();
    assertThat(z.getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void parseDateOrTimestampLiftsDateOnlyToMarketMidnight() throws Exception {
    ZonedDateTime z =
        MarketDataDates.parseDateOrTimestampField(null, N.textNode("2026-06-03"), "t");
    assertThat(z.getHour()).isZero();
    assertThat(z.getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void parseDateOrTimestampRejectsUnparseableString() {
    assertThatThrownBy(
            () -> MarketDataDates.parseDateOrTimestampField(null, N.textNode("nope"), "t"))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("non-conforming");
  }

  @Test
  void parseTimestampRejectsNonConformingString() {
    assertThatThrownBy(() -> MarketDataDates.parseTimestampField(null, N.textNode("nope"), "t"))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("non-conforming timestamp");
  }

  @Test
  void parseTimestampRejectsNonStringNonNumber() {
    assertThatThrownBy(() -> MarketDataDates.parseTimestampField(null, BooleanNode.TRUE, "t"))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("non-string, non-numeric");
  }
}
