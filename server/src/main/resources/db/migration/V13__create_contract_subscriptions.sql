-- Contract subscriptions (milestone 3): a user FOLLOWS a contract and receives an in-app
-- notification for every event on it. A pure membership join like team_members — hard-delete
-- (unfollow removes the row; no history worth keeping), composite PK, CASCADE on the user
-- (a deleted account follows nothing), RESTRICT on the contract (contracts only soft-delete).
CREATE TABLE contract_subscriptions (
    contract_id INTEGER NOT NULL REFERENCES contracts(id) ON DELETE RESTRICT,
    user_id     BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  BIGINT  NOT NULL,
    PRIMARY KEY (contract_id, user_id)
);

-- The "what do I follow" side and the per-user cleanup path.
CREATE INDEX idx_contract_subscriptions_user_id ON contract_subscriptions(user_id);
