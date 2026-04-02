.PHONY: setup dev restart lint format test e2e build push start start-dev stop docker-build docker-up docker-down docker-logs clean backup restore

# ── Setup ────────────────────────────────────────────────────────────────────
## setup: Check system dependencies and install all project dependencies.
setup:
	@bash scripts/setup.sh

# ── Development ──────────────────────────────────────────────────────────────
## dev: Start API (:8080), ML sidecar (:8100), and frontend (:3000) concurrently.
dev:
	@bash scripts/dev.sh

## restart: Kill running services and restart everything (backing services + dev servers).
restart:
	@bash scripts/restart.sh

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

# ── E2E ──────────────────────────────────────────────────────────────────────
## e2e: Run E2E tests with Playwright (requires Docker + Ollama running). Pass ARGS for Playwright flags.
e2e:
	bash scripts/e2e.sh $(ARGS)

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
## push: Run format + lint + test for all services, then git push. Use this instead of raw git push.
push:
	@if [ "$$(git branch --show-current)" = "main" ] || [ "$$(git branch --show-current)" = "master" ]; then \
		echo "ERROR: Do not push directly to main. Create a feature branch first."; \
		exit 1; \
	fi
	@echo "==> Step 1/4: Formatting"
	cd api && ./gradlew spotlessApply
	cd ml-sidecar && uv run ruff format . 2>/dev/null || true
	cd frontend && npm run format 2>/dev/null || true
	@echo "==> Step 2/4: Linting"
	cd api && ./gradlew spotlessCheck
	cd ml-sidecar && uv run ruff check . 2>/dev/null || true
	cd frontend && npm run lint
	@echo "==> Step 3/4: Running tests"
	cd api && ./gradlew test
	cd ml-sidecar && uv run pytest --ignore=tests/ml 2>/dev/null || true
	cd frontend && npx vitest run
	@echo "==> Step 4/4: Pushing"
	git push --force-with-lease -u origin $$(git branch --show-current)
	@echo "==> Push complete."

# ── Start / Stop ────────────────────────────────────────────────────────
## start: Start all services in production mode (Docker Compose + Ollama).
start:
	@bash scripts/start.sh prod

## start-dev: Start infra in Docker, app services natively with hot reload.
start-dev:
	@bash scripts/start.sh dev

## stop: Stop all running services.
stop:
	@bash scripts/start.sh stop

# ── Docker ───────────────────────────────────────────────────────────────────
## docker-build: Build all Docker images.
docker-build:
	docker compose build

## docker-up: Start all services in detached mode.
docker-up:
	docker compose up -d

## docker-down: Stop and remove all services.
docker-down:
	docker compose down

## docker-logs: Follow logs from all services.
docker-logs:
	docker compose logs -f

# ── Logs ─────────────────────────────────────────────────────────────────────
## logs: Tail all service log files.
logs:
	@tail -f logs/api.log logs/ml-sidecar.log 2>/dev/null || echo "No log files found. Start services first with 'make start' or 'make start-dev'."

## logs-export: Export all logs (app files + Docker stdout) to logs/export/.
logs-export:
	@bash scripts/logs-export.sh

# ── Backup / Restore ─────────────────────────────────────────────────────────
## backup: Back up PostgreSQL + ChromaDB to ./backups/ (keeps last 20).
backup:
	@bash scripts/backup.sh

## restore: Restore from a backup (interactive or pass timestamp).
restore:
	@bash scripts/restore.sh $(filter-out $@,$(MAKECMDGOALS))

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
