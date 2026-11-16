package com.paymentsledger

import spock.lang.Unroll

/**
 * The database is the last line of defence: even if service code has a bug,
 * these rows must never be written.
 */
class SchemaConstraintsSpec extends IntegrationSpec {

    static final String A = "00000000-0000-0000-0000-00000000000a"
    static final String B = "00000000-0000-0000-0000-00000000000b"
    static final String MISSING = "00000000-0000-0000-0000-0000000000ff"
    static final String T1 = "00000000-0000-0000-0000-000000000001"
    static final String T2 = "00000000-0000-0000-0000-000000000002"

    def setup() {
        exec("insert into accounts (id, currency) values (?::uuid, 'EUR'), (?::uuid, 'EUR')", A, B)
        exec(transferSql(T1, "existing-key", A, B, 100))
    }

    @Unroll
    def "schema rejects #description"() {
        when:
        def rejected = violation { exec(sql) }

        then:
        rejected == [state: state, constraint: constraint]

        where:
        description                              | sql                                                    || state   | constraint
        "a zero-amount transfer"                 | transferSql(T2, "k2", A, B, 0)                         || "23514" | "transfers_amount_positive"
        "a negative-amount transfer"             | transferSql(T2, "k2", A, B, -5)                        || "23514" | "transfers_amount_positive"
        "a transfer to the same account"         | transferSql(T2, "k2", A, A, 100)                       || "23514" | "transfers_distinct_accounts"
        "a reused idempotency key"               | transferSql(T2, "existing-key", A, B, 100)             || "23505" | "transfers_idempotency_key_uq"
        "a transfer from an unknown account"     | transferSql(T2, "k2", MISSING, B, 100)                 || "23503" | "transfers_from_account_id_fkey"
        "a transfer to an unknown account"       | transferSql(T2, "k2", A, MISSING, 100)                 || "23503" | "transfers_to_account_id_fkey"
        "a zero-amount ledger entry"             | entrySql(T1, A, 0)                                     || "23514" | "ledger_entries_amount_nonzero"
        "an entry for an unknown transfer"       | entrySql(MISSING, A, 100)                              || "23503" | "ledger_entries_transfer_id_fkey"
        "an entry for an unknown account"        | entrySql(T1, MISSING, 100)                             || "23503" | "ledger_entries_account_id_fkey"
    }

    def "schema accepts a well-formed transfer with balanced entries"() {
        expect: "the fixture rows used above are valid on their own"
        violation {
            exec(transferSql(T2, "k2", A, B, 250))
            exec(entrySql(T2, A, -250))
            exec(entrySql(T2, B, 250))
        } == null
    }

    def "a customer account balance can never go negative"() {
        when:
        def rejected = violation { exec("update accounts set balance = -1 where id = ?::uuid", A) }

        then:
        rejected == [state: "23514", constraint: "accounts_balance_non_negative"]
    }

    def "a system account may go negative, since money enters the ledger through it"() {
        when:
        def rejected = violation {
            exec("insert into accounts (id, currency, is_system, balance) values (?::uuid, 'EUR', true, -500)", T2)
        }

        then:
        rejected == null
    }

    def "there is at most one system account per currency"() {
        given:
        exec("insert into accounts (id, currency, is_system) values (?::uuid, 'EUR', true)", T1)

        expect: "a second EUR system account is rejected"
        violation {
            exec("insert into accounts (id, currency, is_system) values (?::uuid, 'EUR', true)", T2)
        } == [state: "23505", constraint: "accounts_one_system_per_currency"]

        and: "a system account in another currency is fine"
        violation {
            exec("insert into accounts (id, currency, is_system) values (?::uuid, 'USD', true)", MISSING)
        } == null
    }

    private static String transferSql(String id, String key, String from, String to, long amount) {
        """insert into transfers (id, idempotency_key, request_hash, from_account_id, to_account_id, amount, currency)
           values ('$id', '$key', 'hash', '$from', '$to', $amount, 'EUR')"""
    }

    private static String entrySql(String transferId, String accountId, long amount) {
        "insert into ledger_entries (transfer_id, account_id, amount) values ('$transferId', '$accountId', $amount)"
    }
}
