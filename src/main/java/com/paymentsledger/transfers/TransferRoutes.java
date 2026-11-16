package com.paymentsledger.transfers;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.api.ApiException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TransferRoutes {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 255;

    private final TransferService transfers;
    private final DepositService deposits;

    public TransferRoutes(TransferService transfers, DepositService deposits) {
        this.transfers = transfers;
        this.deposits = deposits;
    }

    public void register(JavalinConfig cfg) {
        cfg.routes.post("/transfers", this::transfer);
        cfg.routes.post("/deposits", this::deposit);
        cfg.routes.get("/transfers/{id}", this::get);
    }

    private void transfer(Context ctx) {
        String key = idempotencyKey(ctx);
        JsonNode body = body(ctx);
        UUID from = uuid(body, "fromAccountId");
        UUID to = uuid(body, "toAccountId");
        long amount = amount(body);
        String currency = currency(body);
        respond(ctx, transfers.transfer(new TransferCommand(
                key, RequestHash.transfer(from, to, amount, currency), from, to, amount, currency)));
    }

    private void deposit(Context ctx) {
        String key = idempotencyKey(ctx);
        JsonNode body = body(ctx);
        UUID accountId = uuid(body, "accountId");
        long amount = amount(body);
        String currency = currency(body);
        if (amount <= 0) {
            throw ApiException.validation("amount must be greater than 0");
        }
        respond(ctx, deposits.deposit(key, accountId, amount, currency));
    }

    private void get(Context ctx) {
        UUID id;
        try {
            id = UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("id must be a valid UUID");
        }
        ctx.json(transfers.findById(id).orElseThrow(() -> ApiException.notFound("transfer")));
    }

    private static void respond(Context ctx, TransferResult result) {
        ctx.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).json(result.transfer());
    }

    private static String idempotencyKey(Context ctx) {
        String key = ctx.header(KEY_HEADER);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(
                    400,
                    "idempotency_key_required",
                    KEY_HEADER + " header is required and must be 1-" + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static JsonNode body(Context ctx) {
        if (ctx.body().isBlank()) {
            throw ApiException.validation("Request body must be a JSON object");
        }
        JsonNode body = ctx.bodyAsClass(JsonNode.class);
        if (body == null || !body.isObject()) {
            throw ApiException.validation("Request body must be a JSON object");
        }
        return body;
    }

    private static UUID uuid(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) {
            throw ApiException.validation(field + " must be a UUID string");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(field + " must be a valid UUID");
        }
    }

    private static long amount(JsonNode body) {
        JsonNode node = body.get("amount");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw ApiException.validation("amount must be an integer number of minor units");
        }
        return node.longValue();
    }

    private static String currency(JsonNode body) {
        JsonNode node = body.get("currency");
        if (node == null || !node.isTextual() || !CURRENCY.matcher(node.asText()).matches()) {
            throw ApiException.validation("currency must be three uppercase letters");
        }
        return node.asText();
    }
}
