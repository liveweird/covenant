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

## Scenario: a changed upstream revision restarts the scan and publishes one coherent observation

1. Respect the connection refresh cooldown and arm the fixture to rename one service after its first service page without changing entity IDs or counts.
2. Request a manual refresh from the contract page and let the fixture expose a new revision during the scan.
3. Verify Covenant restarts the scan and the UI publishes the renamed service as one current, coherent observation with the same entity link and row count.
4. Inspect fixture request evidence to verify multiple scan starts and a complete final revision across blueprint and entity collections.

## Scenario: continuously changing upstream revisions fail refresh and retain the previous observation

1. Respect the connection refresh cooldown and make the fixture change its revision after every response.
2. Request a manual refresh from the contract page and allow Covenant's bounded restart policy to exhaust.
3. Verify stale/error state appears and the previously published renamed consumer remains visible.
4. Verify the page does not turn unknown usage into an empty result, and fixture evidence shows multiple revisions and restart attempts.

## Scenario: a failed Toadie refresh keeps the last observed consumers visible and marks usage stale

1. Respect the connection refresh cooldown, then make the owned GraphQL fixture return an error response.
2. Request refresh from the contract page.
3. Verify stale/error state appears and the previously published renamed consumer remains visible.
4. Verify the page does not turn the failure into a successful empty-usage result.
