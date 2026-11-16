package com.paymentsledger.transfers

import com.paymentsledger.IntegrationSpec
import com.paymentsledger.api.ApiException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferServiceSpec extends IntegrationSpec {

    static final String SYSTEM = "00000000-0000-0000-0000-000000000001"

    TransferService service

    def setup() {
        service = new TransferService(app.dsl())
    }

    private String customer(String currency = "EUR") {
        def id = UUID.randomUUID().toString()
        exec("insert into accounts (id, currency) values (?::uuid, ?)", id, currency)
        id
    }

    private void system(String currency = "EUR") {
        exec("insert into accounts (id, currency, is_system) values (?::uuid, ?, true)", SYSTEM, currency)
    }

    private TransferCommand cmd(String from, String to, long amount, String key = UUID.randomUUID().toString(),
                                String hash = "h", String currency = "EUR") {
        new TransferCommand(key, hash, UUID.fromString(from), UUID.fromString(to), amount, currency)
    }

    private long balance(String id) {
        app.dsl().fetchOne("select balance from accounts where id = ?::uuid", id).get(0, Long)
    }

    private long count(String table) {
        app.dsl().fetchOne("select count(*) from " + table).get(0, Long)
    }

    def "transfer moves balances and writes two entries summing to zero"() {
        given:
        def a = customer(); def b = customer()
        system()
        service.transfer(cmd(SYSTEM, a, 1000))

        when:
        def result = service.transfer(cmd(a, b, 300))

        then:
        !result.replayed()
        result.transfer().amount() == 300
        balance(a) == 700
        balance(b) == 300
        app.dsl().fetch("select amount from ledger_entries where transfer_id = ?::uuid", result.transfer().id().toString())
                .collect { it.get(0, Long) }.sort() == [-300L, 300L]
        service.findById(result.transfer().id()).get().id() == result.transfer().id()
    }

    def "insufficient funds is 422 and nothing changes"() {
        given:
        def a = customer(); def b = customer()
        system()
        service.transfer(cmd(SYSTEM, a, 100))
        def transfers = count("transfers"); def entries = count("ledger_entries")

        when:
        service.transfer(cmd(a, b, 101))

        then:
        def e = thrown(ApiException)
        e.status() == 422
        e.code() == "insufficient_funds"
        balance(a) == 100
        balance(b) == 0
        count("transfers") == transfers
        count("ledger_entries") == entries
    }

    def "unknown account is 404"() {
        given:
        def a = customer()

        when:
        service.transfer(cmd(a, UUID.randomUUID().toString(), 10))

        then:
        def e = thrown(ApiException)
        e.status() == 404
        e.code() == "not_found"
        count("transfers") == 0
    }

    def "currency mismatch is 422"() {
        given:
        def a = customer("EUR"); def b = customer("USD")
        system()
        service.transfer(cmd(SYSTEM, a, 100))

        when:
        service.transfer(cmd(a, b, 10))

        then:
        def e = thrown(ApiException)
        e.status() == 422
        e.code() == "currency_mismatch"
        balance(a) == 100
        count("transfers") == 1
    }

    def "#description is 400"() {
        given:
        def a = customer(); def b = customer()

        when:
        service.transfer(cmd(a, same ? a : b, amount, "k", "h", currency))

        then:
        def e = thrown(ApiException)
        e.status() == 400
        e.code() == "validation_failed"
        count("transfers") == 0

        where:
        description    | same  | amount | currency
        "same account" | true  | 10L    | "EUR"
        "zero amount"  | false | 0L     | "EUR"
        "negative"     | false | -5L    | "EUR"
        "bad currency" | false | 10L    | "eur"
    }

    def "replay with same key and hash returns same transfer without new writes"() {
        given:
        def a = customer(); def b = customer()
        system()
        service.transfer(cmd(SYSTEM, a, 1000))
        def first = service.transfer(cmd(a, b, 300, "key-1", "hash-1"))

        when:
        def second = service.transfer(cmd(a, b, 300, "key-1", "hash-1"))

        then:
        second.replayed()
        second.transfer().id() == first.transfer().id()
        balance(a) == 700
        balance(b) == 300
        app.dsl().fetchOne("select count(*) from ledger_entries where transfer_id = ?::uuid",
                first.transfer().id().toString()).get(0, Long) == 2
    }

    def "same key with different hash is 409"() {
        given:
        def a = customer(); def b = customer()
        system()
        service.transfer(cmd(SYSTEM, a, 1000))
        service.transfer(cmd(a, b, 300, "key-1", "hash-1"))

        when:
        service.transfer(cmd(a, b, 300, "key-1", "hash-2"))

        then:
        def e = thrown(ApiException)
        e.status() == 409
        e.code() == "idempotency_key_reused"
        balance(a) == 700
    }

    /**
     * Starts a competing transaction that locks both accounts, inserts transfer K and debits `a` by
     * `amount`, then waits on the latch before committing. Returns once it holds the locks.
     */
    private Map startWinner(String a, String b, long amount, String key, String hash, CountDownLatch commit) {
        def locked = new CountDownLatch(1)
        def pool = Executors.newSingleThreadExecutor()
        def winnerId = UUID.randomUUID()
        def future = pool.submit({
            app.dsl().transaction { cfg ->
                def tx = org.jooq.impl.DSL.using(cfg)
                tx.execute("select id from accounts where id in (?::uuid, ?::uuid) order by id for update", a, b)
                tx.execute("insert into transfers (id, idempotency_key, request_hash, from_account_id, to_account_id, amount, currency) " +
                        "values (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, 'EUR')", winnerId.toString(), key, hash, a, b, amount)
                tx.execute("insert into ledger_entries (transfer_id, account_id, amount) values (?::uuid, ?::uuid, ?), (?::uuid, ?::uuid, ?)",
                        winnerId.toString(), a, -amount, winnerId.toString(), b, amount)
                tx.execute("update accounts set balance = balance - ? where id = ?::uuid", amount, a)
                tx.execute("update accounts set balance = balance + ? where id = ?::uuid", amount, b)
                locked.countDown()
                commit.await(30, TimeUnit.SECONDS)
            }
        } as Runnable)
        assert locked.await(30, TimeUnit.SECONDS)
        [id: winnerId, future: future, pool: pool]
    }

    private void awaitBlockedSession() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (app.dsl().fetchOne("select count(*) from pg_stat_activity where wait_event_type = 'Lock'").get(0, Long) > 0) return
            Thread.sleep(20)
        }
        throw new AssertionError("service call never blocked on the account locks")
    }

    private def runBlockedAgainstWinner(String key, String winnerHash, String callerHash) {
        def a = customer(); def b = customer()
        system()
        service.transfer(cmd(SYSTEM, a, 100))
        def commit = new CountDownLatch(1)
        def winner = startWinner(a, b, 100, key, winnerHash, commit)
        def callerPool = Executors.newSingleThreadExecutor()
        def caller = callerPool.submit({
            try { service.transfer(cmd(a, b, 100, key, callerHash)) } catch (ApiException e) { e }
        } as java.util.concurrent.Callable)
        awaitBlockedSession()
        commit.countDown()
        winner.future.get(30, TimeUnit.SECONDS)
        def outcome = caller.get(30, TimeUnit.SECONDS)
        winner.pool.shutdown(); callerPool.shutdown()
        [a: a, winnerId: winner.id, outcome: outcome]
    }

    def "concurrent retry blocked on locks replays the winner instead of failing funds check"() {
        when:
        def r = runBlockedAgainstWinner("key-race", "hash-1", "hash-1")

        then:
        r.outcome instanceof TransferResult
        r.outcome.replayed()
        r.outcome.transfer().id() == r.winnerId
        balance(r.a) == 0
        app.dsl().fetchOne("select count(*) from transfers where idempotency_key = 'key-race'").get(0, Long) == 1
    }

    def "concurrent same key with different hash blocked on locks is 409 not 422"() {
        when:
        def r = runBlockedAgainstWinner("key-race", "hash-1", "hash-2")

        then:
        r.outcome instanceof ApiException
        r.outcome.status() == 409
        r.outcome.code() == "idempotency_key_reused"
        balance(r.a) == 0
    }

    def "system account can send more than its balance"() {
        given:
        def a = customer()
        system()

        when:
        service.transfer(cmd(SYSTEM, a, 500))

        then:
        balance(SYSTEM) == -500
        balance(a) == 500
    }

    def "findById returns empty for unknown id"() {
        expect:
        service.findById(UUID.randomUUID()).isEmpty()
    }
}
