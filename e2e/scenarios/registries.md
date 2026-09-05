# Registries (Domain → System)

- **Spec**: [tests/registries.spec.ts](../tests/registries.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`); one throwaway user for the read-only test
- **Owns** (exclusive server-side state): its throwaway domains (`e2e-dom-*`), systems
  (`e2e-sys-*`) and user — all deleted by the end of the file

## Scenario: admin curates a domain and a system, moves the system, and hits the holds-systems refusal

1. The admin creates two domains through the Domains page's modal.
   - *Expected*: both appear in the filtered list.
2. On the Systems page they create a system, picking the first domain in the modal's Domain select.
   - *Expected*: the row shows the system with its domain's name.
3. They try to delete the first domain.
   - *Expected*: the confirm dialog shows "This domain still holds systems — move or delete them first."
4. They edit the system and pick the second domain (the move), then Save.
   - *Expected*: the row now shows the second domain.
5. They delete the first domain (now empty), then the system, then the second domain.
   - *Expected*: each disappears from its filtered list.

## Scenario: a regular user sees the read-only registries

1. The admin creates a throwaway user and signs out; the user signs in.
2. The user opens the Domains and Systems pages.
   - *Expected*: both render without a New button.
3. The admin signs back in and deletes the user.

## Not covered here (and why)

- **Validation, conflicts, per-domain name uniqueness** — pinned by `DomainTest`/`SystemTest`
  (server) and `Domains.test.tsx`/`Systems.test.tsx` (SPA).
