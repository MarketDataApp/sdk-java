# ADR-005: JSON Library

## Status

Proposed — under discussion.

## Context

The Market Data API speaks JSON over HTTP. The SDK must:

- Parse responses into typed POJOs/records (requirements §11.1, §11.2)
- Decode the API's "compressed, array-keyed JSON" wire format into
  named-field models (§11.1) — this requires custom deserialization
  logic, not just default reflection
- Serialize request bodies (where applicable) and query parameters
- Avoid imposing classpath conflicts on consumers

The JDK has no built-in high-level JSON binding library. JEP 198
proposed `java.util.json` years ago and never landed; as of JDK 25
there is still no standard library answer. We must pick a third-party
dependency.

The wire-format-decoding requirement is non-trivial: the API's primary
response shape is a parallel-arrays format like
`{"s":"ok","symbol":["AAPL","MSFT"],"price":[150.0,400.0]}` rather than
the array-of-objects form `[{"symbol":"AAPL","price":150.0}, …]`. The
SDK has to expand this into typed models. Whichever library we pick
must support custom deserializers cleanly.

## Options Considered

### Option A — Jackson (jackson-databind)

The de facto JSON library for Java.

**Pros**

- Industry-standard. The vast majority of Java consumers already have
  Jackson on their classpath.
- Records support since Jackson 2.12 (released 2020) — works out of
  the box for record-based response models.
- Modular: `jackson-core`, `jackson-databind`, `jackson-annotations`
  are separable; we only depend on what we use.
- Excellent custom serializer/deserializer support — well-suited to
  the wire-format decoding requirement.
- Mature streaming API (`JsonParser`/`JsonGenerator`) for cases where
  we want to avoid full tree allocation.
- Active development; security patches land quickly.

**Cons**

- **Classpath collision risk.** Consumers running an older Jackson
  version may break when our SDK pulls in a newer one (or vice versa).
  This is the recurring pain point with Jackson on shared classpaths.
- Mitigations exist (shading Jackson into our JAR; documenting a
  minimum Jackson version) but each has costs.
- Fairly large surface area — `jackson-databind` alone is ~1.5 MB.
  That's not a lot in absolute terms but isn't negligible.
- Historically the source of a meaningful number of CVEs (the
  polymorphic-deserialization vulnerabilities of 2017–2019). We
  don't use polymorphic deserialization, so we're not exposed, but
  the CVE-tracking overhead is non-zero.

### Option B — Gson

Google's JSON library.

**Pros**

- Simpler API than Jackson. `new Gson().fromJson(...)` is hard to
  beat for ergonomics.
- Smaller surface area (~280 KB).
- No annotation requirements for basic cases.
- Single dependency — no `core`/`databind`/`annotations` split.
- Lower historical CVE burden.

**Cons**

- Records support is workable but less polished than Jackson's. Need
  a custom `TypeAdapterFactory` for full ergonomics, or rely on Gson's
  reflection-based path which is slower.
- Custom deserialization for the parallel-arrays wire format requires
  more boilerplate than Jackson's equivalent.
- Active development has slowed. Releases are infrequent compared to
  Jackson (or Moshi). For an SDK that needs long-term security
  maintenance this is a real concern.
- No streaming Java records integration; would need to mix Gson's
  `JsonReader` with manual record construction.

### Option C — Moshi

Square's JSON library, born out of OkHttp's lineage.

**Pros**

- Modern design; clean API.
- Strong codegen support via `moshi-kotlin-codegen` for build-time
  reflection avoidance — fastest of the three at runtime.
- Smaller than Jackson (~150 KB).
- Lower historical CVE burden.
- Records support via `moshi-java-records-reflect` add-on.

**Cons**

- Sweet spot is Kotlin/Android. Pure-Java usage works but is less
  documented and the codegen story is Kotlin-first.
- Records support is not in the core artifact — adds another module
  to the dep list.
- Smaller community than Jackson; less Stack Overflow surface area
  for unusual deserialization cases.
- Less obvious win for our use case: the wire-format decoder is the
  hard part, and Moshi's codegen advantage doesn't apply to custom
  adapters.

