-- Compare each prerelease identifier using SemVer precedence before SQL pagination.
-- Stored prereleases are at most 100 characters. Numeric identifiers sort below text;
-- fixed-width decimal digits avoid integer overflow. Callers use COLLATE "C" for ASCII.
CREATE FUNCTION covenant_semver_prerelease_key(prerelease TEXT) RETURNS TEXT[]
LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT array_agg(
        CASE WHEN identifier ~ '^[0-9]+$'
            THEN '0' || lpad(identifier, 100, '0')
            ELSE '1' || identifier
        END ORDER BY position
    )
    FROM unnest(string_to_array(prerelease, '.')) WITH ORDINALITY AS parts(identifier, position)
$$;
