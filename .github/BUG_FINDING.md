# Bug Finding Workflow

This document defines a systematic process for proactively discovering bugs through codebase exploration and testing.

> **IMPORTANT: Every bug found MUST be submitted as a GitHub issue.**
>
> Do NOT just document bugs in markdown files, notes, or comments. Each bug you find must result in an actual GitHub issue created via:
> - **CLI**: `gh issue create --label "bug" --title "[Bug]: ..." --body "..."`
> - **Web**: [Create Bug Report](https://github.com/MarketDataApp/sdk-java/issues/new?template=bug.yml)
>
> A bug hunt is not complete until all discovered bugs exist as GitHub issues.

## Overview

**Purpose**: Proactive bug discovery vs reactive bug processing

- **BUG_FINDING.md** (this document): Find bugs before users encounter them
- **ISSUE_WORKFLOW.md**: Process bug reports submitted by users

**Workflow**: Find Bug → **Create GitHub Issue (REQUIRED)** → [ISSUE_WORKFLOW.md] → Fix

Each bug found MUST result in a GitHub issue. No exceptions.

**When to use this document**:
- QA passes before releases
- Pre-release validation
- Exploratory testing sessions
- After significant refactors
- When onboarding to understand edge cases

---

## Prerequisites

### Environment Setup

```bash
# Required
./gradlew build
java -version  # Must be JDK 17, 21, or 25

# API token for integration tests
export MARKETDATA_TOKEN="your_token_here"
```

### Baseline Verification

Before hunting for bugs, confirm the test suite passes:

```bash
./gradlew test
```

If tests fail, fix those issues first. Bug finding assumes a working baseline.

### Architecture Understanding

Familiarize yourself with key components:
- `MarketDataClient` - Main entry point
- `HttpTransport` - HTTP handling, retry logic, rate-limit tracking, async dispatch
- `Configuration` / `EnvVars` - Configuration and token resolution (cascade)
- Resource façades - `StocksResource`, `OptionsResource`, `MarketsResource`, `FundsResource`, `UtilitiesResource`
- Response records - the named `*Response` types implementing `MarketDataResponse<T>`, payload via `values()`

---

## Exploration Areas

Prioritized by historical bug likelihood:

| Priority | Area | Bug Likelihood | Common Issues |
|----------|------|----------------|---------------|
| 1 | Response Format Handling | High | CSV/HTML decoding, column projection nulls |
| 2 | Collection Boundary Conditions | High | Index access on empty `values()` lists |
| 3 | Concurrent Request Handling | Medium | Partial failures, header merging |
| 4 | Date/Time Parsing | Medium | Timestamp formats, boundaries |
| 5 | Multi-Symbol Operations | Medium | Empty lists, deduplication |

---

## Area 1: Response Format Handling

### What Can Go Wrong

- Fields fail to decode when the response format is CSV or HTML
- Human-readable CSV has different column names than the typed JSON
- Optional fields present in JSON but absent in CSV/HTML
- Column projection (`columns(...)`) silently nulls a field that should raise `ParseError`

### Test Scenarios

#### 1.1 Format Switching

Test each endpoint with all three formats. Typed JSON is the default (`values()`);
CSV is reached via `asCsv()` and HTML via the HTML facet:

```java
try (var client = new MarketDataClient()) {

    // JSON (default, typed) — should work
    var jsonResult = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-03"))
            .build());

    // CSV — check the CsvResponse decodes
    CsvResponse csvResult = client.stocks().asCsv().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-03"))
            .build());

    // HTML — check the HTML facet decodes
    var htmlResult = client.stocks().asHtml().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-03"))
            .build());

    // Verify: Does accessing values()/rawBody() throw for any format?
    // Bug indicator: ParseError, ClassCastException, or NullPointerException
}
```

> Kotlin equivalent: same call shape inside `MarketDataClient().use { client -> ... }`,
> reading `resp.values()` / `csvResult.rawBody()`.

#### 1.2 Human-Readable & Column Projection

The `human`/`headers` shaping params live on the CSV facet; `columns(...)` projects fields:

```java
try (var client = new MarketDataClient()) {

    // Regular CSV
    CsvResponse regular = client.stocks().asCsv().human(false).candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-03"))
            .build());

    // Human-readable CSV
    CsvResponse human = client.stocks().asCsv().human(true).candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-03"))
            .build());

    // Column projection (Option A strict decoding)
    var projected = client.stocks().columns("time", "close").candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-03"))
            .build());

    // Verify: Are all fields correctly mapped in both human modes?
    // Verify: A non-requested column decodes to null (OK); a required column the
    //         API omits must raise ParseError — never silently null.
    // Bug indicator: Missing data in human mode, wrong field mappings,
    //                a required field nulled instead of raising ParseError
}
```

### Red Flags

- `com.marketdata.sdk.exception.ParseError` thrown on a format that should decode
- `ClassCastException` — a value decoded to the wrong type
- `NullPointerException` on a field that should be present
- Missing data only in non-JSON (CSV/HTML) formats
- Different results between `human(true)` and `human(false)`
- A required field nulled by `columns(...)` instead of raising `ParseError`

### Pass/Fail Criteria

| Scenario | Pass | Fail |
|----------|------|------|
| JSON format | Returns typed response (`values()`) | Exception thrown |
| CSV format | Returns `CsvResponse` | `ParseError` / `ClassCastException` |
| HTML format | Returns HTML response | `ParseError` / `ClassCastException` |
| Human-readable | Same data as regular CSV | Data missing or incorrect |
| Column projection | Non-requested field → null; required omission → `ParseError` | Required field silently nulled |

---

## Area 2: Collection Boundary Conditions

### What Can Go Wrong

- Accessing `values().get(0)` without checking the list is non-empty
- Single item returned inconsistently vs a list
- Missing optional fields causing `NullPointerException` on access

> The SDK contract: `values()` returns an **empty `List` (never `null`)** when there is
> no data, and `isNoData()` is `true`. Frame "empty results" checks around that contract.

### Test Scenarios

#### 2.1 Empty Results

```java
try (var client = new MarketDataClient()) {

    // Request data for a date range with no trading (e.g., a weekend)
    var result = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-06"))   // Saturday
            .to(LocalDate.parse("2024-01-07"))     // Sunday
            .build());

    // Verify: Does the SDK handle empty results gracefully?
    // Bug indicator: IndexOutOfBoundsException, or values() returning null

    // Check all access patterns:
    // - Direct indexing:  result.values().get(0)
    // - Empty check:       result.isNoData() / result.values().isEmpty()
    // - Iteration:         for (StockCandle c : result.values()) { ... }
}
```

#### 2.2 Single Item Response

```java
try (var client = new MarketDataClient()) {

    // Request exactly one item
    var result = client.stocks().quote(StockQuotesRequest.of("AAPL"));

    // Verify: Is the response consistently a list off values()?
    // Bug indicator: Single item modeled inconsistently, iteration fails
}
```

#### 2.3 Missing Optional Fields

```java
try (var client = new MarketDataClient()) {

    // Earnings often have missing fields (forward quarters null reportedEPS, etc.)
    var result = client.stocks().earnings(
        StockEarningsRequest.builder("AAPL")
            .from(LocalDate.parse("2024-01-01"))
            .build());

    // Verify: Are @Nullable fields handled without throwing?
    // Bug indicator: NullPointerException when an optional field is absent
}
```

### Red Flags

- `IndexOutOfBoundsException` on `values().get(0)`
- `values()` returning `null` instead of an empty `List`
- `NullPointerException` on an optional/`@Nullable` field
- Different behavior with 0, 1, or 2+ results

### Pass/Fail Criteria

| Scenario | Pass | Fail |
|----------|------|------|
| Empty list | Empty `List`, `isNoData()` true, no error | `IndexOutOfBoundsException` or `null` |
| Single item | Consistent `List` off `values()` | Type changes based on count |
| Missing optional | `@Nullable` field is `null`, no error | `NullPointerException` |

---

## Area 3: Concurrent Request Handling

### What Can Go Wrong

- Partial failures not properly reported across a concurrent fan-out
- Headers / rate-limit info from multiple requests conflicting
- Auto-chunk boundaries causing data loss or duplication
- Rate limiting not properly handled in parallel

> Two concurrency shapes exist: `options.quotes` **fans out one request per contract**
> and returns a `Map<String, OptionsQuotesResponse>` (per-symbol status observable);
> intraday `stocks.candles` spanning more than ~one year **auto-splits** into concurrent
> year-sized sub-requests that are merged into one response.

### Test Scenarios

#### 3.1 Partial Failures

```java
try (var client = new MarketDataClient()) {

    // Fan out across contracts, one valid, one bogus
    Map<String, OptionsQuotesResponse> bySymbol = client.options().quotes(
        OptionsQuotesRequest.builder(
            "AAPL250117C00150000", "BOGUS_OPTION_XYZ", "AAPL250117P00150000")
            .build());

    // Verify: How are partial failures reported per key?
    // Bug indicator: Silent failures, missing map entries without errors
    bySymbol.forEach((sym, resp) ->
        System.out.println(sym + " → " + resp.values().size() + " rows"));
}
```

#### 3.2 Header / Rate-Limit Handling

```java
try (var client = new MarketDataClient()) {

    // A batched multi-symbol request (one HTTP request, one row per symbol)
    StockQuotesResponse result = client.stocks().quotes(
        StockQuotesRequest.of("AAPL", "GOOGL", "MSFT", "AMZN", "META"));

    // Verify: Is the per-response rate-limit snapshot coherent?
    // Bug indicator: Wrong rate-limit info, stale client-level snapshot
    System.out.println("request-scoped: " + result.rateLimit());
    System.out.println("client-level:   " + client.getRateLimits());
}
```

#### 3.3 Large Range Auto-Chunking

```java
try (var client = new MarketDataClient()) {

    // Intraday request spanning > ~1 year triggers the concurrent auto-split
    var result = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.minutes(5), "AAPL")
            .from(LocalDate.now().minusYears(2))
            .to(LocalDate.now())
            .build());

    // Verify: Is the merged data complete? Any gaps/dupes at chunk boundaries?
    // Bug indicator: Missing rows, duplicate rows at year boundaries
}
```

### Red Flags

- Missing data without error messages or missing map entries
- Duplicate rows after a merge
- Rate-limit snapshot showing incorrect counts
- Rate limit exhaustion with few requests

### Pass/Fail Criteria

| Scenario | Pass | Fail |
|----------|------|------|
| Partial failure | Clear per-key error for failed, data for successful | Silent data loss |
| Header / rate-limit | Coherent request-scoped + client-level snapshots | Wrong or stale rate-limit info |
| Auto-chunking | Complete merged data, no duplicates | Data loss or duplication at boundaries |

---

## Area 4: Date/Time Parsing

### What Can Go Wrong

- Unix timestamps decoded differently from ISO timestamps
- `LocalDate` vs `ZonedDateTime` boundary handling
- Year boundary edge cases
- Timezone assumptions

### Test Scenarios

#### 4.1 Timestamp Formats

```java
try (var client = new MarketDataClient()) {

    // The request builders take java.time types; the dateFormat universal param
    // controls how the response encodes dates.
    for (DateFormat df : new DateFormat[]{
            DateFormat.TIMESTAMP, DateFormat.UNIX, DateFormat.SPREADSHEET}) {
        try {
            var result = client.stocks().dateFormat(df).candles(
                StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
                    .from(LocalDate.parse("2024-01-02"))
                    .to(LocalDate.now())
                    .build());
            System.out.println("DateFormat " + df + ": OK");
        } catch (MarketDataException e) {
            System.out.println("DateFormat " + df + ": FAILED - " + e.getMessage());
        }
    }

    // Verify: All response date encodings decode to the right java.time value
    // Bug indicator: Some formats fail or decode to the wrong instant
}
```

#### 4.2 Year Boundaries

```java
try (var client = new MarketDataClient()) {

    // Year boundary — Dec 29 to Jan 2
    var result = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2023-12-29"))
            .to(LocalDate.parse("2024-01-02"))
            .build());

    // Verify: Data spans the year boundary correctly
    // Bug indicator: Missing data around the year change
}
```

#### 4.3 Market Hours

```java
try (var client = new MarketDataClient()) {

    // Intraday data near market open/close
    var result = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.minutes(5), "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .to(LocalDate.parse("2024-01-02"))
            .build());

    // Verify: Candle times (ZonedDateTime) land in the expected timezone
    // Bug indicator: Data offset by hours, wrong trading session
}
```

### Red Flags

- Different results for equivalent `DateFormat` encodings
- Gaps in data at year/month boundaries
- Timezone-related offsets on `ZonedDateTime` fields
- Unix timestamp decoded as the wrong instant

### Pass/Fail Criteria

| Scenario | Pass | Fail |
|----------|------|------|
| Multiple formats | Consistent decoded values | Format-dependent behavior |
| Year boundary | Continuous data | Gap in data |
| Timezone | Correct market hours | Offset data |

---

## Area 5: Multi-Symbol Operations

### What Can Go Wrong

- Empty symbol set causes an error instead of a clear validation failure
- Duplicate symbols not deduplicated
- Single vs multiple symbols handled differently
- Symbol case sensitivity issues

### Test Scenarios

#### 5.1 Empty Symbol Set

```java
try (var client = new MarketDataClient()) {

    // Empty varargs / list
    try {
        StockQuotesResponse result = client.stocks().quotes(StockQuotesRequest.of());
        System.out.println("Empty set: returned " + result.values().size() + " rows");
    } catch (IllegalArgumentException e) {
        System.out.println("Empty set: " + e.getMessage());
    }

    // Verify: Should fail fast with a clear IllegalArgumentException at request build,
    //         or return an empty result — never a crash or ambiguous server error.
    // Bug indicator: NullPointerException, IndexOutOfBoundsException, opaque API error
}
```

#### 5.2 Duplicate Symbols

```java
try (var client = new MarketDataClient()) {

    // Duplicates in the symbol list
    StockQuotesResponse result = client.stocks().quotes(
        StockQuotesRequest.of("AAPL", "AAPL", "GOOGL"));

    // Verify: Duplicates should be deduplicated or handled gracefully
    // Bug indicator: Duplicate rows, wasted rate-limit budget
}
```

#### 5.3 Case Sensitivity

```java
try (var client = new MarketDataClient()) {

    var upper = client.stocks().quote(StockQuotesRequest.of("AAPL"));
    var lower = client.stocks().quote(StockQuotesRequest.of("aapl"));
    var mixed = client.stocks().quote(StockQuotesRequest.of("AaPl"));

    // Verify: All cases should return the same data
    // Bug indicator: Case-dependent failures or different data
}
```

### Red Flags

- Empty set causes a crash instead of graceful handling
- Duplicate data in results
- Different behavior for uppercase vs lowercase
- Single symbol returns a different structure than multiple

### Pass/Fail Criteria

| Scenario | Pass | Fail |
|----------|------|------|
| Empty set | Empty result or clear `IllegalArgumentException` | Crash or ambiguous error |
| Duplicates | Deduplicated or single request | Duplicate data returned |
| Case handling | Consistent results | Case-dependent behavior |

---

## Bug Documentation

When you find a bug, you MUST create a GitHub issue for it. Do not just document it in a file or note.

### Required Information

Capture these details for each bug:

1. **Minimal reproduction code** - Smallest code that demonstrates the bug
2. **Expected behavior** - What should happen
3. **Actual behavior** - What actually happens (include error messages)
4. **Environment**:
   - SDK version: the `app.marketdata:marketdata-sdk-java` version in your `build.gradle(.kts)` or `pom.xml`
   - JDK version: `java -version`
   - OS: macOS/Windows/Linux

### Creating the GitHub Issue (REQUIRED)

**Option 1: CLI (Preferred)**

```bash
gh issue create --label "bug" --title "[Bug]: Brief description" --body "$(cat <<'EOF'
## API Documentation Verification
- [x] I have reviewed the [API documentation](https://www.marketdata.app/docs/api) for this endpoint
- [x] The behavior I'm reporting differs from what the API documentation describes

## SDK Resource
stocks

## Method
candles

## Reproduction Code
```java
// Your minimal reproduction code here
```

## Expected Behavior
What should happen

## Actual Behavior
What actually happens (include error messages)

## SDK Version
1.0.0

## JDK Version
17+

## Additional Context
Found via BUG_FINDING.md [Area N]

Location: `src/main/java/com/marketdata/sdk/SomeClass.java:LINE`
EOF
)"
```

**Option 2: Web Form**

1. Go to [Create Bug Report](https://github.com/MarketDataApp/sdk-java/issues/new?template=bug.yml)
2. Fill out ALL fields with captured information
3. In "Additional Context", note: `Found via BUG_FINDING.md [Area N]`
4. Click "Submit new issue"

> **The bug hunt is NOT complete until the GitHub issue URL exists.** Documenting bugs in markdown files, notes, or any other format is NOT a substitute for creating the actual issue.

### Example Bug Report

```markdown
**Resource**: stocks
**Method**: candles

**Reproduction Code**:
try (var client = new MarketDataClient()) {
    CsvResponse result = client.stocks().asCsv().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.parse("2024-01-02"))
            .build());
    System.out.println(result.values().get(0).high()); // Throws
}

**Expected**: Decode CSV row and access the high field without error
**Actual**: ParseError: required column 'high' missing from CSV response

**SDK Version**: 1.0.0
**JDK Version**: 17.0.10

**Additional Context**: Found via BUG_FINDING.md [Area 1 - Format Switching]
```

---

## Endpoint Checklists

Use these checklists for systematic testing of each resource.

### Stocks Resource

| Method | Area 1 (Formats) | Area 2 (Collections) | Area 3 (Concurrent) | Area 4 (Dates) | Area 5 (Multi) |
|--------|------------------|----------------------|---------------------|----------------|----------------|
| `candles` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty [ ] Single | [ ] Auto-chunk | [ ] Formats [ ] Boundaries | [ ] Multi-symbol |
| `quote` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | N/A | N/A |
| `quotes` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | [ ] Headers | N/A | [ ] Empty [ ] Dupe [ ] Case |
| `prices` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | [ ] Headers | N/A | [ ] Empty [ ] Dupe [ ] Case |
| `news` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | [ ] Formats | [ ] Multi-symbol |
| `earnings` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty [ ] Optional | N/A | [ ] Formats | N/A |

### Options Resource

| Method | Area 1 (Formats) | Area 2 (Collections) | Area 3 (Concurrent) | Area 4 (Dates) | Area 5 (Multi) |
|--------|------------------|----------------------|---------------------|----------------|----------------|
| `lookup` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | N/A | N/A |
| `expirations` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | [ ] Formats | N/A |
| `strikes` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | [ ] Formats | N/A |
| `quote` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | N/A | N/A |
| `quotes` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | [ ] Partial [ ] Headers | N/A | [ ] Multi-contract |
| `chain` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | N/A | [ ] Formats | N/A |

### Markets Resource

| Method | Area 1 (Formats) | Area 2 (Collections) | Area 4 (Dates) |
|--------|------------------|----------------------|----------------|
| `status` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty | [ ] Formats |

### Funds Resource

| Method | Area 1 (Formats) | Area 2 (Collections) | Area 4 (Dates) |
|--------|------------------|----------------------|----------------|
| `candles` | [ ] JSON [ ] CSV [ ] HTML | [ ] Empty [ ] Single | [ ] Formats [ ] Boundaries |

### Utilities Resource

| Method | Area 1 (Formats) | Area 2 (Collections) |
|--------|------------------|----------------------|
| `status` | [ ] JSON | N/A |
| `headers` | [ ] JSON | N/A |

---

## Quick Reference

### Common Test Commands

```bash
# Run a single exploration scenario via a scratch JUnit test
./gradlew test --tests '*Scratch*'

# Run the full unit suite after finding a potential bug
./gradlew test

# Integration tests hit the live API — gated by env var
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest
```

> Tip: drop a `ScratchTest` in `src/test/java/com/marketdata/sdk/` with a single
> `@Test` method holding your exploration code, then run it with the `--tests '*Scratch*'`
> filter above. This keeps exploration inside the build (Spotless, classpath, JDK toolchain)
> rather than a standalone `main`.

### Common Bug Indicators

| Error Message | Likely Area | Likely Cause |
|---------------|-------------|--------------|
| `IndexOutOfBoundsException` | Area 2 | Empty `values()` list access |
| `NullPointerException` | Area 1/2 | Unhandled `@Nullable` field or null response |
| `com.marketdata.sdk.exception.ParseError` | Area 1 | CSV/HTML decode or required-column omission |
| `ClassCastException` | Area 1 | Value decoded to the wrong type |
| `IllegalArgumentException` | Area 5 | Request validation (empty/invalid symbols) |
| Data missing without error | Area 3 | Silent partial failure |
| Duplicate data | Area 3/5 | Chunk boundary or deduplication issue |

### Links

- [Bug Report Template](https://github.com/MarketDataApp/sdk-java/issues/new?template=bug.yml)
- [Issue Workflow (for processing bugs)](ISSUE_WORKFLOW.md)

---

## Completion Checklist

Before considering a bug hunt complete, verify:

- [ ] All discovered bugs have been created as GitHub issues (not just documented)
- [ ] Each issue has a URL (e.g., `https://github.com/MarketDataApp/sdk-java/issues/123`)
- [ ] Each issue follows the bug template format
- [ ] Each issue includes `Found via BUG_FINDING.md [Area N]` in Additional Context

**If you documented bugs but did not create GitHub issues, the bug hunt is NOT complete. Go back and create the issues now.**
