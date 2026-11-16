package com.paymentsledger.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Public view of an account. Balance is in minor units. */
public record Account(UUID id, String currency, long balance, OffsetDateTime createdAt) {}
