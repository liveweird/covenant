# Sample contracts

Seven reusable, fictional commerce contracts for exploring the hierarchy, reader, source
editor, findings, and catalog filters. The landscape follows Toadie's
`sample-data/catalog-info.yaml` approach: realistic clean documents alongside clearly
identified deliberate problems. These are manual-test fixtures, not production integrations.

## Load into a running Covenant

Start the stack with `docker compose up --build`, then run from the repository root:

```bash
python3 samples/contracts/load.py --base-url http://localhost:8082
```

Python 3.10 or newer is required; no Python packages need to be installed. The loader prompts
for the password of `admin@covenant.local` (the local compose demo uses `changeme`). Use
`--email` for another administrator. Covenant normally runs on **8082**; 8081 is Toadie's
port. The loader verifies the target's exposed OpenAPI title before sending credentials.

For unattended local testing, supply the password through an environment variable:

```bash
COVENANT_PASSWORD=changeme python3 samples/contracts/load.py --base-url http://localhost:8082
```

This example uses only the burned demo password. For a different password, set
`COVENANT_PASSWORD` through your usual secret-injection mechanism; `--password-env NAME`
selects another variable. Passwords and tokens are never written to the sample files. Accounts
requiring email MFA are not supported by this loader. Non-loopback destinations require HTTPS
and an explicit `--allow-remote`; HTTP redirects are refused.

To authenticate and check all documents without creating catalog records:

```bash
python3 samples/contracts/load.py --base-url http://localhost:8082 --check-only
```

`--dry-run` is an alias for `--check-only`; it validates document expectations, not whether
existing registry names or stored versions conflict. This local-development loader needs
`/openapi/documentation.yaml` to be exposed by the server.

## What gets created

- Domain: **Sample - Commerce**.
- Systems: **Sample - Orders** and **Sample - Analytics**.
- Owning team: **Sample - Commerce team**, initially containing the administrator who loads it.
- Seven contracts, each with a **1.0.0 DRAFT** version containing the corresponding file's exact
  UTF-8 text. All are editable so their deliberate problems can be fixed in the editor.

The loader checks **all seven** documents before creating any catalog records. Clean and
warning-only documents use strict saves; error specimens explicitly use `allowInvalid=true`.
Unparseable documents and type mismatches cannot be waived and are not included. An unavailable
checker fails the loader's expectations so incomplete validation cannot masquerade as clean.

No users, environment credentials, live connection targets, or subscriptions are created.
Server addresses in the documents use reserved example domains; loading does not contact them.

## Expected findings

| File | Format | Expected result | Deliberate issue |
| --- | --- | --- | --- |
| [openapi-orders-clean.yaml](specs/openapi-orders-clean.yaml) | OpenAPI 3.0.3 | Clean | Order creation/retrieval, schemas, examples, problem responses |
| [asyncapi-orders-clean.yaml](specs/asyncapi-orders-clean.yaml) | AsyncAPI 3.1.0 | Clean | Order lifecycle events with a CloudEvents-style envelope |
| [odcs-order-analytics-clean.yaml](specs/odcs-order-analytics-clean.yaml) | ODCS 3.1.0 | Clean | Order facts, column metadata, ownership and support |
| [openapi-orders-soft-error.yaml](specs/openapi-orders-soft-error.yaml) | OpenAPI 3.0.3 | Errors | Undefined `MissingOrder` schema reference (`invalid-ref`, `OAS_PARSE`) |
| [asyncapi-orders-soft-error.yaml](specs/asyncapi-orders-soft-error.yaml) | AsyncAPI 3.1.0 | Errors | Numeric payload `type: 42` instead of a schema type name |
| [odcs-order-analytics-soft-error.yaml](specs/odcs-order-analytics-soft-error.yaml) | ODCS 3.1.0 | Errors | Missing required contract `id` (`ODCS_SCHEMA`) |
| [openapi-orders-lint-warning.yaml](specs/openapi-orders-lint-warning.yaml) | OpenAPI 3.0.3 | Warning only | Missing `info.contact` (`info-contact`) |

Validated against the local stack on 2026-09-06: the clean files each had **0 errors, 0 warnings,
0 infos**; the error files had **2, 4, and 1 errors**, respectively; the warning file had **1
warning and no errors**. Several engines can report the same underlying defect, and counts may
change with validator upgrades. The loader checks severity classes and complete validation,
not exact counts or wording. INFO findings are allowed for every expectation.

Open [the catalog](http://localhost:8082/contracts), find `Sample -`, then open a contract's
1.0.0 version. Compare the Reader and Source views; the findings panel shows the intentional
issues. The manifest's descriptions also explain them. These DRAFT-only samples do not
exercise published-baseline breaking-change detection.

## Repeat runs, edits, and cleanup

`manifest.json` is the file-to-contract map; it declares type, version, system, and expected
severity class. Domain, team, system, and contract descriptions carry the marker
`Reusable Covenant sample data (samples/contracts)`.

Repeated runs reuse matching records and skip versions whose content is unchanged. On a fresh
load the summary is **created 18, skipped 0** (four registry records, seven contracts, seven
versions); the next run is **created 0, skipped 18**. The loader refuses to overwrite mismatched
descriptions, types, ownership, the team roster, existing version text, lifecycle, or source
reference. An existing sample version must still be a DRAFT without a source URL. The loader
does not remove additional versions or undo lifecycle transitions. Run one loader at a time.

If you edit a sample in the UI, preserve it by renaming that contract before reloading, or
delete the sample contract through the UI to restore the original on the next load. Published
versions may prevent contract deletion under the normal lifecycle rules. To evolve the files
without discarding existing text, change the file and its declared version together with the
manifest version to a higher SemVer. A changed manifest description will also require you to
reconcile the existing contract description deliberately.

Loading is not one database transaction. A connection failure or conflict can leave a partial
load; completed records remain, and a rerun can resume when they still match. If a stored sample's
checker report is incomplete, the loader rechecks its unchanged content after the checker
recovers. The loader never deletes or overwrites document text to repair a conflict. To clear
the landscape manually, delete
the sample contracts first, then their systems, domain, and team, using the normal application
actions. Do not reset the whole database just to remove these samples.

## Loader checks

```bash
python3 -m unittest discover -s samples/contracts -p 'test_load.py'
```

The loader tests use fake API responses and do not access your Covenant database.
After editing a spec or upgrading a validator, run `--check-only` against the full stack and
confirm its manifest expectation still describes the observed findings.
