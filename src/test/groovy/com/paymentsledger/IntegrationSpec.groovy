package com.paymentsledger

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

/**
 * Base class for specs that need the real app. Postgres and Redis containers are started once
 * per JVM and shared by all specs; tables are truncated before each feature.
 */
abstract class IntegrationSpec extends Specification {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
    static final GenericContainer REDIS = new GenericContainer("redis:7-alpine").withExposedPorts(6379)

    static {
        POSTGRES.start()
        REDIS.start()
    }

    @Shared App app
    @Shared HttpClient http = HttpClient.newHttpClient()

    def setupSpec() {
        app = App.create(new Config(
                POSTGRES.jdbcUrl,
                POSTGRES.username,
                POSTGRES.password,
                "redis://${REDIS.host}:${REDIS.getMappedPort(6379)}",
                0)).start(0)
    }

    def cleanupSpec() {
        app?.stop()
    }

    def setup() {
        app.dsl().execute("TRUNCATE ledger_entries, transfers, accounts CASCADE")
    }

    protected Map get(String path) {
        send(HttpRequest.newBuilder(uri(path)).GET())
    }

    protected Map post(String path, Object body, Map<String, String> headers = [:]) {
        def builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        headers.each { k, v -> builder.header(k, v) }
        send(builder)
    }

    private URI uri(String path) {
        URI.create("http://localhost:${app.port()}${path}")
    }

    /** Returns [status: int, body: parsed JSON or null]. */
    private Map send(HttpRequest.Builder builder) {
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        def body = response.body() ? new JsonSlurper().parseText(response.body()) : null
        [status: response.statusCode(), body: body]
    }
}
