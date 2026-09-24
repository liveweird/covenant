# syntax=docker/dockerfile:1
# The checker sidecar image — build context is the REPO ROOT (docker-compose.yaml sets it), so
# .dockerignore applies: `docker build -t covenant-checker:<build-tag> -f checker/Dockerfile .`

# ── Stage 1: install + compile ────────────────────────────────────────────────
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
# Spectral's @scarf/scarf postinstall phones home; opt out everywhere the deps install.
ENV SCARF_ANALYTICS=false
WORKDIR /checker
COPY checker/package.json checker/package-lock.json ./
RUN npm ci
COPY checker/tsconfig.json checker/tsconfig.build.json ./
COPY checker/src ./src
RUN npm run build && npm prune --omit=dev

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS runtime
ENV NODE_ENV=production SCARF_ANALYTICS=false PORT=9090
WORKDIR /checker
COPY --from=build /checker/node_modules ./node_modules
COPY --from=build /checker/dist ./dist
COPY checker/package.json ./
# npm is needed to assemble the build stage, but the sidecar only invokes node at runtime.
# Drop the unused global CLI and its bundled dependencies from the final image.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
# Unprivileged, read-only root filesystem (compose `read_only: true`, the k8s securityContext).
USER node
EXPOSE 9090
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:9090/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/server.js"]
