# Notifications (following a contract)

- **Spec**: [tests/notifications.spec.ts](../tests/notifications.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`); one throwaway follower
- **Owns** (exclusive server-side state): its throwaway domain (`e2e-dom-n*`), system (`e2e-sys-n*`),
  team (`e2e-team-n*`), contract (`e2e-followed-*`) and user — all deleted by the end

## Scenario: a follower's bell announces a new version, opens it, and mark-all clears the badge

1. The admin creates a throwaway user, a domain, a system and a team, then a contract owned by the
   team through the New contract form.
2. The admin signs out; the follower signs in and opens the contract page.
   - *Expected*: the header bell reads "Notifications (0 unread)"; the page offers "Follow · 0".
3. The follower clicks Follow.
   - *Expected*: the button now reads "Following · 1".
4. The admin signs back in and saves a first version (1.0.0) of the contract.
   - *Expected*: the admin's own bell still reads 0 unread — the actor is never notified.
5. The follower signs in and opens the bell.
   - *Expected*: the badge reads 1; the drawer lists "… created version 1.0.0 of <contract>".
6. The follower clicks the notification's Open action.
   - *Expected*: the version page opens and the badge drops to 0 (opening counts as seeing).
7. The follower marks the notification unseen, then clicks Mark all as seen.
   - *Expected*: the badge goes back to 1, then to 0.
8. Teardown: the admin deletes the draft version and the contract, then the system, domain, team
   and the follower.

## Not covered here (and why)

- **Recipient-only rules, the wasSeen filter, soft delete, the fan-out matrix (every event kind,
  the breaking push, unfollow)** — pinned by `NotificationRoutesTest` and `ContractSubscriptionTest`
  (server) and `NotificationsButton.test.tsx` / `ContractDetails.test.tsx` (SPA).
