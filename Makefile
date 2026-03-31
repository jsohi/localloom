.PHONY: setup dev lint format test build push clean

# ── Setup ────────────────────────────────────────────────────────────────────
## setup: Check system dependencies and install all project dependencies.
setup:
	@bash scripts/setup.sh

# ── Development ──────────────────────────────────────────────────────────────
## dev: Start API (:8080), ML sidecar (:8100), and frontend (:3000) concurrently.
dev:
	@bash scripts/dev.sh

# ── Lint & Format ────────────────────────────────────────────────────────────
## lint: Run linters for all three projects.
lint:
	@echo "==> Linting API (Spotless check)"
	cd api && ./gradlew spotlessCheck
	@echo "==> Linting ML sidecar (ruff)"
	cd ml-sidecar && uv run ruff check .
	@echo "==> Linting frontend (ESLint)"
	cd frontend && npm run lint

## format: Auto-format all three projects.
format:
	@echo "==> Formatting API (Spotless)"
	cd api && ./gradlew spotlessApply
	@echo "==> Formatting ML sidecar (ruff)"
	cd ml-sidecar && uv run ruff format . && uv run ruff check --fix .
	@echo "==> Formatting frontend (Prettier)"
	cd frontend && npm run format

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

# ── Push (pre-flight checks) ────────────────────────────────────────────────
## push: Run lint + test + build for all services, then git push. Use this instead of raw git push.
push:
	@echo "==> Step 1/4: Formatting"
	cd api && ./gradlew spotlessApply
	cd frontend && npm run format 2>/dev/null || true
	@echo "==> Step 2/4: Linting"
	cd api && ./gradlew spotlessCheck
	cd frontend && npm run lint
	@echo "==> Step 3/4: Running tests"
	cd api && ./gradlew test
	cd frontend && npx vitest run
	@echo "==> Step 4/4: Pushing"
	git push -u origin $$(git branch --show-current)
	@echo "==> Push complete."

# ── Clean ────────────────────────────────────────────────────────────────────
## clean: Remove all build artifacts.
clean:
	@echo "==> Cleaning API"
	cd api && ./gradlew clean
	@echo "==> Cleaning ML sidecar"
	rm -rf ml-sidecar/dist
	find ml-sidecar -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
	@echo "==> Cleaning frontend"
	rm -rf frontend/.next frontend/out
	@echo "Done."
