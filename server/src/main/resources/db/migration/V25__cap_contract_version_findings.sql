-- V10 allowed 500 substantive findings plus a truncation marker. Bring those legacy snapshots
-- under the corrected 500-total-entry bound and keep the denormalized badge counts consistent.
WITH capped AS (
    SELECT id,
           COALESCE(
               (
                   SELECT jsonb_agg(entry ORDER BY ordinal)
                   FROM jsonb_array_elements(findings::jsonb) WITH ORDINALITY AS entries(entry, ordinal)
                   WHERE ordinal <= 499
               ),
               '[]'::jsonb
           ) || jsonb_build_array(
               jsonb_build_object(
                   'severity', 'INFO',
                   'source', 'SYSTEM',
                   'code', 'FINDINGS_TRUNCATED',
                   'message', 'Additional findings were omitted while correcting the 500-entry cap (including this marker)'
               )
           ) AS findings
    FROM contract_versions
    WHERE jsonb_typeof(findings::jsonb) = 'array'
      AND jsonb_array_length(findings::jsonb) > 500
), summarized AS (
    SELECT id,
           findings,
           (SELECT count(*) FROM jsonb_array_elements(findings) AS finding WHERE finding ->> 'severity' = 'ERROR') AS errors,
           (SELECT count(*) FROM jsonb_array_elements(findings) AS finding WHERE finding ->> 'severity' = 'WARN') AS warnings,
           (SELECT count(*) FROM jsonb_array_elements(findings) AS finding WHERE finding ->> 'severity' = 'INFO') AS infos
    FROM capped
)
UPDATE contract_versions AS versions
SET findings = summarized.findings::text,
    check_errors = summarized.errors,
    check_warnings = summarized.warnings,
    check_infos = summarized.infos
FROM summarized
WHERE versions.id = summarized.id;
