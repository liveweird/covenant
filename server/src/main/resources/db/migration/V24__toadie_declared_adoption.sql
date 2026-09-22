-- Optional read-only declared adoption mapping and its verified snapshot projection.
ALTER TABLE toadie_connections
    ADD COLUMN adoption_blueprint VARCHAR(100),
    ADD COLUMN adoption_kind VARCHAR(40),
    ADD COLUMN adoption_consumer_relation VARCHAR(100),
    ADD COLUMN adoption_target_relation VARCHAR(100),
    ADD COLUMN adoption_environment_relation VARCHAR(100),
    ADD COLUMN adoption_value_property VARCHAR(100),
    ADD COLUMN adoption_status_property VARCHAR(100),
    ADD COLUMN adoption_declared_by_property VARCHAR(100),
    ADD COLUMN adoption_verified_at_property VARCHAR(100),
    ADD COLUMN adoption_notes_property VARCHAR(100),
    ADD COLUMN snapshot_adoption_availability VARCHAR(40) NOT NULL DEFAULT 'NOT_SCANNED',
    ADD COLUMN snapshot_adoption_environment_blueprint VARCHAR(100);

ALTER TABLE toadie_snapshot_entities
    ADD COLUMN scalar_properties TEXT;
