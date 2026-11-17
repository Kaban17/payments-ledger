# payments-ledger

A double-entry payments ledger REST API built with **Java 21, Javalin, jOOQ, PostgreSQL and Redis**, developed test-first with **Spock + Testcontainers**.

The core goals are:

- **Double-entry bookkeeping.** Every transfer writes two ledger entries that sum to zero, so money is never created or destroyed.
- **Idempotent transfers.** Retrying a request with the same `Idempotency-Key` never moves money twice. Redis serves replays quickly, and a PostgreSQL unique constraint is the source of truth.
- **Concurrency-safe balances.** Account rows are locked in a fixed order, so concurrent transfers can't deadlock or overdraw an account.

> **Status:** all roadmap items are implemented: accounts, deposits, transfers, Redis-backed idempotency, paginated entry history, and CI. See the [roadmap](#roadmap).

## Tech stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| HTTP | Javalin 7 + Jackson |
| SQL | jOOQ (classes generated from the Flyway migrations) |
| Migrations | Flyway |
| Database | PostgreSQL 16 |
| Cache / idempotency | Redis 7 (Jedis) |
| Connection pool | HikariCP |
| Tests | Spock 2.5 (Groovy 5) + Testcontainers |
| Build | Gradle (Kotlin DSL) |

## Getting started

### Prerequisites

- Any JDK 17+ to run Gradle. The build compiles with a Java 21 toolchain, and if none is installed Gradle downloads one automatically.
- Docker, **running** (for local Postgres/Redis and for the tests)

### Run the tests

```bash
./gradlew build
```

The tests start their own Postgres and Redis containers with Testcontainers, so you don't need to run docker-compose first.

### Run the app locally

```bash
docker compose up -d --wait   # Postgres on :5433, Redis on :6379
./gradlew run
curl http://localhost:8080/health
# {"status":"ok","postgres":"up","redis":"up"}
```

Flyway applies the migrations automatically on startup.

### Configuration

All settings come from environment variables:

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/ledger` |
| `DB_USER` | `ledger` |
| `DB_PASSWORD` | `ledger` |
| `REDIS_URL` | `redis://localhost:6379` |
| `PORT` | `8080` |
| `LEDGER_DB_PORT` | `5433` (host port docker-compose maps Postgres to) |

### Troubleshooting

- **`UnknownHostException: localhost` on macOS:** a system SOCKS proxy (often set by a VPN app) is active, and its bypass list doesn't include `localhost`. The Gradle `test` and `run` tasks already pass `-DsocksNonProxyHosts=localhost|127.*|…`. If you start the app or tests another way (e.g. the IDE's own runner), add that JVM option there too.
- **Tests fail during container setup:** make sure Docker is running.

## Data model

All amounts are stored as `BIGINT` in **minor units** (e.g. cents). Floating point is never used.

```
accounts        id, currency, balance (≥ 0 unless is_system), is_system, created_at
transfers       id, idempotency_key (UNIQUE), request_hash,
                from_account_id, to_account_id, amount (> 0), currency, created_at
ledger_entries  id, transfer_id, account_id, amount (signed, ≠ 0), created_at
```

- `ledger_entries` is **append-only**. A transfer of 100 from A to B writes `A: -100` and `B: +100`.
- `accounts.balance` is a cached copy of `SUM(ledger_entries.amount)` for that account, updated in the same transaction as the entries. Tests check that the two always agree.
- Money enters the system through an **EXTERNAL** system account (`is_system = true`, at most one per currency, created on first use), so deposits are double-entry too. System accounts are the only ones allowed a negative balance.
- The database enforces these rules itself (check, unique and foreign-key constraints), as a safety net under the service code. `SchemaConstraintsSpec` covers each one.

## API

All request and response bodies are JSON with camelCase field names. Amounts are integers in minor units, currencies are three uppercase letters, and timestamps are ISO-8601 strings. `POST /transfers` and `POST /deposits` require an `Idempotency-Key` header (1 to 255 characters).

### Create an account

```bash
curl -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"currency": "EUR"}'
```

`201 Created`:

```json
{"id": "<uuid>", "currency": "EUR", "balance": 0, "createdAt": "2026-01-01T12:00:00Z"}
```

### Get an account

```bash
curl http://localhost:8080/accounts/<account-id>
```

