package com.paymentsledger

import spock.lang.Unroll

class AccountsApiSpec extends IntegrationSpec {

    def "creating an account returns 201 with a zero balance"() {
        when:
        def r = post("/accounts", [currency: "EUR"])

        then:
        r.status == 201
        r.body.currency == "EUR"
        r.body.balance == 0
        UUID.fromString(r.body.id as String)
        java.time.Instant.parse(r.body.createdAt as String)
        r.body.keySet() == ["id", "currency", "balance", "createdAt"] as Set
    }

    def "created account is a customer account"() {
        when:
        def r = post("/accounts", [currency: "USD"])

        then:
        app.dsl().fetchValue("SELECT is_system FROM accounts WHERE id = ?::uuid", r.body.id) == false
    }

    def "get returns the created account"() {
        given:
        def created = post("/accounts", [currency: "EUR"])

        when:
        def r = get("/accounts/${created.body.id}")

        then:
        r.status == 200
        r.body == created.body
    }

    def "get unknown account returns 404"() {
        when:
        def r = get("/accounts/${UUID.randomUUID()}")

        then:
        r.status == 404
        r.body == [error: "not_found", message: "account not found"]
    }

    def "get with malformed uuid returns 400"() {
        when:
        def r = get("/accounts/not-a-uuid")

        then:
        r.status == 400
        r.body.error == "validation_failed"
    }

    @Unroll
    def "invalid body #body is rejected"() {
        when:
        def r = post("/accounts", body)

        then:
        r.status == 400
        r.body.error == "validation_failed"

        where:
        body << [[:], [currency: 5], [currency: null], [currency: "eur"], [currency: "EURO"], [currency: ""]]
    }

    def "malformed json returns 400"() {
        when:
        def r = postRaw("/accounts", "{not json")

        then:
        r.status == 400
        r.body.error == "validation_failed"
    }

    def "unknown route returns 404 not_found"() {
        when:
        def r = get("/unknown-route")

        then:
        r.status == 404
        r.body.keySet() == ["error", "message"] as Set
        r.body.error == "not_found"
    }

    def "wrong method on an existing path returns Javalin's 404 (no 405 detection)"() {
        when:
        def response = http.send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}/accounts"))
                        .PUT(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
        def body = new groovy.json.JsonSlurper().parseText(response.body())

        then:
        response.statusCode() == 404
        body.keySet() == ["error", "message"] as Set
        body.error == "not_found"
    }
}
