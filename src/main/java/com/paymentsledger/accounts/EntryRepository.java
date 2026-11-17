package com.paymentsledger.accounts;

import static com.paymentsledger.jooq.Tables.LEDGER_ENTRIES;

import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

public final class EntryRepository {

    private final DSLContext dsl;

    public EntryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Newest-first page of at most {@code limit} entries with id below {@code cursor} (if given). */
    public EntryPage page(UUID accountId, int limit, Long cursor) {
        Condition where = LEDGER_ENTRIES.ACCOUNT_ID.eq(accountId);
        if (cursor != null) {
            where = where.and(LEDGER_ENTRIES.ID.lt(cursor));
        }
        List<Entry> rows = dsl.select(LEDGER_ENTRIES.ID, LEDGER_ENTRIES.TRANSFER_ID, LEDGER_ENTRIES.AMOUNT, LEDGER_ENTRIES.CREATED_AT)
                .from(LEDGER_ENTRIES)
                .where(where)
                .orderBy(LEDGER_ENTRIES.ID.desc())
                .limit(limit + 1)
                .fetch(r -> new Entry(
                        r.get(LEDGER_ENTRIES.ID),
                        r.get(LEDGER_ENTRIES.TRANSFER_ID),
                        r.get(LEDGER_ENTRIES.AMOUNT),
                        r.get(LEDGER_ENTRIES.CREATED_AT)));
        if (rows.size() <= limit) {
            return new EntryPage(rows, null);
        }
        List<Entry> entries = rows.subList(0, limit);
        return new EntryPage(entries, String.valueOf(entries.get(limit - 1).id()));
    }
}
