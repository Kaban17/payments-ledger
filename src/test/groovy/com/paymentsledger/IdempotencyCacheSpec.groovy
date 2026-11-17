package com.paymentsledger

import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class IdempotencyCacheSpec extends IntegrationSpec {

    private String newAccount() { post("/accounts", [currency: "EUR"]).body.id }

    private static Map key(String k) { ["Idempotency-Key": k] }

    private String funded(long amount) {
        String id = newAccount()
        assert post("/deposits", [accountId: id, amount: amount, currency: "EUR"], key(UUID.randomUUID().toString())).status == 201
        id
    }

    private static String sha256(String s) {
        MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).collect { String.format("%02x", it) }.join()
    }

    def "a cached entry is served without touching the database"() {
        given:
        def a = UUID.randomUUID().toString()
        def b = UUID.randomUUID().toString()
        def hash = sha256("transfer|$a|$b|100|EUR")
        redis { it.set("idem:K", """{"requestHash":"$hash","body":"{\\"marker\\":true}"}""") }

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"], key("K"))

        then:
        r.status == 200
        r.body == [marker: true]
    }

    def "a cached entry with a different body returns 409"() {
        given:
        def a = UUID.randomUUID().toString()
        def b = UUID.randomUUID().toString()
        redis { it.set("idem:K", """{"requestHash":"${sha256("transfer|$a|$b|100|EUR")}","body":"{}"}""") }

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 101, currency: "EUR"], key("K"))

        then:
        r.status == 409
        r.body.error == "idempotency_key_reused"
    }

    def "a successful transfer is cached with a 24h ttl"() {
        given:
        def a = funded(1000)
        def b = newAccount()

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"], key("ttl"))
        def ttl = redis { it.ttl("idem:ttl") }

        then:
        r.status == 201
        ttl > 0 && ttl <= 86400
    }

    def "a successful deposit is cached"() {
        given:
        def a = newAccount()

        when:
        def r = post("/deposits", [accountId: a, amount: 100, currency: "EUR"], key("dep"))

        then:
        r.status == 201
        redis { it.exists("idem:dep") }
    }

    def "an error response is not cached"() {
        given:
        def a = newAccount()
        def b = newAccount()

        when:
        def r = post("/transfers", [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"], key("err"))

        then:
        r.status == 422
        !redis { it.exists("idem:err") }
    }

    def "50 concurrent posts with the same key yield one 201 and 49 replays"() {
        given:
        def a = funded(1000)
        def b = newAccount()
        def body = [fromAccountId: a, toAccountId: b, amount: 10, currency: "EUR"]
        def pool = Executors.newFixedThreadPool(50)

        when:
        def results
        try {
            results = (1..50).collect { pool.submit({ post("/transfers", body, key("race")) } as Callable) }*.get()
        } finally {
            pool.shutdownNow()
        }

        then:
        results.count { it.status == 201 } == 1
        results.count { it.status == 200 } == 49
        results*.body.id.unique().size() == 1
        app.dsl().fetchOne("select count(*) from transfers where idempotency_key = ?", "race").get(0) == 1
    }
}
