package com.paymentsledger.transfers;

import java.util.UUID;

public record TransferCommand(
        String idempotencyKey,
        String requestHash,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency,
        boolean allowSystemAccounts) {

    /** A command from the public API: system accounts may not take part. */
    public TransferCommand(
            String idempotencyKey,
            String requestHash,
            UUID fromAccountId,
            UUID toAccountId,
            long amount,
            String currency) {
        this(idempotencyKey, requestHash, fromAccountId, toAccountId, amount, currency, false);
    }
}
