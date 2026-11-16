package com.paymentsledger.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers handlers that turn every failure into a JSON {"error","message"} response. */
public final class ErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    private ErrorHandling() {}

    public static void register(JavalinConfig cfg) {
        cfg.routes.exception(ApiException.class, (e, ctx) -> respond(ctx, e.status(), e.code(), e.getMessage()));
        cfg.routes.exception(
                JsonProcessingException.class,
                (e, ctx) -> respond(ctx, 400, "validation_failed", "Request body is not valid JSON"));
        cfg.routes.exception(HttpResponseException.class, (e, ctx) -> {
            if (e.getStatus() == 400) {
                respond(ctx, 400, "validation_failed", "Malformed request");
            } else {
                log.error("Unexpected HTTP error {}", e.getStatus(), e);
                respond(ctx, 500, "internal_error", "Internal server error");
            }
        });
        cfg.routes.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            respond(ctx, 500, "internal_error", "Internal server error");
        });
    }

    private static void respond(Context ctx, int status, String code, String message) {
        ctx.status(status).json(Map.of("error", code, "message", message));
    }
}
