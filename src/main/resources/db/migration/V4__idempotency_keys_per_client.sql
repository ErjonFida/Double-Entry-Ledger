ALTER TABLE transfer ADD COLUMN client_id uuid REFERENCES client (id);
ALTER TABLE transfer DROP CONSTRAINT transfer_idempotency_key_key;
ALTER TABLE transfer ADD UNIQUE (client_id, idempotency_key);
