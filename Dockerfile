# ------------------------------------------------------------------------------
# Multi-stage Dockerfile for Cardano Vault Scala Backend
# ------------------------------------------------------------------------------

# Stage 1: Build & Stage with SBT
FROM sbtscala/scala-sbt:eclipse-temurin-21_1.x AS builder

WORKDIR /app

# Cache dependencies
COPY project/build.properties project/
COPY project/plugins.sbt project/
COPY build.sbt .

RUN sbt update

# Copy source code
COPY contract/ contract/
COPY backend/ backend/

# Build standalone universal package
RUN sbt -batch "backend/stage"

# ------------------------------------------------------------------------------
# Stage 2: Minimal Runtime Image
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache bash curl

# Copy staged distribution
COPY --from=builder /app/backend/target/universal/stage/ /app/

ENV HOST=0.0.0.0
ENV PORT=10000

EXPOSE 10000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:${PORT}/health || exit 1

ENTRYPOINT ["/app/bin/vault-backend"]

