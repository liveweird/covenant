# List mutation refresh races

The suite owns uniquely named records and any required parent registries, created through the
real API. It signs in as the seed administrator and never changes existing user records. Its Toadie connection is disabled
and never contacts an upstream service. Cleanup releases held responses and deletes only
owned records through normal APIs, in dependency order, even when an assertion fails.

## Scenario: a pending Users filter cannot restore a deleted user

1. Create a uniquely named user and any required parents, then sign in as administrator.
2. Load a settled list containing it; change the filter/search (or Toadie sort) to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter/sort.
6. Remove the owned records through the API and release the intercepted request.

## Scenario: a pending Teams filter cannot restore a deleted team

1. Create a uniquely named team and any required parents, then sign in as administrator.
2. Load a settled list containing it; change the filter/search (or Toadie sort) to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter/sort.
6. Remove the owned records through the API and release the intercepted request.

## Scenario: a pending Domains filter cannot restore a deleted domain

1. Create a uniquely named domain and any required parents, then sign in as administrator.
2. Load a settled list containing it; change the filter/search (or Toadie sort) to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter/sort.
6. Remove the owned records through the API and release the intercepted request.

## Scenario: a pending Systems filter cannot restore a deleted system

1. Create a uniquely named system and any required parents, then sign in as administrator.
2. Load a settled list containing it; change the filter/search (or Toadie sort) to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter/sort.
6. Remove the owned records through the API and release the intercepted request.

## Scenario: a pending Contracts search cannot restore a deleted contract

1. Create a uniquely named contract and any required parents, then sign in as administrator.
2. Load a settled list containing it; change the filter/search (or Toadie sort) to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter/sort.
6. Remove the owned records through the API and release the intercepted request.

## Scenario: a pending Toadie connections sort cannot restore a deleted connection

1. Create a uniquely named connection and any required parents, then sign in as administrator.
2. Load a settled list containing it; change the filter/search (or Toadie sort) to start a new query.
3. Hold the first real response for that query while its previous rows remain actionable.
4. Delete the record through its row menu and confirmation; verify DELETE 204 and GET 404.
5. Deliver the pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without reloading or changing the selected filter/sort.
6. Remove the owned records through the API and release the intercepted request.


## Scenario: a pending Feature Flags filter cannot undo a successful toggle

1. Create a throwaway user with MFA disabled; sign in as the seed administrator.
2. Filter Feature Flags to that user and verify its MFA switch is off.
3. Change to another matching name filter and hold its first real response.
4. Toggle MFA on while the previous row remains visible; verify PUT 204 and persisted MFA enabled.
5. Release the response captured before the update.
   - *Expected*: the switch is enabled and on, with the selected name filter preserved.
6. Release the intercepted response and delete only the throwaway user through the API.
