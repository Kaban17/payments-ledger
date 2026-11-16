package com.paymentsledger.transfers

import com.paymentsledger.IntegrationSpec
import com.paymentsledger.api.ApiException

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
