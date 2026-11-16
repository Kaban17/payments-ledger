package com.paymentsledger.api;

/** An error that maps directly to an HTTP status and a {"error","message"} response body. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException validation(String message) {
        return new ApiException(400, "validation_failed", message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " not found");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(
                409, "idempotency_key_reused", "Idempotency-Key was already used with a different request");
    }

    public static ApiException insufficientFunds() {
        return new ApiException(422, "insufficient_funds", "Insufficient funds");
    }

    public static ApiException currencyMismatch() {
        return new ApiException(422, "currency_mismatch", "Currency does not match the accounts' currency");
    }
}
