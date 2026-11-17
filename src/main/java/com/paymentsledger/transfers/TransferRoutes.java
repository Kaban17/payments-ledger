package com.paymentsledger.transfers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.api.ApiException;
import com.paymentsledger.idempotency.CachedResponse;
import com.paymentsledger.idempotency.IdempotencyCache;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TransferRoutes {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 255;

    private final TransferService transfers;
    private final DepositService deposits;
    private final IdempotencyCache cache;
    private final ObjectMapper mapper;

    public TransferRoutes(
            TransferService transfers, DepositService deposits, IdempotencyCache cache, ObjectMapper mapper) {
        this.transfers = transfers;
        this.deposits = deposits;
        this.cache = cache;
        this.mapper = mapper;
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
        if (amount <= 0) {
            throw ApiException.validation("amount must be greater than 0");
        }
        if (from.equals(to)) {
            throw ApiException.validation("fromAccountId and toAccountId must differ");
        }
        String hash = RequestHash.transfer(from, to, amount, currency);
        if (serveFromCache(ctx, key, hash)) {
            return;
        }
        respond(ctx, key, hash, transfers.transfer(new TransferCommand(key, hash, from, to, amount, currency)));
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
        String hash = RequestHash.deposit(accountId, amount, currency);
        if (serveFromCache(ctx, key, hash)) {
            return;
        }
        respond(ctx, key, hash, deposits.deposit(key, accountId, amount, currency));
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

    /** Returns true if the response was written from the cache; throws 409 if the key belongs to another request. */
    private boolean serveFromCache(Context ctx, String key, String hash) {
        Optional<CachedResponse> hit = cache.get(key);
        if (hit.isEmpty()) {
            return false;
        }
        if (!hit.get().requestHash().equals(hash)) {
            throw ApiException.idempotencyKeyReused();
        }
        ctx.status(HttpStatus.OK).contentType("application/json").result(hit.get().body());
        return true;
    }

    private void respond(Context ctx, String key, String hash, TransferResult result) {
        String json;
        try {
            json = mapper.writeValueAsString(result.transfer());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        cache.put(key, hash, json);
        ctx.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .contentType("application/json")
                .result(json);
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