Returns `200` with the same shape as above, or `404 not_found`.

### Deposit

```bash
curl -X POST http://localhost:8080/deposits \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-001' \
  -d '{"accountId": "<account-id>", "amount": 10000, "currency": "EUR"}'
```

The money comes from the EXTERNAL system account for that currency, which is created on first use. `amount` must be greater than 0, and depositing into a system account is rejected with `400`. The response is a transfer (below) from the system account to the target account.

### Transfer

```bash
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: tr-001' \
  -d '{"fromAccountId": "<uuid>", "toAccountId": "<uuid>", "amount": 2500, "currency": "EUR"}'
```

`201 Created`:

```json
{
  "id": "<uuid>",
  "fromAccountId": "<uuid>",
  "toAccountId": "<uuid>",
  "amount": 2500,
  "currency": "EUR",
  "createdAt": "2026-01-01T12:00:00Z"
}
```

`amount` must be greater than 0 and the two accounts must differ.

### Replay

Sending the same request again with the same `Idempotency-Key` returns `200 OK` with the original transfer body and moves no money. Reusing the key with a different request (different accounts, amount or currency) returns `409 idempotency_key_reused`. Key order and whitespace in the JSON body do not matter, because the comparison uses a hash of the parsed fields.

### Get a transfer

```bash
curl http://localhost:8080/transfers/<transfer-id>
```

Returns `200` with the transfer, or `404 not_found`.

### List account entries

```bash
curl 'http://localhost:8080/accounts/<account-id>/entries?limit=2'
```

```json
{
  "entries": [
    {"id": 42, "transferId": "<uuid>", "amount": -2500, "createdAt": "2026-01-01T12:00:00Z"},
    {"id": 17, "transferId": "<uuid>", "amount": 10000, "createdAt": "2026-01-01T11:59:00Z"}
  ],
  "nextCursor": "17"
}
```

Entries are ordered newest first and `amount` is signed (negative for debits). `limit` defaults to 50 and must be between 1 and 200. To get the next page, pass `nextCursor` as `cursor`; it is `null` on the last page. An invalid `limit` or `cursor` returns `400 validation_failed`, and an unknown account returns `404`.

### Errors

Every non-2xx response has the body `{"error": "<code>", "message": "<human text>"}`.

| Status | `error` | When |
|---|---|---|
| 400 | `validation_failed` | Malformed JSON, missing or invalid field, malformed UUID, invalid query parameter |
| 400 | `idempotency_key_required` | `Idempotency-Key` header missing, blank, or longer than 255 characters |
| 404 | `not_found` | Account, transfer or route does not exist |
| 405 | `method_not_allowed` | Wrong HTTP method for an existing path |
| 409 | `idempotency_key_reused` | Same `Idempotency-Key` used with a different request |
| 422 | `insufficient_funds` | A customer account would go below 0 |
| 422 | `currency_mismatch` | Request currency differs from an account's currency |
| 500 | `internal_error` | Unexpected failure; the message is generic and carries no stack trace or SQL |

`ErrorHandling` also maps any other HTTP client error raised by Javalin to the code `http_error`.

## How a transfer works

```
POST /transfers                     Idempotency-Key: <client-generated key>
{ "fromAccountId": "<uuid>", "toAccountId": "<uuid>", "amount": 2500, "currency": "EUR" }
```

1. **Validation.** The header and body are validated. The request is reduced to a SHA-256 hash of its canonical fields.
2. **Redis lookup.** If the key is cached and the hash matches, the stored response is returned with `200`. If the hash differs, the answer is `409`. A Redis error counts as a miss.
3. **Database.** `TransferService` first looks the key up in Postgres. If it is not there, it opens one transaction:
   1. `SELECT … FROM accounts WHERE id IN (:from, :to) ORDER BY id FOR UPDATE`.
      Both rows are always locked in ascending `id` order. When A→B and B→A run at the same time, both transactions try to lock the same row first, so one waits for the other instead of deadlocking.
   2. **Idempotency key re-check, after the lock.** A request with the same key may have committed while this one waited. If so, it is replayed (or rejected with `409` if the hash differs) before any balance check can see the winner's effects.
   3. Check that both accounts exist (`404`), that neither is a system account (`400`; only deposits may use them), that the currency matches (`422`), and that the source account has enough funds (`422`). Deposits are the one case where the source is a system account, which may go negative.
   4. `INSERT` the transfer, then the two ledger entries, then update both balances. If the insert still hits the unique constraint on `idempotency_key`, the transaction rolls back and the existing transfer is returned as a replay.
