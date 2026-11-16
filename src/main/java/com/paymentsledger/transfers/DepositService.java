package com.paymentsledger.transfers;

import static com.paymentsledger.jooq.Tables.ACCOUNTS;

import com.paymentsledger.api.ApiException;
import java.util.UUID;
import org.jooq.DSLContext;

/** Deposits move money from the per-currency EXTERNAL system account into a customer account. */
public final class DepositService {

    private final DSLContext dsl;
    private final TransferService transfers;

    public DepositService(DSLContext dsl, TransferService transfers) {
        this.dsl = dsl;
        this.transfers = transfers;
    }

    public TransferResult deposit(String idempotencyKey, UUID accountId, long amount, String currency) {
        Boolean isSystem = dsl.select(ACCOUNTS.IS_SYSTEM)
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(accountId))
                .fetchOne(ACCOUNTS.IS_SYSTEM);
        if (isSystem == null) {
            throw ApiException.notFound("account");
        }
        if (isSystem) {
            throw ApiException.validation("cannot deposit into a system account");
        }
        UUID systemId = systemAccountId(currency);
        return transfers.transfer(new TransferCommand(
                idempotencyKey, RequestHash.deposit(accountId, amount, currency), systemId, accountId, amount, currency));
    }

    private UUID systemAccountId(String currency) {
        dsl.insertInto(ACCOUNTS)
                .set(ACCOUNTS.ID, UUID.randomUUID())
                .set(ACCOUNTS.CURRENCY, currency)
                .set(ACCOUNTS.IS_SYSTEM, true)
                .onConflict(ACCOUNTS.CURRENCY)
                .where(ACCOUNTS.IS_SYSTEM)
                .doNothing()
                .execute();
        return dsl.select(ACCOUNTS.ID)
                .from(ACCOUNTS)
                .where(ACCOUNTS.CURRENCY.eq(currency))
                .and(ACCOUNTS.IS_SYSTEM)
                .fetchOne(ACCOUNTS.ID);
    }
}
