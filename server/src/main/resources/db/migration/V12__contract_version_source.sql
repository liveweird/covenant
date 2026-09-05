-- Source references & repo sync (Toadie's V21 shape, on the VERSION — the unit of text): a
-- version may point at the canonical copy of its document in a GitHub/GitLab repo. The
-- reference and the sync state are ROW state beside the document, which stays byte-exact.
ALTER TABLE contract_versions
    -- The https URL of the repo copy; NULL = no reference. Static guards only at write time
    -- (absolute https, no credentials, <= 2048 chars) — the public-host check runs at fetch time.
    ADD COLUMN source_url VARCHAR(2048),
    -- Epoch millis of the last repo -> Covenant sync; 0 = never (the password_changed_at idiom).
    -- A sync stamps updated_at to the SAME value, so updated_at > last_synced_at means
    -- "edited in Covenant since the last sync". A changed/cleared reference resets it to 0.
    ADD COLUMN last_synced_at BIGINT NOT NULL DEFAULT 0,
    -- The document text as pulled at the last sync — the baseline that attributes a later
    -- difference to a side (Covenant vs the repo). NULL = never synced.
    ADD COLUMN synced_content TEXT;
