package com.paymentsledger

import com.paymentsledger.api.ApiException
import com.paymentsledger.transfers.TransferCommand
import com.paymentsledger.transfers.TransferService
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.postgresql.util.PSQLException

/**
 * The project's correctness gate: concurrent transfers conserve money, never overdraw, never
 * deadlock and are idempotent. Exercises TransferService directly, no HTTP.
 */
class ConcurrencySpec extends IntegrationSpec {

    static final String SYSTEM = "00000000-0000-0000-0000-000000000001"
    static final long SEED = 20261116L
    static final long WAIT_SECONDS = 45

    TransferService service

    def setup() {
        service = new TransferService(app.dsl())
    }

    /** Outcome of one task: either a value or the exception it threw. */
    static class Outcome {
        Object value
        Throwable error
    }

    /**
     * Runs all tasks on a pool of the given size. Workers block on a start gate so they begin
     * together. The gate is always opened and the pool always shut down, even if waiting fails.
     */
    private List<Outcome> runGated(int threads, List<Callable> tasks) {
        def pool = Executors.newFixedThreadPool(threads)
        def gate = new CountDownLatch(1)
        try {
            def futures = tasks.collect { Callable task ->
                pool.submit({
                    if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out")
                    def o = new Outcome()
                    try { o.value = task.call() } catch (Throwable t) { o.error = t }
                    o
                } as Callable)
            }
            gate.countDown()
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            return futures.collect {
                it.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) as Outcome
            }
        } finally {
            gate.countDown()
            pool.shutdownNow()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = (c.cause == c ? null : c.cause)) {
            if (c instanceof PSQLException && c.SQLState == "40P01") return true
        }
        false
    }

    private static boolean isInsufficientFunds(Throwable t) {
        t instanceof ApiException && t.code() == "insufficient_funds"
    }

    private String customer(String currency = "EUR") {
        def id = UUID.randomUUID().toString()
        exec("insert into accounts (id, currency) values (?::uuid, ?)", id, currency)
        id
    }

    private void system(String currency = "EUR") {
        exec("insert into accounts (id, currency, is_system) values (?::uuid, ?, true)", SYSTEM, currency)
    }

    private TransferCommand cmd(String from, String to, long amount, String key = UUID.randomUUID().toString()) {
        new TransferCommand(key, "h", UUID.fromString(from), UUID.fromString(to), amount, "EUR", from == SYSTEM)
    }

    private long balance(String id) {
        app.dsl().fetchOne("select balance from accounts where id = ?::uuid", id).get(0, Long)
    }

    private long scalar(String sql) {
        app.dsl().fetchOne(sql).get(0, Long)
    }

    def "concurrent transfers conserve balance, never overdraw and never deadlock"() {
        given:
        def random = new Random(SEED)
        system()
        def accounts = (1..10).collect { customer() }
        accounts.each { service.transfer(cmd(SYSTEM, it, 10_000)) }

        // 1000 opposite-direction pairs (2000 transfers, 40%) queued back to back, plus 3000 random ones.
        // Each pair shares a barrier so both halves enter transfer() together on different threads.
        // Pairs are adjacent in the FIFO queue, so at most one half waits for its partner at a time.
        def tasks = []
        def pairThreads = new java.util.concurrent.ConcurrentHashMap<Integer, List<String>>()
        1000.times { int pair ->
            def a = accounts[random.nextInt(10)]
            def b = (accounts - a)[random.nextInt(9)]
            def barrier = new java.util.concurrent.CyclicBarrier(2)
            pairThreads[pair] = java.util.Collections.synchronizedList([])
            [cmd(a, b, 1 + random.nextInt(500)), cmd(b, a, 1 + random.nextInt(500))].each { c ->
                tasks << ({ ->
                    barrier.await(10, TimeUnit.SECONDS)
                    pairThreads[pair] << Thread.currentThread().name
                    service.transfer(c)
                } as Callable)
            }
        }
        3000.times {
            def a = accounts[random.nextInt(10)]
            def b = (accounts - a)[random.nextInt(9)]
            def c = cmd(a, b, 1 + random.nextInt(500))
            tasks << ({ -> service.transfer(c) } as Callable)
        }
        assert tasks.size() == 5000

        when:
        def outcomes = runGated(16, tasks)
        def failures = outcomes.findAll { it.error != null }.collect { it.error }
        def unexpected = failures.findAll { !isInsufficientFunds(it) }
        def deadlocks = failures.findAll { isDeadlock(it) }
        def succeeded = outcomes.count { it.error == null }
        def message = "seed=${SEED}, succeeded=${succeeded}, insufficient=${failures.size() - unexpected.size()}, " +
                "unexpected=${unexpected.collect { it.class.name + ': ' + it.message }.unique().take(10)}"

        then:
        assert deadlocks.isEmpty(): "deadlocks=${deadlocks.size()}, ${message}"
        assert unexpected.isEmpty(): message
        assert pairThreads.size() == 1000 && pairThreads.values().every { it.size() == 2 && it[0] != it[1] }:
                "opposite pairs must run on different threads; ${message}"

        assert succeeded > 0 && failures.size() > 0: "stress should mix successes and insufficient_funds; ${message}"

        and: "money is conserved and no customer is overdrawn"
        def ids = accounts.collect { "'${it}'" }.join(",")
        assert scalar("select sum(balance) from accounts where id in (${ids})") == 100_000L: message
        assert scalar("select count(*) from accounts where id in (${ids}) and balance < 0") == 0L: message

        and: "every balance equals the sum of its ledger entries"
        assert scalar("""select count(*) from accounts a
                where a.balance <> coalesce((select sum(e.amount) from ledger_entries e where e.account_id = a.id), 0)""") == 0L: message

        and: "every transfer has exactly two entries that sum to zero"
        assert scalar("select count(*) from transfers") == succeeded + 10L: message
        assert scalar("""select count(*) from transfers t
                where (select count(*) from ledger_entries e where e.transfer_id = t.id) <> 2
                   or (select sum(e.amount) from ledger_entries e where e.transfer_id = t.id) <> 0""") == 0L: message
        assert scalar("select count(*) from ledger_entries where transfer_id not in (select id from transfers)") == 0L
    }

    def "overdraft race lets exactly the affordable transfers through"() {
        given:
        system()
        def source = customer()
        def target = customer()
        service.transfer(cmd(SYSTEM, source, 100))

        when:
        def outcomes = runGated(20, (1..20).collect { { -> service.transfer(cmd(source, target, 10)) } as Callable })
        def failures = outcomes.findAll { it.error != null }.collect { it.error }
        def message = "outcomes=${outcomes.collect { it.error ? it.error.class.name + ': ' + it.error.message : 'ok' }}"

        then:
        assert outcomes.count { it.error == null } == 10: message
        assert failures.size() == 10: message
        assert failures.every { isInsufficientFunds(it) }: message
        balance(source) == 0L
        balance(target) == 100L
    }

    def "same idempotency key from 50 threads creates exactly one transfer"() {
        given:
        system()
        def source = customer()
        def target = customer()
        service.transfer(cmd(SYSTEM, source, 1000))
        def key = "race-" + UUID.randomUUID()

        when:
        def outcomes = runGated(50, (1..50).collect { { -> service.transfer(cmd(source, target, 100, key)) } as Callable })
        def message = "errors=${outcomes.findAll { it.error }.collect { it.error.class.name + ': ' + it.error.message }}"

        then:
        assert outcomes.every { it.error == null }: message
        def results = outcomes.collect { it.value }
        results.collect { it.transfer().id() }.unique().size() == 1
        results.count { !it.replayed() } == 1
        scalar("select count(*) from transfers where idempotency_key = '${key}'") == 1L
        scalar("select count(*) from ledger_entries where transfer_id = '${results[0].transfer().id()}'::uuid") == 2L
        balance(source) == 900L
        balance(target) == 100L
    }
}
