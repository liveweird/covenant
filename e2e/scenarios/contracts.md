# Contracts (the core loop)

- **Spec**: [tests/contracts.spec.ts](../tests/contracts.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`); one throwaway user for the read-only test
- **Owns** (exclusive server-side state): its throwaway domains (`e2e-dom*`), systems (`e2e-sys*`),
  teams (`e2e-team*`), contracts (`e2e-petstore-*`, `e2e-readonly-*`) and user — all deleted by the
  end of the file

## Scenario: admin imports a contract, iterates a version through Save-anyway, compares, publishes, downloads and retires it

1. The admin creates a domain, a system inside it and a team through the registry pages.
2. On the Import page they paste an OpenAPI 3.1 document into the editor.
   - *Expected*: the Name and Version fields fill themselves from the document's title and declared
     version; the findings panel reports a clean document.
3. They pick the system and the team as owner and click Import.
   - *Expected*: "Contract created with its first version" with a link that opens the version page
     showing the document.
4. From the contract page they start a new version: the number defaults to the next patch; Minor
   bumps it to 1.1.0. They paste a copy of the document with a broken `$ref`.
   - *Expected*: the live findings name the missing schema.
5. They click Save draft.
   - *Expected*: the Save-anyway dialog names the blocking finding; confirming stores the version
     with `allowInvalid=true` and the version page shows "1 errors".
6. They open Compare versions.
   - *Expected*: the diff names the two versions and shows the changed `$ref` as a `+` line, with
     "+2 / −2 lines".
7. They open 1.0.0, click Propose then Activate.
   - *Expected*: Deprecate becomes the only move and the Edit document button is gone (the text is
     locked).
8. They download the document from the More menu.
   - *Expected*: the file is named `<system>__<contract>__1.0.0.yaml`.
9. They start another new version, paste the document with the required `Pet.name` property
   removed, and bump Minor (1.2.0).
   - *Expected*: the findings panel says it compared against active version 1.0.0 and lists the
     `CHANGED_RESPONSE` breaking change plus the blocking `BREAKING_WITHOUT_MAJOR_BUMP` finding.
10. They bump Major (2.0.0).
    - *Expected*: the blocking finding disappears while the breaking change stays listed as a
      note; they go back without saving.
11. Back on the contract page they read the History section.
    - *Expected*: it lists "Version 1.0.0: Proposed → Active", "Version 1.1.0 created" and
      "Version 1.0.0 imported" as localized lines.
12. Teardown through the rules: the 1.1.0 draft deletes from its row; deleting the contract is
   refused while 1.0.0 is active ("still has active or deprecated versions"); they deprecate and
   retire 1.0.0 (each behind a confirm), delete the contract, then the system, domain and team.
   - *Expected*: each step lands where described; the contract delete returns to the list.

## Scenario: a regular user reads a contract in the hierarchy and the list but gets no write actions

1. The admin creates a throwaway user, a domain/system/team, and a contract owned by the team
   through the New contract form.
   - *Expected*: the contract page shows the New version button to the admin.
2. The admin signs out; the user signs in and lands on the Hierarchy.
   - *Expected*: searching the contract's name shows it in the tree; its page has no New version
     button and its More menu offers Export but no Delete.
3. The user opens the Contracts list filtered to the name.
   - *Expected*: the row has no operations menu.
4. The admin signs back in and deletes the contract, the registries and the user.

## Not covered here (and why)

- **Ownership matrix, SemVer rules, HARD/SOFT gate, transitions, import statuses** — pinned by
  `ContractTest`/`ContractVersionTest`/`ContractImportTest` (server) and the page tests (SPA).
- **URL fetch and sync-from-source** — both need a public host; the SSRF guard is `UrlFetchTest`,
  the picker is `DocumentSourcePicker.test.tsx`, the sync modal `SyncVersionModal.test.tsx` and the
  server rules `ContractVersionTest` (source reference, sync).
