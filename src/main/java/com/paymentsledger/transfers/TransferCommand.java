package com.paymentsledger.transfers;

import java.util.UUID;

public record TransferCommand(
        String idempotencyKey,
        String requestHash,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency) {}
