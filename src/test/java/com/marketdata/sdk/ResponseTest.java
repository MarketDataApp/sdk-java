package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponseTest {

  private static HttpResponseEnvelope env(byte[] body, int status, String url) {
    return new HttpResponseEnvelope(
        body, status, "req-id-123", HttpHeaders.of(Map.of(), (a, b) -> true), URI.create(url));
  }

  // ---------- typed accessors ----------

  @Test
  void exposesDataStatusAndUrl() {
    Response<String> r =
        Response.wrap("payload", env("body".getBytes(), 200, "http://x/y"), Format.JSON);

    assertThat(r.data()).isEqualTo("payload");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.requestUrl()).isEqualTo(URI.create("http://x/y"));
    assertThat(r.requestId()).isEqualTo("req-id-123");
  }

  @Test
  void requestIdNullWhenServerOmitsIt() {
    HttpResponseEnvelope e =
        new HttpResponseEnvelope(
            "x".getBytes(),
            200,
            null,
            HttpHeaders.of(Map.of(), (a, b) -> true),
            URI.create("http://x"));
    Response<String> r = Response.wrap("data", e, Format.JSON);

    assertThat(r.requestId()).isNull();
  }

  // ---------- format detection ----------

  @Test
  void formatDetectionExposesBooleansOnly() {
    // The Format enum itself is package-private; consumers only see the booleans.
    Response<String> json = Response.wrap("a", env("a".getBytes(), 200, "http://x"), Format.JSON);
    Response<String> csv = Response.wrap("a", env("a".getBytes(), 200, "http://x"), Format.CSV);
    Response<String> html = Response.wrap("a", env("a".getBytes(), 200, "http://x"), Format.HTML);

    assertThat(json.isJson()).isTrue();
    assertThat(json.isCsv()).isFalse();
    assertThat(json.isHtml()).isFalse();

    assertThat(csv.isCsv()).isTrue();
    assertThat(csv.isJson()).isFalse();
    assertThat(csv.isHtml()).isFalse();

    // §13.5: HTML detection — typically a misrouted request that landed on the web-server tier.
    assertThat(html.isHtml()).isTrue();
    assertThat(html.isJson()).isFalse();
    assertThat(html.isCsv()).isFalse();
  }

  // ---------- no-data ----------

  @Test
  void isNoDataReflects404Convention() {
    Response<String> ok = Response.wrap("d", env("d".getBytes(), 200, "http://x"), Format.JSON);
    Response<String> noData = Response.wrap("d", env("d".getBytes(), 404, "http://x"), Format.JSON);

    assertThat(ok.isNoData()).isFalse();
    assertThat(noData.isNoData()).isTrue();
  }

  // ---------- raw body immutability ----------

  @Test
  void rawBodyReturnsDefensiveCopy() {
    byte[] source = "hello".getBytes();
    Response<String> r = Response.wrap("ignored", env(source, 200, "http://x"), Format.JSON);

    byte[] firstCopy = r.rawBody();
    firstCopy[0] = 'X'; // mutate the returned array
    byte[] secondCopy = r.rawBody();

    assertThat(secondCopy[0])
        .as("internal state must not be affected by mutation")
        .isEqualTo((byte) 'h');
  }

  @Test
  void constructorCopiesIncomingRawBody() {
    // Symmetric: the constructor must clone the input so mutations to the source after
    // construction don't bleed into the Response.
    byte[] source = "hello".getBytes();
    Response<String> r = Response.wrap("ignored", env(source, 200, "http://x"), Format.JSON);
    source[0] = 'X';

    assertThat(r.rawBody()[0]).isEqualTo((byte) 'h');
  }

  // ---------- saveToFile ----------

  @Test
  void saveToFileWritesRawBodyVerbatim(@TempDir Path tmp) throws IOException {
    byte[] body = "alpha,beta\n1,2\n".getBytes();
    Response<String> r = Response.wrap("ignored", env(body, 200, "http://x"), Format.CSV);

    Path target = tmp.resolve("out.csv");
    r.saveToFile(target);

    assertThat(Files.readAllBytes(target)).isEqualTo(body);
  }

  @Test
  void saveToFileWrapsIoFailuresInUncheckedIoException(@TempDir Path tmp) {
    Response<String> r = Response.wrap("d", env("d".getBytes(), 200, "http://x"), Format.JSON);

    // A non-existent parent directory triggers NoSuchFileException — the wrapper turns it into
    // UncheckedIOException so the call fits in a fluent chain without checked-exception noise.
    Path inaccessible = tmp.resolve("does-not-exist").resolve("out.txt");

    assertThatThrownBy(() -> r.saveToFile(inaccessible))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining(inaccessible.toString());
  }

  // ---------- toString ----------

  @Test
  void toStringIncludesStatusFormatBytesAndUrl() {
    Response<String> r =
        Response.wrap("payload", env("body".getBytes(), 200, "http://x/y"), Format.JSON);

    String repr = r.toString();

    // safeUri emits the path only (its contract): host/scheme are uninteresting in a log line and
    // omitting them mirrors what HttpDispatcher already does for ambient request logs.
    assertThat(repr)
        .contains("status=200")
        .contains("format=json")
        .contains("bytes=4")
        .contains("url=/y");
  }

  /**
   * Issue #38: {@code toString} is a routine logging surface. The typed payload may carry sensitive
   * content (e.g. a {@code RequestHeaders} map with {@code authorization} or client IPs), so it
   * must not be embedded. Consumers that want the payload have {@link Response#data()}.
   */
  @Test
  void toStringDoesNotIncludeDataPayload() {
    Response<String> r =
        Response.wrap(
            "sensitive-payload-do-not-leak",
            env("body".getBytes(), 200, "http://x/y"),
            Format.JSON);

    assertThat(r.toString()).doesNotContain("sensitive-payload-do-not-leak");
  }

  /**
   * Issue #38 + §16: query strings (tokens, account_ids, symbols) must not survive through {@code
   * toString}. The full URI is still available via {@link Response#requestUrl()}.
   */
  @Test
  void toStringRedactsQueryStringInUrl() {
    Response<String> r =
        Response.wrap(
            "data",
            env("body".getBytes(), 200, "http://x/quotes/?token=secret&symbol=AAPL"),
            Format.JSON);

    String repr = r.toString();

    assertThat(repr).doesNotContain("secret").doesNotContain("AAPL");
    assertThat(repr).contains("?…");
  }
}
