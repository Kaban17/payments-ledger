package com.paymentsledger

import spock.lang.Unroll

class EntriesApiSpec extends IntegrationSpec {

    private static Map key(String k) { ["Idempotency-Key": k] }

    private String newAccount() {
        post("/accounts", [currency: "EUR"]).body.id
    }

    /** Produces 5 entries for the returned account: deposit +1000, then 2 outgoing and 2 incoming transfers. */
    private List seeded() {
        String a = newAccount()
        String b = newAccount()
        assert post("/deposits", [accountId: a, amount: 1000, currency: "EUR"], key("d")).status == 201
        assert post("/transfers", [fromAccountId: a, toAccountId: b, amount: 100, currency: "EUR"], key("t1")).status == 201
        assert post("/transfers", [fromAccountId: a, toAccountId: b, amount: 200, currency: "EUR"], key("t2")).status == 201
        assert post("/transfers", [fromAccountId: b, toAccountId: a, amount: 50, currency: "EUR"], key("t3")).status == 201
        assert post("/transfers", [fromAccountId: b, toAccountId: a, amount: 25, currency: "EUR"], key("t4")).status == 201
        [a, b]
    }

    def "pages of 2, 2, 1 with strictly descending ids and null final cursor"() {
        given:
        def (a, b) = seeded()

        when:
        def p1 = get("/accounts/$a/entries?limit=2")
        def p2 = get("/accounts/$a/entries?limit=2&cursor=${p1.body.nextCursor}")
        def p3 = get("/accounts/$a/entries?limit=2&cursor=${p2.body.nextCursor}")
        def ids = (p1.body.entries + p2.body.entries + p3.body.entries)*.id

        then:
        [p1, p2, p3]*.status == [200, 200, 200]
        [p1, p2, p3].collect { it.body.entries.size() } == [2, 2, 1]
        p1.body.nextCursor == p1.body.entries.last().id.toString()
        p2.body.nextCursor == p2.body.entries.last().id.toString()
        p3.body.nextCursor == null
        ids.size() == 5
        ids.unique(false).size() == 5
        ids == ids.sort(false) { -it }
    }

    def "entries have signed amounts and the documented shape"() {
        given:
        def (a, b) = seeded()

        when:
        def r = get("/accounts/$a/entries")

        then:
        r.status == 200
        r.body.keySet() == ["entries", "nextCursor"] as Set
        r.body.entries*.amount == [25, 50, -200, -100, 1000]
        r.body.entries[0].keySet() == ["id", "transferId", "amount", "createdAt"] as Set
        UUID.fromString(r.body.entries[0].transferId)
        java.time.OffsetDateTime.parse(r.body.entries[0].createdAt)
        r.body.nextCursor == null
    }

    def "limit exactly exhausting the entries returns a null cursor"() {
        given:
        def (a, b) = seeded()

        expect:
        get("/accounts/$a/entries?limit=5").body.nextCursor == null
    }

    def "account with no entries returns an empty list"() {
        given:
        def a = newAccount()

        when:
        def r = get("/accounts/$a/entries")

        then:
        r.status == 200
        r.body.entries == []
        r.body.nextCursor == null
    }

    @Unroll
    def "invalid query #query returns 400"() {
        given:
        def a = newAccount()

        when:
        def r = get("/accounts/$a/entries?$query")

        then:
        r.status == 400
        r.body.error == "validation_failed"

        where:
        query << ["limit=0", "limit=201", "limit=abc", "limit=-1", "cursor=abc", "cursor=-5"]
    }

    def "limit 200 is accepted"() {
        given:
        def a = newAccount()

        expect:
        get("/accounts/$a/entries?limit=200").status == 200
    }

    def "unknown account returns 404"() {
        when:
        def r = get("/accounts/${UUID.randomUUID()}/entries")

        then:
        r.status == 404
        r.body.error == "not_found"
    }

    def "malformed account id returns 400"() {
        when:
        def r = get("/accounts/not-a-uuid/entries")

        then:
        r.status == 400
        r.body.error == "validation_failed"
    }
}
