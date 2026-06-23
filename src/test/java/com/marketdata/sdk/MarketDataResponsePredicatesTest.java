package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Format predicates and {@code saveToFile} error path on {@link AbstractMarketDataResponse}. */
class MarketDataResponsePredicatesTest {

  private static HttpResponseEnvelope env() {
    return new HttpResponseEnvelope(
        "body".getBytes(),
        200,
        "req-1",
        HttpHeaders.of(Map.of(), (a, b) -> true),
        URI.create("http://localhost/"));
  }

  @Test
  void formatPredicatesReflectTheSentFormat() {
    assertThat(new HtmlResponse("{}", env(), Format.JSON).isJson()).isTrue();
    assertThat(new HtmlResponse("<html>", env(), Format.HTML).isHtml()).isTrue();
    assertThat(new HtmlResponse("<html>", env(), Format.HTML).isJson()).isFalse();
  }

  @Test
  void saveToFileWrapsIoFailureAsUnchecked() {
    HtmlResponse resp = new HtmlResponse("data", env(), Format.HTML);

    // Parent directory does not exist → Files.write throws IOException, surfaced as unchecked.
    assertThatThrownBy(() -> resp.saveToFile(Path.of("/no-such-dir-xyz/nested/out.txt")))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to write response body");
  }
}
