package com.paymentsledger.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentsledger.api.ApiException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AccountRoutes {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final AccountRepository accounts;

    public AccountRoutes(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public void register(JavalinConfig cfg) {
        cfg.routes.post("/accounts", this::create);
        cfg.routes.get("/accounts/{id}", this::get);
    }

    private void create(Context ctx) {
        JsonNode body = ctx.bodyAsClass(JsonNode.class);
        JsonNode currency = body == null ? null : body.get("currency");
        if (currency == null || !currency.isTextual() || !CURRENCY.matcher(currency.asText()).matches()) {
            throw ApiException.validation("currency must be three uppercase letters");
        }
        ctx.status(HttpStatus.CREATED).json(accounts.createCustomer(currency.asText()));
    }

    private void get(Context ctx) {
        UUID id;
        try {
            id = UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("id must be a valid UUID");
        }
        ctx.json(accounts.find(id).orElseThrow(() -> ApiException.notFound("account")));
    }
}
