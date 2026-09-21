ALTER TABLE toadie_connections
    ADD COLUMN remote_revision BIGINT
        CHECK (remote_revision IS NULL OR remote_revision >= 0);
