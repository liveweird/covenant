# Toadie contract usage

The suite owns an HTTP GraphQL fixture, an encrypted Covenant connection, and its own contract
hierarchy. It never changes the live Toadie catalog or existing Covenant connections. Cleanup
uses the ordinary contract and connection delete APIs and closes only its fixture server.

## Scenario: an administrator connects Toadie and a contract owner links multiple APIs to view declared usage

1. Sign in as administrator and create a Toadie connection through the SPA using the fixture URL and key.
2. Verify the response reveals only hasApiKey and wait for the real backend GraphQL refresh.
3. Open the owned contract, select the connection and link two API entities through the picker.
4. Verify providers and consumers are deduplicated, systems/teams link to Toadie, and version/line remain unknown.
5. Check accessibility of the usage panel and attach a screenshot.

## Scenario: a failed Toadie refresh keeps the last observed consumers visible and marks usage stale

1. Make the owned GraphQL fixture return a partial/error response.
2. Respect the connection refresh cooldown, then request refresh from the contract page.
3. Verify stale/error state appears and the previously observed consumer remains visible.
4. Verify the page does not turn the failure into a successful empty-usage result.
