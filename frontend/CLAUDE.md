# frontend/CLAUDE.md — Next.js frontend

Scoped instructions for AI agents working inside `frontend/`. Read the root [`../CLAUDE.md`](../CLAUDE.md) first.

## What lives here

Next.js 14 (app router) + TypeScript + Tailwind + shadcn/ui:

```
src/
  app/              App-router pages (layout.tsx, page.tsx, chat/, sources/, ...)
  components/       React components (chat-view, chat-input, chat-message, source cards, ...)
  components/ui/    shadcn/ui primitives (button, input, dialog, ...)
  hooks/            Custom React hooks
  lib/              Client-side utilities
    api.ts          API wrapper — streamQuery (SSE), getConversations, etc.
    types.ts        TypeScript types for API responses
  test/             Vitest tests
```

## Commands

```bash
cd frontend && npm run dev        # dev server :3000 (use `make dev` for the full stack)
cd frontend && npm run lint       # ESLint
cd frontend && npm run format     # Prettier
cd frontend && npx vitest run     # tests
cd frontend && npm run build      # production build
```

## Conventions

- **App router only.** Don't mix in pages-router patterns.
- **Server components by default** — add `"use client"` only when you need hooks or browser APIs.
- **shadcn/ui for new primitives.** Don't hand-roll a button / dialog / input when a shadcn component already exists under `components/ui/`.
- **Tailwind for styling.** No CSS modules, no styled-components.
- **API calls go through `src/lib/api.ts`.** Do not call `fetch()` directly from components. If you need a new endpoint wrapper, add it to `api.ts`.
- **Streaming** — the SSE client is `streamQuery` in `src/lib/api.ts`. Reuse it; do not reimplement the parser. The event types it emits are `token`, `sources`, `done`.
- **Types** — add API response shapes to `src/lib/types.ts`, keep them in sync with the Java DTOs in `api/src/main/java/com/localloom/service/dto/`.

## Adding things

### Add a page

Drop `src/app/<segment>/page.tsx`. Wrap long-lived client state in a `"use client"` component and compose from a server component if possible.

### Add a component

- Reusable primitive → `src/components/ui/<name>.tsx` (follow shadcn pattern).
- Feature component → `src/components/<name>.tsx`.

### Call a backend endpoint

1. Add the wrapper to `src/lib/api.ts`.
2. Add the response type to `src/lib/types.ts`.
3. Consume via TanStack Query or direct call — the codebase uses both; follow the nearest existing pattern.

### Add a test

Vitest + React Testing Library. Tests live under `src/test/` or colocated as `*.test.ts[x]`. Run with `npx vitest run`.

## Gotchas

- **SSE parsing is finicky.** `streamQuery` already handles partial frames and reconnects. Don't reimplement.
- **API URL** is provided via the `API_URL` env var at build time (see `docker-compose.yml`). In dev (`make dev`) it defaults to `http://localhost:8080`.
- **CORS** is enforced by the API — if you're hitting the API from a non-`localhost:3000` origin, set `LOCALLOOM_CORS_ORIGINS` on the API.
- **Do not add a state management library.** The app uses React state + TanStack Query. No Redux, Zustand, Jotai.
- **Do not import from `node_modules` types for Radix/shadcn** — import from the wrapper in `components/ui/`.
