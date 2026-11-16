package com.paymentsledger.transfers;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Transfer(
        UUID id, UUID fromAccountId, UUID toAccountId, long amount, String currency, OffsetDateTime createdAt) {}
