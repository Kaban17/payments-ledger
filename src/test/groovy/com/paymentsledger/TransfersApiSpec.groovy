package com.paymentsledger

import java.security.MessageDigest
import spock.lang.Unroll

class TransfersApiSpec extends IntegrationSpec {

    private String newAccount(String currency = "EUR") {
        post("/accounts", [currency: currency]).body.id
    }

    private static Map key(String k) { ["Idempotency-Key": k] }

    private String funded(long amount, String currency = "EUR") {
        String id = newAccount(currency)
        assert post("/deposits", [accountId: id, amount: amount, currency: currency], key(UUID.randomUUID().toString())).status == 201
        id
    }

    private static String sha256(String s) {
        MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).collect { String.format("%02x", it) }.join()
    }

    def "deposit then transfer updates balances end to end"() {
        given:
        def a = newAccount()
        def b = newAccount()

        when:
        def dep = post("/deposits", [accountId: a, amount: 5000, currency: "EUR"], key("d1"))
        def tr = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 2500, currency: "EUR"], key("t1"))

        then:
        dep.status == 201
        tr.status == 201
        tr.body.keySet() == ["id", "fromAccountId", "toAccountId", "amount", "currency", "createdAt"] as Set
        tr.body.amount == 2500
        get("/accounts/$a").body.balance == 2500
        get("/accounts/$b").body.balance == 2500
    }

    def "replay returns 200 with identical body"() {
        given:
        def a = funded(1000)
        def b = newAccount()
        def body = [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"]

        when:
        def first = post("/transfers", body, key("k"))
        def second = post("/transfers", body, key("k"))

        then:
        first.status == 201
        second.status == 200
        second.body == first.body
        get("/accounts/$a").body.balance == 900
    }

    def "same key with a different body returns 409"() {
        given:
        def a = funded(1000)
        def b = newAccount()
        post("/transfers", [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"], key("k"))

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 101, currency: "EUR"], key("k"))

        then:
        r.status == 409
        r.body.error == "idempotency_key_reused"
    }

    def "different key order and whitespace under the same key is a replay"() {
        given:
        def a = funded(1000)
        def b = newAccount()
        def one = """{"fromAccountId":"$a","toAccountId":"$b","amount":100,"currency":"EUR"}"""
        def two = """{ "currency" : "EUR",\n  "amount" : 100, "toAccountId" : "$b",\n "fromAccountId" : "$a" }"""

        when:
        def first = postRaw("/transfers", one, key("k"))
        def second = postRaw("/transfers", two, key("k"))

        then:
        first.status == 201
        second.status == 200
        second.body == first.body
    }

    def "deposit replay returns 200 and a different deposit body returns 409"() {
        given:
        def a = newAccount()

        when:
        def first = post("/deposits", [accountId: a, amount: 700, currency: "EUR"], key("k"))
        def second = post("/deposits", [accountId: a, amount: 700, currency: "EUR"], key("k"))
        def third = post("/deposits", [accountId: a, amount: 701, currency: "EUR"], key("k"))

        then:
        first.status == 201
        second.status == 200
        second.body == first.body
        third.status == 409
        get("/accounts/$a").body.balance == 700
    }

    def "stored request hash is lowercase hex sha256 of the canonical string"() {
        given:
        def a = funded(1000)
        def b = newAccount()
        post("/transfers", [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"], key("kt"))

        expect:
        app.dsl().fetchValue("SELECT request_hash FROM transfers WHERE idempotency_key = 'kt'") ==
                sha256("transfer|$a|$b|100|EUR")
    }

    def "deposit hash uses the deposit canonical string"() {
        given:
        def a = newAccount()
        post("/deposits", [accountId: a, amount: 5000, currency: "EUR"], key("kd"))

        expect:
        app.dsl().fetchValue("SELECT request_hash FROM transfers WHERE idempotency_key = 'kd'") ==
                sha256("deposit|$a|5000|EUR")
    }

    def "deposits reuse a single system account per currency"() {
        given:
        def a = newAccount()
        def b = newAccount()

        when:
        post("/deposits", [accountId: a, amount: 10, currency: "EUR"], key("1"))
        post("/deposits", [accountId: b, amount: 10, currency: "EUR"], key("2"))

        then:
        app.dsl().fetchValue("SELECT count(*) FROM accounts WHERE is_system") == 1
        app.dsl().fetchValue("SELECT balance FROM accounts WHERE is_system") == -20
    }

    @Unroll
    def "#header idempotency key returns 400 idempotency_key_required"() {
        given:
        def a = newAccount()
        def b = newAccount()

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 1, currency: "EUR"], headers)
        def d = post("/deposits", [accountId: a, amount: 1, currency: "EUR"], headers)

        then:
        r.status == 400
        r.body.error == "idempotency_key_required"
        d.status == 400
        d.body.error == "idempotency_key_required"

        where:
        header    | headers
        "missing" | [:]
        "blank"   | ["Idempotency-Key": "   "]
        "long"    | ["Idempotency-Key": "k" * 256]
    }

    def "key of exactly 255 chars is accepted"() {
        given:
        def a = newAccount()

        expect:
        post("/deposits", [accountId: a, amount: 1, currency: "EUR"], key("k" * 255)).status == 201
    }

    def "insufficient funds returns 422"() {
        given:
        def a = funded(100)
        def b = newAccount()

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 101, currency: "EUR"], key("k"))

        then:
        r.status == 422
        r.body.error == "insufficient_funds"
    }

    def "currency mismatch returns 422"() {
        given:
        def a = funded(100)
        def b = newAccount("USD")

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 10, currency: "EUR"], key("k"))
        def d = post("/deposits", [accountId: b, amount: 10, currency: "EUR"], key("k2"))

        then:
        r.status == 422
        r.body.error == "currency_mismatch"
        d.status == 422
        d.body.error == "currency_mismatch"
    }

    def "unknown account returns 404"() {
        given:
        def a = funded(100)

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: UUID.randomUUID().toString(), amount: 10, currency: "EUR"], key("k"))
        def d = post("/deposits", [accountId: UUID.randomUUID().toString(), amount: 10, currency: "EUR"], key("k2"))

        then:
        r.status == 404
        r.body.error == "not_found"
        d.status == 404
        d.body.error == "not_found"
    }

    def "deposit into a system account returns 400"() {
        given:
        def a = newAccount()
        post("/deposits", [accountId: a, amount: 10, currency: "EUR"], key("d"))
        String systemId = app.dsl().fetchValue("SELECT id FROM accounts WHERE is_system").toString()

        when:
        def r = post("/deposits", [accountId: systemId, amount: 10, currency: "EUR"], key("d2"))

        then:
        r.status == 400
        r.body.error == "validation_failed"
    }

    @Unroll
    def "invalid transfer body #name returns 400 validation_failed"() {
        given:
        def a = UUID.randomUUID().toString()
        def b = UUID.randomUUID().toString()
        def base = [fromAccountId: a, toAccountId: b, amount: 10, currency: "EUR"]

        when:
        def r = postRaw("/transfers", raw != null ? raw : groovy.json.JsonOutput.toJson(base + patch), key("k"))

        then:
        r.status == 400
        r.body.error == "validation_failed"

        where:
        name               | patch                         | raw
        "null from"        | [fromAccountId: null]         | null
        "numeric from"     | [fromAccountId: 5]            | null
        "bad uuid to"      | [toAccountId: "nope"]         | null
        "null amount"      | [amount: null]                | null
        "string amount"    | [amount: "10"]                | null
        "decimal amount"   | [amount: 10.5]                | null
        "zero amount"      | [amount: 0]                   | null
        "negative amount"  | [amount: -1]                  | null
        "huge amount"      | [amount: 9223372036854775808] | null
        "null currency"    | [currency: null]              | null
        "numeric currency" | [currency: 5]                 | null
        "lowercase ccy"    | [currency: "eur"]             | null
        "missing fields"   | [:]                           | '{}'
        "array body"       | [:]                           | '[]'
        "empty body"       | [:]                           | ''
        "bad json"         | [:]                           | '{'
    }

    def "same account transfer returns 400"() {
        given:
        def a = funded(100)

        expect:
        post("/transfers", [fromAccountId: a, toAccountId: a, amount: 1, currency: "EUR"], key("k")).status == 400
    }

    @Unroll
    def "invalid deposit body #name returns 400 validation_failed"() {
        when:
        def r = postRaw("/deposits", raw, key("k"))

        then:
        r.status == 400
        r.body.error == "validation_failed"

        where:
        name            | raw
        "missing"       | '{}'
        "null account"  | '{"accountId":null,"amount":5,"currency":"EUR"}'
        "bad uuid"      | '{"accountId":"x","amount":5,"currency":"EUR"}'
        "string amount" | '{"accountId":"00000000-0000-0000-0000-000000000001","amount":"5","currency":"EUR"}'
        "decimal"       | '{"accountId":"00000000-0000-0000-0000-000000000001","amount":5.5,"currency":"EUR"}'
        "null amount"   | '{"accountId":"00000000-0000-0000-0000-000000000001","amount":null,"currency":"EUR"}'
        "bad currency"  | '{"accountId":"00000000-0000-0000-0000-000000000001","amount":5,"currency":"EURO"}'
        "null currency" | '{"accountId":"00000000-0000-0000-0000-000000000001","amount":5,"currency":null}'
        "zero"          | '{"accountId":"00000000-0000-0000-0000-000000000001","amount":0,"currency":"EUR"}'
    }

    def "get transfer found"() {
        given:
        def a = funded(100)
        def b = newAccount()
        def created = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 10, currency: "EUR"], key("k"))

        when:
        def r = get("/transfers/${created.body.id}")

        then:
        r.status == 200
        r.body == created.body
    }

    def "get transfer not found and malformed id"() {
        expect:
        get("/transfers/${UUID.randomUUID()}").status == 404
        get("/transfers/${UUID.randomUUID()}").body.error == "not_found"
        get("/transfers/not-a-uuid").status == 400
        get("/transfers/not-a-uuid").body.error == "validation_failed"
    }
}
