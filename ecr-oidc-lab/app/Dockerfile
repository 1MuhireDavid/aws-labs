# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: build/install dependencies
#   - Uses a full-ish alpine image only to install deps, never shipped.
# ---------------------------------------------------------------------------
FROM node:20-alpine AS build

WORKDIR /app

# Copy only manifest files first so this layer is cached unless deps change.
COPY package*.json ./

# --omit=dev keeps devDependencies out of the artifact entirely; deterministic
# install via npm ci instead of npm install.
RUN npm ci --omit=dev

# Copy application source after deps are installed (better layer caching).
COPY . .

# ---------------------------------------------------------------------------
# Stage 2: minimal runtime image
#   - Only production node_modules + source are copied in; no build tools,
#     no package manager caches, no shell history end up in the final image.
# ---------------------------------------------------------------------------
FROM node:20-alpine AS production

ENV NODE_ENV=production \
    PORT=3000

WORKDIR /app

# Create a dedicated, unprivileged, non-login user/group instead of running
# as root (the node:alpine images already ship a "node" user, but we create
# our own to be explicit and portable across base images).
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build --chown=appuser:appgroup /app/node_modules ./node_modules
COPY --from=build --chown=appuser:appgroup /app/package*.json ./
COPY --from=build --chown=appuser:appgroup /app/server.js ./

# Drop root privileges for the remainder of the build and at runtime.
USER appuser

EXPOSE 3000

# Let the orchestrator / Docker know the container is actually serving traffic.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1

# Exec form: node runs as PID 1, receives signals directly (clean shutdowns).
CMD ["node", "server.js"]
