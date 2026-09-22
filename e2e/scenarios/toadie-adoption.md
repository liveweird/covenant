# Declared adoption from Toadie

Tests own a fixture GraphQL server, two connections to that source, uniquely named contracts
and their parent registries, and a throwaway reader. They never write to live Toadie. Cleanup
removes only their own Covenant records through the API and closes the fixture.

## Scenario: an administrator opts into parallel API declarations without changing architecture usage

1. Seed API and ODCS contracts against independent mappings to one fixture. Leave API adoption disabled.
2. Verify the API adoption endpoint says NOT_CONFIGURED.
3. Sign in as admin, edit that connection, enable Read declared adoption, and explicitly select the API adoption preset.
4. Wait for a successful scan and open the API contract.
5. Verify every parallel declaration, unknown value, all/specific environment, and provenance is visible.
   - *Expected*: v1 and v2 declarations stay separate; a v3 declaration without an architecture consumption edge remains visible with a discrepancy notice.
   - *Expected*: architecture usage still contains only its original provider and consumer.
6. Run accessibility checks and capture the view.

## Scenario: a reader sees dataset declarations and downloads all of them from lifecycle impact

1. Sign in as a throwaway regular reader and open the ODCS contract.
2. Verify the exact declared version, unknown declaration, environment scope and provenance, without edit controls.
3. Open the 1.x retirement impact view and verify it includes declared adoption.
4. Download the migration report.
   - *Expected*: the complete declarations include target/consumer/environment/value, provenance and source verification time.
   - *Expected*: source notes are escaped safely; credentials never enter the report.
5. Verify reading/exporting left the contract version in DRAFT.

## Scenario: a failed Toadie refresh retains declared adoption with a stale warning

1. Make the fixture return GraphQL errors.
2. Change only a display field on the dataset connection to schedule its refresh, retaining the mapping and previous snapshot.
3. Wait for the failed refresh, then open the ODCS contract as the reader.
   - *Expected*: the saved declaration remains visible with stale/error context; failure does not become zero adoption.

## Scenario: an absent optional adoption blueprint keeps architecture usage available

1. Start a separate fixture containing ordinary API architecture and no adoption blueprints.
2. Create an adoption-enabled connection and wait for a successful scan; link a throwaway contract.
3. Verify declaration availability BLUEPRINT_MISSING with a CURRENT connection cache.
4. Open the contract and verify the missing-blueprint explanation alongside existing architecture consumers.
5. Remove the owned contract hierarchy and connection and close the fixture.
