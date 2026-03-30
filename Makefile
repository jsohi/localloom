.PHONY: setup dev test build clean

# ── Setup ────────────────────────────────────────────────────────────────────
## setup: Check system dependencies and install all project dependencies.
setup:
	@bash scripts/setup.sh

# ── Development ──────────────────────────────────────────────────────────────
## dev: Start API (:8080), ML sidecar (:8100), and frontend (:3000) concurrently.
dev:
	@bash scripts/dev.sh

# ── Test ─────────────────────────────────────────────────────────────────────
## test: Run tests for all three projects.
test:
	@echo "==> Running API tests"
	cd api && ./gradlew test
	@echo "==> Running ML sidecar tests"
	cd ml-sidecar && uv run pytest
	@echo "==> Running frontend tests"
	cd frontend && npm test

# ── Build ────────────────────────────────────────────────────────────────────
## build: Build all three projects (skips tests).
build:
	@echo "==> Building API"
	cd api && ./gradlew build -x test
	@echo "==> Building ML sidecar (wheel)"
	cd ml-sidecar && uv build
	@echo "==> Building frontend"
	cd frontend && npm run build

# ── Clean ────────────────────────────────────────────────────────────────────
## clean: Remove all build artifacts.
clean:
	@echo "==> Cleaning API"
	cd api && ./gradlew clean
	@echo "==> Cleaning ML sidecar"
	rm -rf ml-sidecar/dist ml-sidecar/__pycache__ ml-sidecar/**/__pycache__
	@echo "==> Cleaning frontend"
	rm -rf frontend/.next frontend/out
	@echo "Done."
