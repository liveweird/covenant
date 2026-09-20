-- Advisory lifecycle planning for release lines. Replacement IDs remain stored when the
-- referenced contract or line is later soft-deleted, so readers can report it as unavailable.
ALTER TABLE contract_release_lines
    ADD COLUMN deprecates_on VARCHAR(10),
    ADD COLUMN replacement_contract_id INTEGER REFERENCES contracts(id) ON DELETE RESTRICT,
    ADD COLUMN replacement_major INTEGER CHECK (replacement_major >= 0),
    ADD COLUMN migration_guide VARCHAR(8000),
    ADD CONSTRAINT ck_release_line_replacement_major_requires_contract
        CHECK (replacement_major IS NULL OR replacement_contract_id IS NOT NULL);

-- Scheduled reminders use the notification row itself as their durable delivery ledger.
-- Ordinary event notifications keep a null key and are unaffected by the unique constraint.
ALTER TABLE notifications ADD COLUMN deduplication_key VARCHAR(160);
CREATE UNIQUE INDEX uq_notifications_recipient_deduplication_key
    ON notifications(recipient_id, deduplication_key);
