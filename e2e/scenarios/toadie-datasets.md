# Toadie dataset usage

This suite owns one GraphQL fixture, two Covenant connections to that fixture, and separate
API and ODCS contract hierarchies. It removes its records through the ordinary APIs and
never changes the live Toadie catalog.

## Scenario: API and ODCS contracts use separate mappings to the same Toadie instance

1. Create two connections through the SPA, choosing the API and dataset mapping presets respectively.
2. Verify both refresh successfully against the same upstream URL and keep their keys write-only.
3. Link an OpenAPI contract to an API entity; confirm datasets are absent from its picker.
4. Link an ODCS contract to two dataset entities; confirm API entities are absent from its picker.
5. Verify one service producing both datasets is deduplicated and can be both provider and consumer.
6. Verify version and release-line adoption remain unknown, database-only dependencies are excluded,
   and source links point to the dataset identity.
7. Verify the history label includes datasets, check accessibility, attach a screenshot, and revisit the API contract to confirm its usage is unchanged.
8. Verify the real GraphQL client reads both configured target blueprints without scanning resources or adoption entities.
