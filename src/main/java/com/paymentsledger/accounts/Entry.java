package com.paymentsledger.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One signed ledger entry on an account, in minor units. */
public record Entry(long id, UUID transferId, long amount, OffsetDateTime createdAt) {}
