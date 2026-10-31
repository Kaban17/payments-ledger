# payments-ledger

A double-entry payments ledger REST API built with **Java 21, Javalin, jOOQ, PostgreSQL and Redis**, developed test-first with **Spock + Testcontainers**.

The core goals are:

- **Double-entry bookkeeping.** Every transfer writes two ledger entries that sum to zero, so money is never created or destroyed.
- **Idempotent transfers.** Retrying a request with the same `Idempotency-Key` never moves money twice. Redis serves replays quickly, and a PostgreSQL unique constraint is the source of truth.
- **Concurrency-safe balances.** Account rows are locked in a fixed order, so concurrent transfers can't deadlock or overdraw an account.

> **Status: in progress.** The project skeleton, schema and test infrastructure are done. The transfer API is next; see the [roadmap](#roadmap).

## Tech stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| HTTP | Javalin 6 + Jackson |
| SQL | jOOQ (classes generated from the Flyway migrations) |
| Migrations | Flyway |
| Database | PostgreSQL 16 |
| Cache / idempotency | Redis 7 (Jedis) |
| Connection pool | HikariCP |
| Tests | Spock 2.3 (Groovy 4) + Testcontainers |
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
accounts        id, currency, balance, created_at
transfers       id, idempotency_key (UNIQUE), request_hash,
                from_account_id, to_account_id, amount (> 0), currency, created_at
ledger_entries  id, transfer_id, account_id, amount (signed, ≠ 0), created_at
```

- `ledger_entries` is **append-only**. A transfer of 100 from A to B writes `A: -100` and `B: +100`.
- `accounts.balance` is a cached copy of `SUM(ledger_entries.amount)` for that account, updated in the same transaction as the entries. Tests check that the two always agree.
- Money enters the system through an **EXTERNAL** system account, so deposits are double-entry too.

## How a transfer works

```
POST /transfers                     Idempotency-Key: <client-generated key>
{ "from": "<uuid>", "to": "<uuid>", "amount": 2500, "currency": "EUR" }
```

1. **Redis check.** If the key is cached and the request hash matches, return the stored response. If the hash differs, return `409`.
2. **One database transaction:**
   1. `SELECT … FROM accounts WHERE id IN (:from, :to) ORDER BY id FOR UPDATE`.
      Both rows are always locked in ascending `id` order. When A→B and B→A run at the same time, both transactions try to lock the same row first, so one simply waits for the other instead of deadlocking.
   2. Check currency and funds.
   3. `INSERT` the transfer. If the insert hits the unique constraint on `idempotency_key`, another request with the same key got there first. Roll back and return the existing transfer.
   4. Insert the two ledger entries and update both balances.
3. **After commit,** cache the response in Redis with a 24h TTL.

Postgres stays authoritative. If Redis is down, or the process crashes between commit and the cache write, the unique constraint still guarantees the transfer happens at most once.

## Testing

Everything is tested against real PostgreSQL and Redis containers. The containers start once per test run and are shared by all specs, and the tables are emptied before each test.

Planned correctness tests:

- **Conservation stress test:** 16 threads making about 5,000 random transfers, including opposite-direction pairs. Afterwards the total balance must be unchanged, no balance may be negative, every balance must equal the sum of its entries, and no deadlocks may have occurred.
- **Overdraft race:** an account holding 100 receives 20 parallel transfers of 10. Exactly 10 must succeed.
- **Idempotency race:** 50 parallel requests with the same key must produce exactly one transfer and identical responses.
- **Redis outage:** transfers and replays stay correct with Redis stopped.

## Roadmap

- [x] Project skeleton: Gradle, Javalin, Flyway, jOOQ code generation, docker-compose
- [x] Spock + Testcontainers base spec, `/health`
- [x] Initial schema (`accounts`, `transfers`, `ledger_entries`)
- [ ] Schema constraint specs and the EXTERNAL system account
- [ ] Accounts API: `POST /accounts`, `GET /accounts/{id}`
- [ ] `TransferService` with ordered row locking
- [ ] Conservation stress test and overdraft race
- [ ] `POST /transfers`, `POST /deposits`, `GET /transfers/{id}`
- [ ] Redis idempotency layer (+ race and outage tests)
- [ ] `GET /accounts/{id}/entries` with cursor pagination
- [ ] GitHub Actions CI

## Project layout

```
src/main/java/com/paymentsledger/   App wiring, config, database setup
src/main/resources/db/migration/    Flyway migrations (also the jOOQ codegen input)
src/test/groovy/com/paymentsledger/ Spock specs
build/generated-sources/jooq/       Generated jOOQ classes (not committed)
```
