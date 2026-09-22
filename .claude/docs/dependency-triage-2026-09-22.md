# Dependency triage — 2026-09-22

Scope: the eleven open Dependabot PRs, npm advisories and compatible npm updates, plus the
Node/JDK image pins involved in those PRs. This is not a complete JVM/container vulnerability
scan or a new review of every Gradle dependency. Follow [dependency maintenance](dependencies.md).

## Major-version PR decisions

All eleven PRs remain open, with an explicit deferred decision. No PR was merged, closed or
commented on as part of this triage; no new Dependabot ignore rules suppress future updates.

| PRs | Decision and evidence | Revisit when |
| --- | --- | --- |
| [26](https://github.com/liveweird/covenant/pull/26), [28](https://github.com/liveweird/covenant/pull/28) — Node 26 | Keep Node 24. Node 26 is Current; the project requires an LTS production runtime. Root/checker image stages and local tooling stay aligned. | Node 26 reaches LTS and a coordinated runtime/type migration is tested. |
| [27](https://github.com/liveweird/covenant/pull/27) — JDK 24 | Keep JDK/JRE 21. The PR changes the build JDK major while Gradle, local tooling and the runtime remain 21; Java 24 is non-LTS. | A supported LTS migration updates the toolchain, build and runtime together. |
| [9](https://github.com/liveweird/covenant/pull/9), [14](https://github.com/liveweird/covenant/pull/14), [16](https://github.com/liveweird/covenant/pull/16) — Node types 26 | Keep types 24 in web, E2E and checker, matching the actual runtime. | The Node runtime major changes. |
| [4](https://github.com/liveweird/covenant/pull/4), [6](https://github.com/liveweird/covenant/pull/6), [7](https://github.com/liveweird/covenant/pull/7) — TypeScript 7 | Keep TypeScript 6.0.3. Even typescript-eslint 8.70.1 declares `>=4.8.4 <6.1.0`; openapi-typescript 7.13.0 declares `^5.x`. The existing frontend exception for TS6 does not authorize a TS7 migration. | Lint and API-generation tooling support the compiler, followed by a coordinated three-workspace migration. |
| [10](https://github.com/liveweird/covenant/pull/10) — networknt 3 | Keep 2.0.7, the latest 2.x listed by Maven Central at this check. `VendoredSchemas.validate` passes a Jackson 2 `JsonNode`; networknt 3 uses Jackson 3 types. | The parser/schema/Avro boundary can migrate together. |
| [3](https://github.com/liveweird/covenant/pull/3) — Stoplight types 14 | Keep the root 13.x range. AsyncAPI parser 3.6.3 and Spectral core/rulesets still require 13.x; spectral-parsers has an isolated 14.x subtree. Raising the root version only rearranges that split. Version 14 also changes HTTP security types and requires bundled webhooks. | Parser and Spectral requirements allow a coordinated update with unchanged fixture verdicts. |

The direct Stoplight types dependency is not imported by checker source and is explicitly
excluded in Knip. Removing that pin is a separate cleanup consideration; upgrading it alone
does not align the upstream graph.

Primary compatibility evidence:

- [Node release policy/status](https://nodejs.org/en/about/previous-releases).
- [Temurin support roadmap](https://adoptium.net/support/).
- Exact npm metadata: [typescript-eslint 8.70.1](https://registry.npmjs.org/typescript-eslint/8.70.1),
  [openapi-typescript 7.13.0](https://registry.npmjs.org/openapi-typescript/7.13.0),
  [AsyncAPI parser 3.6.3](https://registry.npmjs.org/@asyncapi/parser/3.6.3),
  [Spectral core 1.23.1](https://registry.npmjs.org/@stoplight/spectral-core/1.23.1),
  [Spectral rulesets 1.22.7](https://registry.npmjs.org/@stoplight/spectral-rulesets/1.22.7),
  [Stoplight types 14.1.1](https://registry.npmjs.org/@stoplight/types/14.1.1).
- [networknt's Jackson compatibility boundary](https://github.com/networknt/json-schema-validator#usage)
  and [Maven Central version metadata](https://repo.maven.apache.org/maven2/com/networknt/json-schema-validator/maven-metadata.xml).

## Compatible updates selected for 1.0.4

| Dependency | Before → after | Scope |
| --- | --- | --- |
| Mantine core/form/hooks/notifications/spotlight (+ transitive store) | 9.6.1 → 9.6.2 | Frontend; all Mantine packages aligned, Spotlight keeps its exact pin. |
| CodeMirror state / view | 6.7.5 → 6.7.6 / 6.43.12 → 6.43.13 | Editor cursor and bidirectional movement fixes. |
| TanStack React Query (+ query-core) | 5.103.1 → 5.103.2 | Ignore removal requests for query instances no longer in the cache. |
| react-i18next | 17.0.14 → 17.0.15 | Upstream Trans child-rendering fix; Covenant currently has no Trans component usage. |
| Tabler icons | 3.47.0 → 3.48.0 | Additive icon release. |
| typescript-eslint family | 8.70.0 → 8.70.1 | Shared lint-tooling patch in web, checker and E2E. |
| ignore (transitive development dependency) | 7.0.9 → 7.0.10 | Web/E2E ESLint subtree; wildcard matching fix, within the existing `^7.0.5` range. Checker retains 7.0.9. |

Reviewed upstream notes: [Mantine](https://github.com/mantinedev/mantine/releases/tag/9.6.2),
[React Query](https://github.com/TanStack/query/releases/tag/%40tanstack%2Freact-query%405.103.2),
[query-core](https://github.com/TanStack/query/releases/tag/%40tanstack%2Fquery-core%405.103.2),
[react-i18next](https://github.com/i18next/react-i18next/blob/master/CHANGELOG.md),
[Tabler](https://github.com/tabler/tabler-icons/releases/tag/v3.48.0), and
[typescript-eslint](https://github.com/typescript-eslint/typescript-eslint/releases/tag/v8.70.1).
The incidental `ignore` lockfile update was identified during independent review and retained
after inspecting the [7.0.9 → 7.0.10 source diff](https://github.com/kaelzhang/node-ignore/compare/7.0.9...7.0.10),
including its regression fixtures. It corrects wildcard handoff after a partial separator match;
it is development-only and does not change the application/checker runtime dependency graph.
CodeMirror notes were read from the exact npm tarballs for
[state 6.7.6](https://registry.npmjs.org/@codemirror/state/6.7.6) and
[view 6.43.13](https://registry.npmjs.org/@codemirror/view/6.43.13).

## Runtime pins and security observations

Registry manifest inspection matched the committed multi-platform digests for
`node:24.21.0-alpine`, `eclipse-temurin:21.0.12_8-jdk-noble`, and
`eclipse-temurin:21.0.12_8-jre-noble`. No pin changes were made. The local mise JDK's CSPU
distinction remains documented in the run-stack playbook; no newer paired container tags
were established by this targeted check.

GitHub returned no open Dependabot vulnerability alerts. npm audits reported zero known
vulnerabilities in web, checker and E2E. These results describe the registries' advisory data
at the time of the check, not proof that the application or images contain no vulnerabilities.

## Verification

- Clean installs from all three lockfiles passed. Manifest/lockfile dependency declarations
  match; Mantine resolves consistently to 9.6.2 and the lint family to 8.70.1.
- Frontend: API schema regeneration produced no drift; lint, Knip, coverage and build passed.
  All 639 tests passed; coverage remained 93.02% statements, 86.39% branches, 90.09% functions
  and 95.81% lines, above the unchanged floors.
- Checker: lint, Knip, typecheck, coverage and build passed; all 51 tests passed, including
  the 19 fixture/verdict tests. Runtime dependencies are unchanged. A sandbox loopback-bind
  restriction required the coverage run to be repeated with network permission.
- E2E: lint, Knip, typecheck, all 22 scenario/spec pairs and four setup tests passed.
  All 72 browser tests passed without retries against the rebuilt Docker Compose stack.
- Both images built, services became healthy, database readiness returned `ok`, and the
  local verification build displayed 1.0.4. All seven sample contracts passed `--check-only`.
- Independent review found no remaining issues after the incidental development dependency
  update was reviewed and documented. The original catalog inventory was preserved.

No Gradle/backend dependency changed, so the JVM suite was not rerun for this npm-only update.
Implementation and local verification are complete. Version 1.0.4 publication follows
[the application release process](app-releases.md).
