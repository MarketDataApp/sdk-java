# Convenience wrapper around ./gradlew and the consumer-test / mock-server.
# Run `make` (no args) or `make help` for the target list.

CONSUMER_DIR := examples/consumer-test
MOCK_DIR     := examples/mock-server

.DEFAULT_GOAL := help

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make \033[36m<target>\033[0m\n\nTargets:\n"} \
	  /^# ===/ { in_section = 1; next } \
	  /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 } \
	  /^## ---/ { printf "\n\033[33m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo ""
	@echo "Typical workflow:"
	@echo "  make publish              # publish SDK to mavenLocal"
	@echo "  make example-stocks       # a resource example (live API, needs a token)"
	@echo "  make mock-server          # in another terminal, then:"
	@echo "  make example-concurrency  # a cross-cutting example (needs the mock server)"

# ---------------------------------------------------------------------------
## --- SDK build ---
# ---------------------------------------------------------------------------

.PHONY: build
build: ## Full build: unit tests + Spotless + JaCoCo (JDK 17)
	./gradlew build

.PHONY: test
test: ## Unit tests only
	./gradlew test

.PHONY: spotless
spotless: ## Apply code formatting
	./gradlew spotlessApply

.PHONY: clean
clean: ## Clean all Gradle outputs (SDK + consumer-test)
	./gradlew clean
	cd $(CONSUMER_DIR) && ./gradlew clean

.PHONY: publish
publish: ## Publish SDK to ~/.m2 (prereq for any demo)
	./gradlew publishToMavenLocal

# ---------------------------------------------------------------------------
## --- Mock server ---
# ---------------------------------------------------------------------------

.PHONY: mock-server
mock-server: ## Start the FastAPI mock server (blocks, Ctrl+C to stop)
	cd $(MOCK_DIR) && ./run.sh

# ---------------------------------------------------------------------------
## --- Examples (need `make publish` first) ---
# ---------------------------------------------------------------------------
# Resource examples hit the LIVE API (need a token). The cross-cutting examples that show otherwise-
# invisible behavior (concurrency, retry, errors) drive the mock server — run `make mock-server`
# first for those. `make example-list` prints every runnable example with a one-line description.

.PHONY: example-list
example-list: ## List every runnable example with a description
	cd $(CONSUMER_DIR) && ./gradlew tasks --group examples

# --- resource examples (live API) ---
.PHONY: example-utilities
example-utilities: ## utilities: health, quota, request echo (live)
	cd $(CONSUMER_DIR) && ./gradlew runUtilities

.PHONY: example-stocks
example-stocks: ## stocks: candles, quote, batch quotes (live)
	cd $(CONSUMER_DIR) && ./gradlew runStocks

.PHONY: example-options
example-options: ## options: lookup, expirations, chain, quote (live)
	cd $(CONSUMER_DIR) && ./gradlew runOptions

.PHONY: example-funds
example-funds: ## funds: NAV candles (live)
	cd $(CONSUMER_DIR) && ./gradlew runFunds

.PHONY: example-markets
example-markets: ## markets: open/closed calendar (live)
	cd $(CONSUMER_DIR) && ./gradlew runMarkets

.PHONY: example-kotlin
example-kotlin: ## the same SDK from Kotlin: sync + async (live)
	cd $(CONSUMER_DIR) && ./gradlew runKotlinQuickstart

# --- cross-cutting examples ---
.PHONY: example-sync-async
example-sync-async: ## sync vs async, parallel fan-out (live)
	cd $(CONSUMER_DIR) && ./gradlew runSyncVsAsync

.PHONY: example-config
example-config: ## constructors, cascade, redaction, validation (offline)
	cd $(CONSUMER_DIR) && ./gradlew runConfiguration

.PHONY: example-response
example-response: ## response wrapper: data, metadata, formats, saveToFile (live)
	cd $(CONSUMER_DIR) && ./gradlew runResponseFormats

.PHONY: example-concurrency
example-concurrency: ## fan-out async + 50-permit cap, observed (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runConcurrency

.PHONY: example-retry
example-retry: ## automatic retry + backoff + Retry-After (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runRetry

.PHONY: example-errors
example-errors: ## the sealed exception hierarchy and how to handle it (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runErrors

.PHONY: examples-mock
examples-mock: ## Run the three mock-server examples back-to-back (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runConcurrency runRetry runErrors
