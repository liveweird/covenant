---
name: verify
description: Drive the covenant SPA end-to-end with Playwright to observe a change working against the local dev stack. Use after nontrivial frontend/backend changes, before committing.
---

# Verifying changes end-to-end

## Handle

The surface is the SPA in a browser. Use the running local dev stack (preferred per project convention): `docker compose up postgres checker` + `./gradlew :server:run` + `cd web && npm run dev`, then drive `http://localhost:5175` (Vite serves the edited source with HMR; `/api` proxies to :8082). Check what's already up first: `lsof -nP -iTCP:8082 -iTCP:5175 -sTCP:LISTEN` — reuse a healthy stack, and remember stray `:server:run` JVMs squat :8082. Covenant's ports deliberately avoid Lettuce's (8080/5173/5432) and Toadie's (8081/5174/5433), so double-check WHICH app answers before concluding anything.

No Chrome-extension automation required: Playwright is installed in `e2e/node_modules`. A scratch script can import it directly:

```js
import { chromium } from "/<repo>/e2e/node_modules/playwright/index.mjs";
```

Chromium binaries are already installed (the e2e suite uses them).

## Drive recipe (gotchas that cost time)

- **Leftover sessions block the login form.** While `covenant.auth.*` localStorage keys exist, `RedirectIfAuthed` bounces `/login` to the home page and a `fill()` waits out the whole timeout. Clear first (the `e2e/tests/helpers.ts` trick): `await page.goto("/login"); await page.evaluate(() => localStorage.clear()); await page.goto("/login");`.
- **Mantine locators:** `getByLabel(/password/i)` is a strict-mode violation (matches the visibility-toggle button too). Use `getByRole("textbox", { name: ... })`.
- **Login:** seed admin `admin@covenant.local` / `changeme`. Keep logins to a minimum — the per-IP `/login` rate limit produces roaming 429s (though the dev stack lifts it to 1000/min; see below). Five consecutive FAILED logins for one email lock that account for 15 minutes (in-memory — restarting the server clears it).
- **Language probe:** switch via the account menu's Language section; the choice persists in `localStorage` (`covenant.lang`) AND is saved on the user (`PUT /users/{id}/language`), so a re-login restores it — a hand-set `covenant.lang` + reload probes only the UI half.
- **Lazy-route fill race (production bundle only):** after clicking a link to another SPA route, `waitForURL` passes while the OLD page is still rendered (React Router flips the URL before the lazy chunk mounts — instant in Vite dev, slow enough to bite against the built bundle). A locator that matches fields on both pages silently fills the old page's input, which then unmounts. Always `waitFor()` an element unique to the target page before filling.
- **Rate-limit self-interference:** `/login` and `/refresh` have per-IP token buckets (10/min — lifted to 1000/min in development mode — and 30/min). Curl "warm-up probes" against those endpoints eat the budget of the Playwright run that follows — probe readiness via `GET /` instead, or `docker restart covenant-app` to reset the in-memory buckets.

## Cleanup

The scaffold's API surface creates no records beyond users and sessions (`revoked_tokens` rows prune themselves); contract features will add rows to soft-delete (`marked_as_deleted = true`, never `DELETE`). If a verification touched the database directly, remove the rows via psql: `docker compose exec postgres psql -U covenant covenant` (soft-delete users by setting `marked_as_deleted = true`, never `DELETE`). Use a recognizable marker like `verify.` in test emails so leftovers are findable. Never mutate the seed admin — if a probe changed its password, restore the V3 state (hash in `infra/db/Bootstrap.kt`, or `TestSeedState.restoreSeedAccounts()` from a test).
