# Avro evolution examples

These three AsyncAPI 3.0 documents exercise Avro reader/writer compatibility for a
`receive` operation. The application is the reader: compare the candidate reader
schema with data written under the published 1.0.0 baseline.

1. Save `asyncapi-avro-baseline.yaml` as version 1.0.0 of an AsyncAPI contract and
   make it ACTIVE.
2. Check `asyncapi-avro-compatible.yaml` as version 1.1.0. The reader promotes
   `amount` from `int` to `long` and gives its new `note` field a default; no Avro
   breaking finding is expected.
3. Check `asyncapi-avro-incompatible.yaml` as version 1.1.0. Its new reader field
   `approvalCode` has no default, so old records cannot be read. Expect an Avro
   breaking finding and `BREAKING_WITHOUT_MAJOR_BUMP`. A 2.0.0 candidate would
   report the break as informational.

Use the version page's compatibility report to compare each candidate against the
baseline in both directions. The [catalog loader](../load.py) does not load these
examples: it deliberately creates only independent DRAFT versions, while these
examples require a published baseline. All addresses are fictional and are not
contacted by the checker.
