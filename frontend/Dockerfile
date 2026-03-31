# ── Stage 1: Install dependencies ────────────────────────────────────────────
FROM node:20-alpine AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

# ── Stage 2: Build ───────────────────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /app

ARG API_URL=http://localhost:8080
ENV API_URL=${API_URL}

COPY --from=deps /app/node_modules node_modules
COPY . .
RUN mkdir -p public

RUN npm run build

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM node:20-alpine
RUN apk add --no-cache curl

WORKDIR /app

ENV NODE_ENV=production

COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static .next/static
COPY --from=build /app/public public

EXPOSE 3000

RUN addgroup -g 1001 -S nodejs && adduser -S nextjs -u 1001
USER nextjs

CMD ["node", "server.js"]
