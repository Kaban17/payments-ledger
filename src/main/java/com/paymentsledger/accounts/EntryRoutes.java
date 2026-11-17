package com.paymentsledger.accounts;

import com.paymentsledger.api.ApiException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.util.UUID;

public final class EntryRoutes {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AccountRepository accounts;
    private final EntryRepository entries;

    public EntryRoutes(AccountRepository accounts, EntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    public void register(JavalinConfig cfg) {
        cfg.routes.get("/accounts/{id}/entries", this::list);
    }

    private void list(Context ctx) {
        UUID id;
        try {
            id = UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("id must be a valid UUID");
        }
        int limit = DEFAULT_LIMIT;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                throw ApiException.validation("limit must be an integer between 1 and " + MAX_LIMIT);
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw ApiException.validation("limit must be an integer between 1 and " + MAX_LIMIT);
            }
        }
        Long cursor = null;
        String cursorParam = ctx.queryParam("cursor");
        if (cursorParam != null) {
            try {
                cursor = Long.parseLong(cursorParam);
            } catch (NumberFormatException e) {
                throw ApiException.validation("cursor must be a non-negative integer");
            }
            if (cursor < 0) {
                throw ApiException.validation("cursor must be a non-negative integer");
            }
        }
        accounts.find(id).orElseThrow(() -> ApiException.notFound("account"));
        ctx.json(entries.page(id, limit, cursor));
    }
}
