# Checker (`checker/`)

Covenant's contract lint/semantic sidecar: [Spectral](https://stoplight.io/open-source/spectral)
(`oas` + `asyncapi` rulesets) and [`@asyncapi/parser`](https://github.com/asyncapi/parser-js)
behind one `POST /check`, called by the Covenant server only. It never fetches anything: external
`$ref`s are refused before any engine sees them, and the container has no route out.

The full design — contract, engines, severity/source mapping, the offline policy, the JVM
client's fail-open posture, bump procedure — lives in [`.claude/docs/checker.md`](../.claude/docs/checker.md);
the HTTP contract is [`openapi.yaml`](openapi.yaml).

| Command | What it does |
| ------- | ------------ |
| `npm ci` | Install (set `SCARF_ANALYTICS=false` — Spectral's postinstall phones home otherwise) |
| `npm run dev` | Run from source with reload on `:9090` (`tsx watch`) |
| `npm run build && npm start` | Compile to `dist/` and run it (what the image does) |
| `npm run lint` / `npm run knip` / `npm run typecheck` | The static gates (zero findings, no baseline) |
| `npm test` / `npm run test:coverage` | Vitest: the HTTP contract, the mapping, the `$ref` refusal, one fixture per format |

Smoke: `curl -s localhost:9090/healthz` and
`curl -s -XPOST localhost:9090/check -H 'content-type: application/json' -d '{"type":"OPENAPI","content":"openapi: 3.1.0\ninfo: {title: T, version: \"1\"}\npaths: {}\n"}'`.

Env: `PORT` (9090), `CHECKER_TOKEN` (optional shared secret, header `X-Checker-Token`),
`CHECKER_MAX_BYTES` (2 MiB), `CHECKER_TIMEOUT_MS` (20000).
