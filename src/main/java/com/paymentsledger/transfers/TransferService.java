package com.paymentsledger.transfers;

import static com.paymentsledger.jooq.Tables.ACCOUNTS;
import static com.paymentsledger.jooq.Tables.LEDGER_ENTRIES;
import static com.paymentsledger.jooq.Tables.TRANSFERS;

import com.paymentsledger.api.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * Moves money between two accounts. Both account rows are locked with one ordered
 * {@code SELECT ... ORDER BY id FOR UPDATE}, so concurrent transfers acquire locks in the same
 * order and cannot deadlock.
 */
public final class TransferService {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String IDEMPOTENCY_CONSTRAINT = "transfers_idempotency_key_uq";

    private final DSLContext dsl;

    public TransferService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public TransferResult transfer(TransferCommand cmd) {
        validate(cmd);

        Optional<StoredTransfer> existing = findByKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }

        try {
            return dsl.transactionResult(tx -> execute(tx.dsl(), cmd));
        } catch (DataAccessException e) {
            if (isIdempotencyViolation(e)) {
                StoredTransfer winner = findByKey(cmd.idempotencyKey()).orElseThrow(() -> e);
                return replay(winner, cmd);
            }
            throw e;
        }
    }

    public Optional<Transfer> findById(UUID id) {
        return dsl.selectFrom(TRANSFERS)
                .where(TRANSFERS.ID.eq(id))
                .fetchOptional(TransferService::toTransfer);
    }

    private static void validate(TransferCommand cmd) {
        if (cmd.amount() <= 0) {
            throw ApiException.validation("amount must be greater than 0");
        }
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw ApiException.validation("fromAccountId and toAccountId must differ");
        }
        if (cmd.currency() == null || !CURRENCY.matcher(cmd.currency()).matches()) {
            throw ApiException.validation("currency must be three uppercase letters");
        }
    }

    private TransferResult execute(DSLContext tx, TransferCommand cmd) {
        List<Record4<UUID, String, Long, Boolean>> locked = tx
                .select(ACCOUNTS.ID, ACCOUNTS.CURRENCY, ACCOUNTS.BALANCE, ACCOUNTS.IS_SYSTEM)
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.in(cmd.fromAccountId(), cmd.toAccountId()))
                .orderBy(ACCOUNTS.ID)
                .forUpdate()
                .fetch();

        // A same-key request may have committed while we waited for the locks; it must be replayed
        // (or rejected as a key reuse) before any balance check sees its effects.
        Optional<StoredTransfer> winner = findByKey(tx, cmd.idempotencyKey());
        if (winner.isPresent()) {
            return replay(winner.get(), cmd);
        }

        if (locked.size() < 2) {
            throw ApiException.notFound("account");
        }

        Record4<UUID, String, Long, Boolean> source = null;
        for (Record4<UUID, String, Long, Boolean> row : locked) {
            if (!cmd.currency().equals(row.value2().trim())) {
                throw ApiException.currencyMismatch();
            }
            if (row.value1().equals(cmd.fromAccountId())) {
                source = row;
            }
        }
        if (!source.value4() && source.value3() < cmd.amount()) {
            throw ApiException.insufficientFunds();
        }

        UUID transferId = UUID.randomUUID();
        Transfer transfer = tx.insertInto(TRANSFERS)
                .set(TRANSFERS.ID, transferId)
                .set(TRANSFERS.IDEMPOTENCY_KEY, cmd.idempotencyKey())
                .set(TRANSFERS.REQUEST_HASH, cmd.requestHash())
                .set(TRANSFERS.FROM_ACCOUNT_ID, cmd.fromAccountId())
                .set(TRANSFERS.TO_ACCOUNT_ID, cmd.toAccountId())
                .set(TRANSFERS.AMOUNT, cmd.amount())
                .set(TRANSFERS.CURRENCY, cmd.currency())
                .returning()
                .fetchOne(TransferService::toTransfer);

        tx.insertInto(LEDGER_ENTRIES, LEDGER_ENTRIES.TRANSFER_ID, LEDGER_ENTRIES.ACCOUNT_ID, LEDGER_ENTRIES.AMOUNT)
                .values(transferId, cmd.fromAccountId(), -cmd.amount())
                .values(transferId, cmd.toAccountId(), cmd.amount())
                .execute();

        tx.update(ACCOUNTS)
                .set(ACCOUNTS.BALANCE, ACCOUNTS.BALANCE.minus(cmd.amount()))
                .where(ACCOUNTS.ID.eq(cmd.fromAccountId()))
                .execute();
        tx.update(ACCOUNTS)
                .set(ACCOUNTS.BALANCE, ACCOUNTS.BALANCE.plus(cmd.amount()))
                .where(ACCOUNTS.ID.eq(cmd.toAccountId()))
                .execute();

        return new TransferResult(transfer, false);
    }

    private TransferResult replay(StoredTransfer stored, TransferCommand cmd) {
        if (!stored.requestHash().equals(cmd.requestHash())) {
            throw ApiException.idempotencyKeyReused();
        }
        return new TransferResult(stored.transfer(), true);
    }

    private Optional<StoredTransfer> findByKey(String key) {
        return findByKey(dsl, key);
    }

    private static Optional<StoredTransfer> findByKey(DSLContext dsl, String key) {
        return dsl.selectFrom(TRANSFERS)
                .where(TRANSFERS.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(r -> new StoredTransfer(toTransfer(r), r.getRequestHash()));
    }

    private static boolean isIdempotencyViolation(DataAccessException e) {
        PSQLException pg = e.getCause(PSQLException.class);
        return pg != null
                && UNIQUE_VIOLATION.equals(pg.getSQLState())
                && pg.getServerErrorMessage() != null
                && IDEMPOTENCY_CONSTRAINT.equals(pg.getServerErrorMessage().getConstraint());
    }

    private static Transfer toTransfer(com.paymentsledger.jooq.tables.records.TransfersRecord r) {
        return new Transfer(
                r.getId(),
                r.getFromAccountId(),
                r.getToAccountId(),
                r.getAmount(),
                r.getCurrency().trim(),
                r.getCreatedAt());
    }

    private record StoredTransfer(Transfer transfer, String requestHash) {}
}