4. **After commit,** the response is cached in Redis under the key with a 24h TTL. Error responses are not cached.

Postgres stays authoritative. If Redis is down, or the process crashes between commit and the cache write, the unique constraint still guarantees the transfer happens at most once.

## Testing

Everything is tested against real PostgreSQL and Redis containers (Testcontainers). The containers start once per test run and are shared by all specs, and the tables are emptied before each test. CI runs `./gradlew build` on every push and pull request (`.github/workflows/ci.yml`).

Specs are in `src/test/groovy/com/paymentsledger/`:

- `ConcurrencySpec`
  - **Conservation stress test:** 5,000 transfers on a 16-thread pool across 10 accounts, 2,000 of them in 1,000 opposite-direction pairs that start together on different threads. Afterwards there must be no deadlocks and no unexpected errors (only `insufficient_funds` failures are allowed), the total balance must be unchanged, no customer balance may be negative, every balance must equal the sum of its entries, and every transfer must have exactly two entries that sum to zero.
  - **Overdraft race:** an account holding 100 receives 20 parallel transfers of 10 on 20 threads. Exactly 10 succeed and 10 fail with `insufficient_funds`.
  - **Idempotency race:** 50 threads call the service with the same key. Exactly one transfer and two entries are created, and the other 49 results are replays.
- `transfers/TransferServiceSpec`: the service in isolation, including a retry blocked on the row locks while the winning request commits (it replays the winner, or returns `409` if the hash differs, rather than failing the funds check).
- `TransfersApiSpec`: deposits, transfers, replay, key reuse with a different body, header and body validation, `404` and `422` cases, request hash format, and `GET /transfers/{id}`.
- `IdempotencyCacheSpec`: a cached entry is served without touching the database, a cached entry with a different body returns `409`, successful transfers and deposits are cached (transfers with a 24h TTL), errors are not cached, and 50 concurrent HTTP posts with one key give one `201` and 49 `200`s.
- `RedisOutageSpec`: the app is started with an unreachable Redis URL. A deposit, a transfer and its replay still return `201`, `201` and `200`, and `/health` returns `503` with `redis` reported as `down`.
- `EntriesApiSpec`: cursor pagination (pages of 2, 2 and 1 with descending ids and a final `null` cursor), signed amounts, default and maximum `limit`, invalid query values, and unknown or malformed account ids.
- `AccountsApiSpec`, `ErrorHandlingSpec`: account endpoints, the error body format, `404` for unknown routes, `405` for a wrong method, and a generic `500` for unexpected exceptions.
- `SchemaConstraintsSpec`: the database check, unique and foreign-key constraints, and system account rules.
- `HealthSpec`: `/health` and the migrated tables.

## Roadmap

- [x] Project skeleton: Gradle, Javalin, Flyway, jOOQ code generation, docker-compose
- [x] Spock + Testcontainers base spec, `/health`
- [x] Initial schema (`accounts`, `transfers`, `ledger_entries`)
- [x] Schema constraint specs and system (EXTERNAL) accounts
- [x] Accounts API: `POST /accounts`, `GET /accounts/{id}`
- [x] `TransferService` with ordered row locking
- [x] Conservation stress test and overdraft race
- [x] `POST /transfers`, `POST /deposits`, `GET /transfers/{id}`
- [x] Redis idempotency layer (+ race and outage tests)
- [x] `GET /accounts/{id}/entries` with cursor pagination
- [x] GitHub Actions CI

## Project layout

```
src/main/java/com/paymentsledger/              App wiring, config, database setup
  accounts/                                    Account and entry repositories and routes
  transfers/                                   TransferService, DepositService, transfer routes, request hashing
  idempotency/                                 Redis response cache
  api/                                         ApiException and JSON error handling
src/main/resources/db/migration/               Flyway migrations (also the jOOQ codegen input)
src/test/groovy/com/paymentsledger/            Spock specs
.github/workflows/ci.yml                       GitHub Actions workflow
build/generated-sources/jooq/                  Generated jOOQ classes (not committed)
```
