-- A cash account's balance is the sum of its entries and is no longer stored. Updating it made every deposit in a
-- currency wait for the same row
UPDATE account SET balance = 0 WHERE cash;

ALTER TABLE account ADD CHECK (NOT cash OR balance = 0);