### Option D — JSON-B (Jakarta JSON Binding) + Yasson

The standardized Jakarta EE JSON binding API with the reference
implementation.

**Pros**

- Standard API — in principle the implementation could be swapped.
- Annotation model is similar to Jackson's; familiar to Jakarta EE
  developers.

**Cons**

- Requires both an API artifact (`jakarta.json.bind-api`) and an
  implementation (`yasson` — itself ~600 KB and pulls in `parsson`).
- Limited third-party SDK adoption. Smaller community.
- Custom deserialization (needed for wire-format decoding) is workable
  but less ergonomic than Jackson's.
- The "swappable implementation" benefit is theoretical — almost no
  one swaps JSON-B implementations in practice.

### Option E — Hand-rolled JSON parser

Mentioned for completeness only.

**Pros**

- Zero dependencies.

**Cons**

- Unequivocally a bad idea for an SDK that must handle real-world
  edge cases (Unicode escapes, numeric precision, trailing commas
  emitted by misbehaving servers, very large responses, etc.).
- Maintenance burden grows over time as the API surface grows.
- Listed and rejected; not a serious option.

## Claude's Recommendation

**Option A (Jackson).**

The deciding factors:

1. Records support is mature and frictionless. Given that ADR-002 is
   trending toward JDK 17 (which makes records the natural response
   model), Jackson's record support is the cleanest of the three.
2. The wire-format decoding requirement (§11.1) is the load-bearing
   technical challenge here, and Jackson's `@JsonDeserialize` +
   custom `JsonDeserializer` model is the most ergonomic and
   best-documented solution among the options.
3. Industry default. New Java engineers onboarding to this SDK will
   already know Jackson.
4. Active development pace and rapid CVE response.

The classpath-collision concern is real but manageable. Two reasonable
mitigations, in order of preference:

- **Document a minimum Jackson version** (likely 2.17+) and list
  Jackson as a required dependency in our published POM. Consumers
  with a strictly older Jackson version will see a clear failure;
  upgrade paths exist.
- **Shade Jackson into the SDK JAR** if collisions become a real
  customer pain. Adds build complexity (Gradle Shadow plugin or
  Maven Shade plugin) and roughly doubles JAR size. Defer until
  there's evidence it's needed.

The strongest counter-recommendation is **Option B (Gson)** if the
team values simplicity and a smaller dep over Jackson's ecosystem. Gson
is genuinely fine; it's the second-best fit.

**Option C (Moshi)** would be the right answer if this were a
Kotlin-first SDK — but ADR-001 is trending toward Java-only, and
Moshi's advantages don't translate.

**Option D (JSON-B)** has no clear upside over Jackson here.

## Decision

*To be filled in by the team.*

## Consequences

*To be filled in once a decision is made.*

- **A (Jackson):** Add `jackson-databind` (and transitively `core` +
  `annotations`) as a runtime dependency. Document the minimum
  Jackson version. Custom deserializers for wire-format decoding live
  in `internal/wire/` or similar. Decide later whether to shade.
- **B (Gson):** Add `com.google.code.gson:gson` as a runtime
  dependency. Hand-write a `TypeAdapter` family for response models;
  custom `JsonDeserializer` for wire-format decoding.
- **C (Moshi):** Add `com.squareup.moshi:moshi` plus
  `moshi-java-records-reflect`. Codegen path likely not used. More
  boilerplate for the wire-format adapter than Jackson would need.
- **D (JSON-B):** Add `jakarta.json.bind-api` and `yasson` as runtime
  dependencies. Custom serializers via the JSON-B serializer SPI.

## References

- [Jackson documentation](https://github.com/FasterXML/jackson)
- [Jackson record support (2.12 release notes)](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.12)
- [Gson user guide](https://github.com/google/gson/blob/main/UserGuide.md)
- [Moshi documentation](https://github.com/square/moshi)
- [Yasson (JSON-B reference impl)](https://github.com/eclipse-ee4j/yasson)
- [Market Data SDK Requirements §11.1 — Wire Format Decoding](../sdk-requirements.md)
- [Gradle Shadow plugin](https://gradleup.com/shadow/) — for the
  shading mitigation if needed
