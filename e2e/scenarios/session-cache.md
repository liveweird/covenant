# Browser session cache

- **Spec**: [tests/session-cache.spec.ts](../tests/session-cache.spec.ts)
- **Actors**: the seed administrator and one throwaway regular user
- **Owns** (exclusive server-side state): one `E2E Session Cache` user and one
  `E2E Session Cache Mutation` user, each deleted by its test.

## Scenario: a new account never sees the previous account's cached notifications

1. The administrator creates a throwaway user and receives an unread notification count while
   signed in. The notification response is controlled by the test; no shared notification is
   created in the catalog.
2. The administrator's access token becomes invalid and their refresh token is rejected when
   opening the notification drawer makes an uncached authenticated SPA request.
   - *Expected*: the app shows the sign-in form without reloading the browser.
3. The throwaway user signs in through that form. Their notification request is held pending.
   - *Expected*: the new shell shows zero unread notifications while the request is pending;
     the administrator's cached count never appears under the new account.
4. The held request is released. The administrator signs in again and deletes the throwaway user.

## Scenario: a late draft result cannot appear after another tab switches accounts

1. The administrator starts generating an unsaved inferred draft. The test holds that response,
   which contains a distinctive private title.
2. A second browser tab signs in as its throwaway user and publishes that session to shared
   local storage while the first tab remains on the inference page.
   - *Expected*: the first tab drops the old account's form and pending result.
3. The old draft response arrives.
   - *Expected*: its content and Open in editor action do not appear under the new account.
4. The administrator signs in again and deletes the throwaway user.
