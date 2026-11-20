-- A reconciliation is one Spring Batch job instance: one statement file, day and currency
CREATE TABLE statement_line (
    reconciliation_id bigint NOT NULL REFERENCES batch_job_instance (job_instance_id),
    reference         text   NOT NULL,
    amount            bigint NOT NULL,
    PRIMARY KEY (reconciliation_id, reference)
);

CREATE TABLE mismatch (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reconciliation_id bigint      NOT NULL REFERENCES batch_job_instance (job_instance_id),
    kind              varchar(30) NOT NULL
        CHECK (kind IN ('DUPLICATE', 'NOT_IN_LEDGER', 'WRONG_AMOUNT', 'MISSING_FROM_STATEMENT')),
    reference         text        NOT NULL,
    statement_amount  bigint,
    ledger_amount     bigint
);

CREATE INDEX mismatch_reconciliation ON mismatch (reconciliation_id);
