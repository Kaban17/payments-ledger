package com.paymentsledger.idempotency;

/** What is stored in Redis for an idempotency key: the request fingerprint and the exact response body. */
public record CachedResponse(String requestHash, String body) {}
