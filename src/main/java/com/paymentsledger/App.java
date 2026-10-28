package com.paymentsledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import java.util.Map;
import org.jooq.DSLContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Wires infrastructure and routes. Tests build one with {@link #create(Config)} and start it on port 0. */
public final class App {

    private final Javalin javalin;
    private final HikariDataSource dataSource;
    private final JedisPool redis;
    private final DSLContext dsl;

    private App(Javalin javalin, HikariDataSource dataSource, JedisPool redis, DSLContext dsl) {
        this.javalin = javalin;
        this.dataSource = dataSource;
        this.redis = redis;
        this.dsl = dsl;
    }

    public static App create(Config config) {
        HikariDataSource dataSource = Database.dataSource(config);
        Database.migrate(dataSource);
        DSLContext dsl = Database.dsl(dataSource);
        JedisPool redis = new JedisPool(config.redisUrl());

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Javalin javalin = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(mapper, false));
        });

        javalin.get("/health", ctx -> {
            boolean dbUp = check(() -> dsl.selectOne().fetch());
            boolean redisUp = check(() -> {
                try (Jedis jedis = redis.getResource()) {
                    jedis.ping();
                }
            });
            boolean up = dbUp && redisUp;
            ctx.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                    .json(Map.of(
                            "status", up ? "ok" : "degraded",
                            "postgres", dbUp ? "up" : "down",
                            "redis", redisUp ? "up" : "down"));
        });

        return new App(javalin, dataSource, redis, dsl);
    }

    public App start(int port) {
        javalin.start(port);
        return this;
    }

    public int port() {
        return javalin.port();
    }

    public DSLContext dsl() {
        return dsl;
    }

    public void stop() {
        javalin.stop();
        redis.close();
        dataSource.close();
    }

    private static boolean check(Runnable probe) {
        try {
            probe.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
