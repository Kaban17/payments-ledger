package com.paymentsledger.transfers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Lowercase hex SHA-256 of a canonical string, so JSON key order and whitespace never matter. */
public final class RequestHash {

    private RequestHash() {}

    public static String transfer(UUID from, UUID to, long amount, String currency) {
        return sha256("transfer|" + from + "|" + to + "|" + amount + "|" + currency);
    }

    public static String deposit(UUID accountId, long amount, String currency) {
        return sha256("deposit|" + accountId + "|" + amount + "|" + currency);
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
