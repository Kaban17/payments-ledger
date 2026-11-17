package com.paymentsledger.transfers;

import static com.paymentsledger.jooq.Tables.ACCOUNTS;

import com.paymentsledger.api.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record2;

/** Deposits move money from the per-currency EXTERNAL system account into a customer account. */
public final class DepositService {

    private final DSLContext dsl;
    private final TransferService transfers;

    public DepositService(DSLContext dsl, TransferService transfers) {
        this.dsl = dsl;
        this.transfers = transfers;
    }

    public TransferResult deposit(String idempotencyKey, UUID accountId, long amount, String currency) {
        String hash = RequestHash.deposit(accountId, amount, currency);
        // The key's outcome wins over account checks, as it does in the Redis cache path.
        Optional<TransferResult> replay = transfers.findReplay(idempotencyKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Record2<Boolean, String> account = dsl.select(ACCOUNTS.IS_SYSTEM, ACCOUNTS.CURRENCY)
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(accountId))
                .fetchOne();
        if (account == null) {
            throw ApiException.notFound("account");
        }
        if (account.value1()) {
            throw ApiException.validation("cannot deposit into a system account");
        }
        // Fail before a system account is created for a currency nobody holds; the locked check stays authoritative.
        if (!currency.equals(account.value2().trim())) {
            throw ApiException.currencyMismatch();
        }
        UUID systemId = systemAccountId(currency);
        return transfers.transfer(new TransferCommand(idempotencyKey, hash, systemId, accountId, amount, currency, true));
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
