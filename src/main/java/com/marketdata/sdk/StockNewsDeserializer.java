package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.stocks.StockNews;
import com.marketdata.sdk.stocks.StockNewsArticle;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Wire-format deserializer for {@link StockNews}. The article columns are parallel arrays; {@code
 * updated} is a <em>scalar</em> at the response root (omitted for date-bounded queries), which is
 * the mixed shape {@link ParallelArrays#listDeserializer} can't express. Envelope handling mirrors
 * {@link ParallelArrays}:
 *
 * <ul>
 *   <li>{@code "s":"error"} → {@link JsonMappingException} carrying {@code errmsg}.
 *   <li>{@code "s":"no_data"} → empty list, {@code updated} null.
 *   <li>otherwise → strict field validation across the five article columns; {@code updated} is
 *       read only when present.
 * </ul>
 */
final class StockNewsDeserializer extends JsonDeserializer<StockNews> {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_ERROR = "error";
  private static final String ENVELOPE_NO_DATA = "no_data";
  private static final String UPDATED_KEY = "updated";

  private static final List<String> ARTICLE_FIELDS =
      List.of("symbol", "headline", "content", "source", "publicationDate");

  @Override
  public StockNews deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    String envelopeStatus = root.path(ENVELOPE_STATUS).asText("");
    if (ENVELOPE_ERROR.equals(envelopeStatus)) {
      throw new JsonMappingException(
          p,
          "API responded with error: "
              + LogSafe.sanitize(root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)")));
    }
    if (ENVELOPE_NO_DATA.equals(envelopeStatus)) {
      return new StockNews(List.of(), null);
    }

    List<StockNewsArticle> articles =
        ParallelArrays.zip(
            p,
            root,
            ARTICLE_FIELDS,
            row ->
                new StockNewsArticle(
                    row.text("symbol"),
                    row.text("headline"),
                    row.text("content"),
                    row.text("source"),
                    MarketDataDates.parseDateOrTimestampField(
                        p, row.node("publicationDate"), "publicationDate")));

    // `updated` is the feed's latest-update scalar; the backend omits it for historical queries.
    JsonNode updatedNode = root.get(UPDATED_KEY);
    ZonedDateTime updated =
        updatedNode == null || updatedNode.isNull()
            ? null
            : MarketDataDates.parseTimestampField(p, updatedNode, UPDATED_KEY);
    return new StockNews(articles, updated);
  }
}
