package com.paymentsledger

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import spock.lang.Shared
import spock.lang.Specification

class RedisOutageSpec extends Specification {

    @Shared App app
    @Shared HttpClient http = HttpClient.newHttpClient()

    def setupSpec() {
        app = App.create(new Config(
                IntegrationSpec.POSTGRES.jdbcUrl,
                IntegrationSpec.POSTGRES.username,
                IntegrationSpec.POSTGRES.password,
                "redis://localhost:1",
                0)).start(0)
    }

    def cleanupSpec() {
        app?.stop()
    }

    private Map post(String path, Object body, String key) {
        def req = HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build()
        def r = http.send(req, HttpResponse.BodyHandlers.ofString())
        [status: r.statusCode(), body: new JsonSlurper().parseText(r.body())]
    }

    def "transfers and replays work without redis and health reports it down"() {
        given:
        def aId = post("/accounts", [currency: "EUR"], "x").body.id
        def bId = post("/accounts", [currency: "EUR"], "x").body.id
        def dep = [accountId: aId, amount: 500, currency: "EUR"]
        def body = [fromAccountId: aId, toAccountId: bId, amount: 100, currency: "EUR"]

        when:
        def d = post("/deposits", dep, "dep-outage")
        def first = post("/transfers", body, "tr-outage")
        def second = post("/transfers", body, "tr-outage")
        def health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}/health")).build(),
                HttpResponse.BodyHandlers.ofString())

        then:
        d.status == 201
        first.status == 201
        second.status == 200
        second.body.id == first.body.id
        health.statusCode() == 503
        new JsonSlurper().parseText(health.body()).redis == "down"
    }
}
