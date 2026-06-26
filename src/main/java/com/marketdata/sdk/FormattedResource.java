package com.marketdata.sdk;

/**
 * Package-private self-typed base for the CSV format facets: a {@link ConfiguredResource} that
 * additionally exposes the output-shaping {@code human}/{@code headers} params. Those two only
 * cohere with CSV output (they reshape the rendered text), so the HTML facets — which expose no
 * params at all — do <em>not</em> extend this.
 */
abstract class FormattedResource<SELF extends FormattedResource<SELF>>
    extends ConfiguredResource<SELF> {

  FormattedResource(HttpTransport transport, RequestConfig config) {
    super(transport, config);
  }

  /** Returns a copy that renders human-friendly values instead of raw codes (CSV only). */
  public SELF human(boolean human) {
    return withConfig(config.withHuman(human));
  }

  /** Returns a copy that toggles the CSV header row (CSV only). */
  public SELF headers(boolean headers) {
    return withConfig(config.withHeaders(headers));
  }
}
