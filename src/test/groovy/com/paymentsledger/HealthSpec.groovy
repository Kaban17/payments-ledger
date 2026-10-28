package com.paymentsledger

class HealthSpec extends IntegrationSpec {

    def "health reports postgres and redis up"() {
        when:
        def response = get("/health")

        then:
        response.status == 200
        response.body == [status: "ok", postgres: "up", redis: "up"]
    }

    def "migrations create the ledger tables"() {
        when:
        def tables = app.dsl().fetch(
                "select table_name from information_schema.tables where table_schema = 'public'")
                .getValues(0, String)

        then:
        tables.containsAll(["accounts", "transfers", "ledger_entries"])
    }
}
