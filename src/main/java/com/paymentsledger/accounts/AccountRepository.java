package com.paymentsledger.accounts;

import static com.paymentsledger.jooq.Tables.ACCOUNTS;

import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class AccountRepository {

    private final DSLContext dsl;

    public AccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Account createCustomer(String currency) {
        return dsl.insertInto(ACCOUNTS)
                .set(ACCOUNTS.ID, UUID.randomUUID())
                .set(ACCOUNTS.CURRENCY, currency)
                .set(ACCOUNTS.BALANCE, 0L)
                .set(ACCOUNTS.IS_SYSTEM, false)
                .returning(ACCOUNTS.ID, ACCOUNTS.CURRENCY, ACCOUNTS.BALANCE, ACCOUNTS.CREATED_AT)
                .fetchOne(r -> new Account(r.getId(), r.getCurrency(), r.getBalance(), r.getCreatedAt()));
    }

    public Optional<Account> find(UUID id) {
        return dsl.select(ACCOUNTS.ID, ACCOUNTS.CURRENCY, ACCOUNTS.BALANCE, ACCOUNTS.CREATED_AT)
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(id))
                .fetchOptional(r -> new Account(
                        r.get(ACCOUNTS.ID), r.get(ACCOUNTS.CURRENCY), r.get(ACCOUNTS.BALANCE), r.get(ACCOUNTS.CREATED_AT)));
    }
}
