package com.marketdata.sdk;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

record Configuration(
    @Nullable String apiKey,
    String baseUrl,
    String apiVersion,
    @Nullable String loggingLevel,
    @Nullable String dateFormat) {

  static final String DEFAULT_BASE_URL = "https://api.marketdata.app";
  static final String DEFAULT_API_VERSION = "v1";
  static final Path DEFAULT_DOTENV_PATH = Path.of(".env");

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
  private static final Pattern API_VERSION_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

  static Configuration resolve(
      @Nullable String explicitApiKey,
      @Nullable String explicitBaseUrl,
      @Nullable String explicitApiVersion,
      Function<String, @Nullable String> env,
      Path dotEnvPath) {
    Map<String, String> dotEnv = DotEnvLoader.load(dotEnvPath);
    String apiKey = pickFirst(explicitApiKey, env.apply(EnvVars.TOKEN), dotEnv.get(EnvVars.TOKEN));
    String baseUrl =
        pickFirstOrDefault(
            DEFAULT_BASE_URL,
            explicitBaseUrl,
            env.apply(EnvVars.BASE_URL),
            dotEnv.get(EnvVars.BASE_URL));
    String apiVersion =
        pickFirstOrDefault(
            DEFAULT_API_VERSION,
            explicitApiVersion,
            env.apply(EnvVars.API_VERSION),
            dotEnv.get(EnvVars.API_VERSION));
    String loggingLevel =
        pickFirst(env.apply(EnvVars.LOGGING_LEVEL), dotEnv.get(EnvVars.LOGGING_LEVEL));
    String dateFormat = pickFirst(env.apply(EnvVars.DATE_FORMAT), dotEnv.get(EnvVars.DATE_FORMAT));
    String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
    String normalizedApiVersion = normalizeApiVersion(apiVersion);
    validateBaseUrl(normalizedBaseUrl);
    validateApiVersion(normalizedApiVersion);
    return new Configuration(
        apiKey, normalizedBaseUrl, normalizedApiVersion, loggingLevel, dateFormat);
  }

  /**
   * Strip trailing slashes from {@code baseUrl} so {@code HttpTransport.buildUri} can append {@code
   * "/" + apiVersion + "/" + path} unconditionally. A user-supplied {@code
   * "https://api.marketdata.app/"} would otherwise produce a double-slash like {@code
   * "https://api.marketdata.app//v1/..."} that some routers reject and others silently canonicalize
   * — either way, an annoying source of "looks right but isn't" failures.
   */
  static String normalizeBaseUrl(String raw) {
    String trimmed = raw.trim();
    int end = trimmed.length();
    while (end > 0 && trimmed.charAt(end - 1) == '/') {
      end--;
    }
    return trimmed.substring(0, end);
  }

  /**
   * Strip leading and trailing slashes from {@code apiVersion} so the segment composes cleanly
   * regardless of how the user spelled it ({@code "v1"}, {@code "/v1"}, {@code "v1/"}, {@code
   * "/v1/"}).
   */
  static String normalizeApiVersion(String raw) {
    String trimmed = raw.trim();
    int start = 0;
    int end = trimmed.length();
    while (start < end && trimmed.charAt(start) == '/') {
      start++;
    }
    while (end > start && trimmed.charAt(end - 1) == '/') {
      end--;
    }
    return trimmed.substring(start, end);
  }

  /**
   * Validate that {@code baseUrl} (already normalized — no trailing slashes, no surrounding
   * whitespace) is a usable HTTP origin. The point is to fail at construction with a clear message
   * instead of letting {@link java.net.http.HttpClient} surface a cryptic {@code
   * IllegalArgumentException} the first time a request is sent.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>Non-empty (post-normalize {@code "////"} collapses to empty).
   *   <li>Parseable as a {@link URI}.
   *   <li>Scheme is exactly {@code http} or {@code https} — schemes like {@code file:}, {@code
   *       ftp:}, or {@code javascript:} have no business here.
   *   <li>Host is present (rules out scheme-only inputs like {@code "https://"}).
   *   <li>No query, fragment, or user-info — those belong on a request, not the origin, and their
   *       presence is almost always a copy-paste mistake that would mangle the constructed URL.
   * </ul>
   */
  static void validateBaseUrl(String baseUrl) {
    if (baseUrl.isEmpty()) {
      throw new IllegalArgumentException(
          "baseUrl must not be empty; expected an http or https URL like " + DEFAULT_BASE_URL);
    }
    URI uri;
    try {
      uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "baseUrl '" + baseUrl + "' is not a valid URI: " + e.getMessage(), e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(java.util.Locale.ROOT))) {
      throw new IllegalArgumentException(
          "baseUrl '"
              + baseUrl
              + "' must use scheme http or https (got "
              + (scheme == null ? "<none>" : scheme)
              + ")");
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException(
          "baseUrl '" + baseUrl + "' is missing a host (e.g. api.marketdata.app)");
    }
    if (uri.getRawQuery() != null) {
      throw new IllegalArgumentException(
          "baseUrl '" + baseUrl + "' must not contain a query string");
    }
    if (uri.getRawFragment() != null) {
      throw new IllegalArgumentException("baseUrl '" + baseUrl + "' must not contain a fragment");
    }
    if (uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException(
          "baseUrl '"
              + baseUrl
              + "' must not contain user-info — credentials belong on the"
              + " request, not the origin");
    }
  }

  /**
   * Validate {@code apiVersion} (already normalized — no leading/trailing slashes, no surrounding
   * whitespace) as a single, URL-safe path segment. Rejects anything outside {@code [A-Za-z0-9._-]}
   * — that's permissive enough for {@code v1}, {@code v2}, {@code v1.0}, {@code beta-1}, etc.,
   * while ruling out embedded slashes ({@code "v1/extra"}), spaces, already percent-encoded values
   * ({@code "%2Fv1"}), and path-traversal tokens ({@code ".."} fails because {@code .} alone is
   * allowed but the result becomes a literal {@code ".."} segment — that's still legitimate enough
   * to send and the server will reject it; the regex's job is just to keep us from emitting
   * malformed URLs).
   */
  static void validateApiVersion(String apiVersion) {
    if (apiVersion.isEmpty()) {
      throw new IllegalArgumentException(
          "apiVersion must not be empty; expected a path segment like " + DEFAULT_API_VERSION);
    }
    if (!API_VERSION_PATTERN.matcher(apiVersion).matches()) {
      throw new IllegalArgumentException(
          "apiVersion '"
              + apiVersion
              + "' must match [A-Za-z0-9._-]+ (a single URL-safe path segment)");
    }
  }

  private static @Nullable String pickFirst(@Nullable String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

  private static String pickFirstOrDefault(String fallback, @Nullable String... candidates) {
    String picked = pickFirst(candidates);
    return picked != null ? picked : fallback;
  }
}
