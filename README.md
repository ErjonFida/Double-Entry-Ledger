# Double-Entry Ledger

[![CI](https://github.com/ErjonFida/Double-Entry-Ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/ErjonFida/Double-Entry-Ledger/actions/workflows/ci.yml)

Double-entry ledger API: accounts, deposits and transfers, where every movement of money is two entries that sum to zero.

**Stack:** Java 25, Spring Boot 4.1, Spring Security, Spring Batch, Spring Data JPA + Flyway, PostgreSQL, Redis, springdoc-openapi, JUnit, Mockito, Testcontainers, Docker, GitHub Actions, AWS (EC2, RDS), Caddy.

## Run locally

Needs JDK 25 and Docker.

```bash
docker compose up -d       # PostgreSQL on :5443, Redis on :6380
./mvnw spring-boot:run     # http://localhost:8094/swagger-ui.html
```

On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

Or run everything in Docker, with no JDK needed:

```bash
docker compose --profile app up -d --build   # http://localhost:8094/swagger-ui.html
```

## Test

```bash
./mvnw verify              # Testcontainers starts a real PostgreSQL in Docker
```

GitHub Actions runs the same on every push and pull request. Each push to `main` that passes also publishes the image as `ghcr.io/erjonfida/double-entry-ledger`, tagged `latest` and with the commit's SHA.

## Deploy

On AWS, one EC2 instance runs that image, Redis, and Caddy for HTTPS, and PostgreSQL runs on RDS, which only the instance can reach. [deploy/README.md](deploy/README.md) walks through it.

## API

Register a client to get an API key, then send it as an `X-API-Key` header on every other request. In Swagger UI, call `POST /api/clients`, then paste the key under **Authorize**. The key is shown only once.

| Method | Path | What |
|---|---|---|
| POST | `/api/clients` | Register: `{"name": "Shop"}`. Returns the API key. Needs no key |
| POST | `/api/accounts` | Open an account: `{"name": "Alice", "currency": "EUR"}`. EUR, USD or ALL |
| GET | `/api/accounts/{id}` | The account and its balance |
| GET | `/api/accounts/{id}/entries` | Its entries, newest first (`page`, `size` up to 100) |
| POST | `/api/accounts/{id}/deposits` | Pay money in: `{"amount": 10000}` |
| POST | `/api/transfers` | `{"fromAccountId": "...", "toAccountId": "...", "amount": 2500}` |

Amounts are whole minor units (cents). Errors come back as problem details (RFC 9457).

Each client may send 60 requests a minute, and each IP address may register 5 clients an hour. Past that, requests get a 429 with a `Retry-After` header saying how many seconds to wait.

The two requests that move money need an `Idempotency-Key` header, any string up to 100 characters. Generate one per transfer, and send the same one when you retry it:

- A retry with the same key and body returns the first response, and moves no more money.
- The same key with a different body returns 422.
- A request that failed (say, for lack of funds) is not stored, so it can be retried with the same key.
- Keys belong to the client, so two clients can use the same key without clashing.

## Reconciling a bank statement

Every deposit is money that should have reached the bank, so the bank's statement for a day should list each one once, with the same amount. A Spring Batch job checks that:

```bash
./mvnw -q package -DskipTests
java -jar target/ledger-0.0.1-SNAPSHOT.jar --spring.profiles.active=reconcile statement=statement.csv date=2026-11-20 currency=EUR
```

The statement is a CSV file with a `reference,amount` header and one line per payment received. The reference is the deposit's transfer id, and the amount is in minor units. Days are in UTC. Mismatches go to the `mismatch` table, and a count of each kind goes to the log:

| Kind | Meaning |
|---|---|
| `WRONG_AMOUNT` | The statement and the deposit have different amounts |
| `DUPLICATE` | The reference appears on the statement more than once |
| `NOT_IN_LEDGER` | No deposit in that currency has this reference |
| `MISSING_FROM_STATEMENT` | A deposit made that day has no line on the statement |

- The job reads the file 1,000 lines at a time. Each chunk's lines, its mismatches, and how far Spring Batch has read are saved in one transaction.
- Each line is stored under the key (reconciliation, reference). A line whose reference is already stored inserts nothing, and that is how duplicates are found, even in different chunks.
- A statement, day and currency are reconciled once. Running the same again fails with `JobInstanceAlreadyCompleteException`.
- The process exits with 0 on success and 1 on failure, so a scheduler can run it.

If the process is killed partway through, Spring Batch still records the run as started, so running the same command again fails with `JobExecutionAlreadyRunningException`. Once the killed process is really gone, add `--recover` to the command. That marks the run as failed, and Spring Batch restarts it from the last chunk it saved. Lines from the chunk that was cut off were rolled back with it, so they are read again and counted once.

## How it works

- Entries are only ever inserted. A transfer writes one negative and one positive entry.
- Each customer account also stores its balance, updated in the same transaction as the entries.
- A deposit is a transfer from the currency's cash account. Cash accounts belong to no client, so money only leaves them through a deposit.
- A cash account's balance is not stored. It is the sum of the account's entries, so a deposit updates only the account it pays into. Storing it made every deposit in a currency wait for the same row; see the [deposit experiment](#deposit-experiment).
- Each client sees only its own accounts, and both accounts in a transfer must be the caller's. Another client's account answers 404, the same as one that does not exist.
- API keys are 256 random bits, stored only as SHA-256 hashes. A fast hash is enough for random keys; slow hashes like bcrypt are for passwords people can guess.
- Database `CHECK`s stop a customer's balance from going below zero, and stop a cash account from storing a balance at all.
- A transfer starts by inserting its row with `ON CONFLICT (client_id, idempotency_key) DO NOTHING`. If two retries arrive at once, PostgreSQL makes the second wait until the first commits, and it then returns the first one's transfer. A test sends 20 copies at once and checks the money moved once.
- A transfer updates the account with the lower id first, whichever way the money goes. Two opposite transfers then lock the accounts in the same order, so they cannot deadlock.
- Rate limits are counted in Redis by a small Lua script, which increments the counter and sets its expiry in one atomic step. The window starts at a client's first request, so up to twice the limit can fit across the end of one window and the start of the next.
- If Redis is down, requests go through and a warning is logged, so an outage cannot take the ledger down. Redis calls time out after 500 ms, which is the extra latency while it lasts.
- Behind a proxy, such as Caddy in the AWS deploy, the client address comes from `X-Forwarded-For`. It is trusted only when the connection itself comes from a private address, so a client connecting directly cannot fake one to get round the registration limit.
- A seeded test runs 10,000 random transfers, then checks that all entries sum to zero and every customer account's balance equals the sum of its entries.

## Deadlock experiment

200 threads send 1-cent transfers between the same two accounts, half in each direction, for 10 seconds. Each strategy gets a warm-up round and then three timed rounds, and the table shows the medians. The app uses its default pool of 10 database connections, and PostgreSQL its default 1-second `deadlock_timeout`.

```bash
./mvnw test -Dtest=TransferBenchmark   # about 3 minutes, also writes target/benchmark.md
```

| Strategy | Transfers/s | Failed | Deadlocks per 100 transfers | Median latency (ms) | p99 latency (ms) |
|---|--:|--:|--:|--:|--:|
| Sender first | 0.3 | 86% | 625 | 10,042 | 15,067 |
| Sender first, retry on deadlock | 0.4 | 0% | 540 | 12,060 | 12,070 |
| Lower id first | 719 | 0% | 0 | 11 | 5,034 |

- **Sender first:** a transfer from A to B locks A then B, and one from B to A locks B then A. Almost every such pair deadlocks, and PostgreSQL waits a second before cancelling one of them.
- **Retry on deadlock:** no transfer fails, but each one deadlocks about five times before it gets through, so throughput stays under one per second.
- **Lower id first:** both directions lock the accounts in the same order. Transfers queue for the two rows but never deadlock. This is what the app does.
- **Latency:** 200 clients share 10 connections, and the pool serves them unevenly. Half the id-order transfers finished within 11 ms, but 1 in 100 waited 5 seconds. See [Tail latency](#tail-latency).
- **Variance:** across three runs, lower id first managed 719 to 1,137 transfers/s, and sender first 0.3 to 0.7 with 79 to 89% failing. The sender-first strategies complete only a few transfers per round.

### Tail latency

A later run of the same benchmark added a fourth strategy: lower id first, with clients queueing for a connection in the order they arrive, through a fair semaphore the size of the pool. From that run:

| Strategy | Transfers/s | Median latency (ms) | p99 latency (ms) |
|---|--:|--:|--:|
| Lower id first | 1,000 | 8 | 3,449 |
| Lower id first, queueing in order for a connection | 925 | 209 | 293 |

- **The average is fixed:** with 200 clients and about 1,000 transfers a second, a transfer spends about 200 ms waiting and running on average, whatever order the queue is served in (Little's law).
- **Why the tail is long:** HikariCP does not serve waiting threads in order. A thread asking just as a connection is returned can take it ahead of threads that have waited longer. So most transfers barely wait, and a few wait seconds.
- **Queueing in order:** the same wait is shared evenly. p99 fell 12 times, the median rose 26 times, and throughput stayed within the usual spread between runs.
- **Lowering the average itself:** that needs more throughput on the two contended rows, or fewer clients contending for them.

Measured with Docker Desktop on Windows 11.

## Deposit experiment

Every deposit is a transfer from the currency's cash account. While that account stored its balance, every deposit in a currency updated, and so locked, the same row. Deposits into different customers' accounts still queued behind each other.

The benchmark has 200 clients each deposit 1 cent at a time into an account of their own, for 10 seconds, through `LedgerService`. It uses a warm-up round and three timed rounds, and reports the medians. It then checks that the 200 accounts hold exactly one cent per completed deposit.

```bash
./mvnw test -Dtest=DepositBenchmark   # about 1 minute, also writes target/deposit-benchmark.md
```

| Cash account | Deposits/s | Median latency (ms) | p99 latency (ms) |
|---|--:|--:|--:|
| Balance stored, updated by every deposit | 525 | 362 | 1,659 |
| Balance not stored, the sum of its entries | 2,202 | 85 | 476 |

- **Throughput:** 4.2 times higher, now that deposits only lock the account they pay into.
- **Latency:** it fell with it. 200 clients share 10 connections, so the average wait is 200 divided by the throughput (Little's law), and higher throughput shortens it.
- **Still shared:** every deposit still writes an entry for the cash account, and the foreign key check takes a lock on its row. That lock is shared, so deposits don't wait for each other.
- **Runs:** each row is one run of the benchmark, the first before the change and the second after it. The gap is far larger than the variation between runs of the other benchmarks.

Measured with Docker Desktop on Windows 11.

## Reconciliation experiment

The benchmark puts 1,000,000 deposits in the ledger for one day. Reconciliation reads nothing but the transfer rows, so it inserts those directly with SQL. Then it writes that day's statement with 1,000 errors in it:

- 250 deposits left out
- 250 at the wrong amount
- 250 listed twice
- 250 lines with references that match nothing

The repeated and unknown lines go in at random places, so a duplicate almost always lands in a different chunk from the first copy. Each chunk size gets a warm-up run and three timed runs on the 1,000,250-line file. The table shows the medians, with Seconds covering the whole job.

```bash
./mvnw test -Dtest=ReconciliationBenchmark   # about 11 minutes, also writes target/reconciliation-benchmark.md
```

| Chunk size | Seconds | Lines/s |
|--:|--:|--:|
| 100 | 91.4 | 10,938 |
| 1,000 | 33.1 | 30,265 |
| 5,000 | 37.5 | 26,678 |

- **Accuracy:** every run, warm-ups included, reported exactly the 1,000 injected errors and nothing else.
- **Chunk size:** each chunk ends in a commit. A commit waits for PostgreSQL to flush its log to disk, and Spring Batch also updates its own progress rows. At 100 lines a chunk that happens 10,000 times, and the job takes almost three times as long. At 1,000 lines commits stop mattering, so 5,000 is no faster. The job uses 1,000.
- **Planner statistics:** in the first version, the step that finds missing deposits once ran for over 8 minutes instead of 3 seconds. PostgreSQL had planned the query before autovacuum analysed the million new statement lines; the analysis finished 4 seconds after the query started. The step now runs `ANALYZE statement_line` first, and it has taken 2 to 6 seconds in every run since.
- **Variance:** the timed runs took 67 to 104 seconds at 100 lines a chunk, 24 to 37 at 1,000 and 36 to 38 at 5,000. Commits wait on the disk, and Docker Desktop's disk is uneven. The warm-up at 100 (47 seconds) beat all three timed runs, so warm-up does not explain the spread.

**Kill and restart:** a test in `./mvnw verify` runs the job as its own JVM on a 200,050-line statement with 200 injected errors, and kills it once 20,000 lines are saved.

- **The kill:** in one run, the job died with 23,000 lines saved.
- **The restart:** running the same command again was refused. With `--recover`, the restart read the remaining 177,050 lines.
- **The result:** together, the two runs read every line exactly once, and the job reported exactly the 200 injected errors.
- **The control:** with the reader's saved position turned off, the restart read all 200,050 lines again, and the test fails.

Measured with Docker Desktop on Windows 11.

## Roadmap

- [x] 1. Core ledger: accounts, deposits, transfers, entry history, the balance invariant test
- [x] 2. Retries and deadlocks: idempotency keys, the opposing-transfers experiment
- [x] 3. Client access: API keys with Spring Security, idempotency keys scoped to each client, rate limiting in Redis
- [ ] 4. Deploy: Docker image (done), CI (done), AWS with EC2 and RDS
- [x] 5. Batch reconciliation of bank statements with Spring Batch: the job, a 1,000,000-line run, a kill and restart
