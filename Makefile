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
	@echo "  make mock-server          # in another terminal"
	@echo "  make demo-config          # in a third terminal"

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
## --- Consumer demos (need `make publish` first) ---
# ---------------------------------------------------------------------------

.PHONY: demo-quickstart
demo-quickstart: ## Idiomatic per-resource usage tour (live API; grows as resources land)
	cd $(CONSUMER_DIR) && ./gradlew runQuickstart

.PHONY: demo-live
demo-live: ## Live API smoke (needs MARKETDATA_TOKEN in examples/consumer-test/.env)
	cd $(CONSUMER_DIR) && ./gradlew runLive

.PHONY: demo-config
demo-config: ## Demo mode, cascade, validation (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runDemoConfig

.PHONY: demo-exceptions
demo-exceptions: ## Every MarketDataException subtype (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runExceptions

.PHONY: demo-retry
demo-retry: ## Retry, Retry-After, preflight (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runRetry

.PHONY: demo-response
demo-response: ## MarketDataResponse surface features (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runResponse

.PHONY: demo-concurrency
demo-concurrency: ## 50-permit semaphore (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runConcurrency

.PHONY: demo-options
demo-options: ## Full options surface: every endpoint + all params, CSV facet, columns, Option A (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runOptions

.PHONY: demo-stocks
demo-stocks: ## Full stocks surface: every endpoint + all params, CSV facet, columns, Option A (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runStocks

.PHONY: demos-all
demos-all: ## Run every mock-server-based demo back-to-back (needs mock-server)
	cd $(CONSUMER_DIR) && ./gradlew runDemoConfig runExceptions runRetry runResponse runConcurrency runOptions runStocks
