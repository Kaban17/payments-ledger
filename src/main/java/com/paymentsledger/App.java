package com.paymentsledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paymentsledger.accounts.AccountRepository;
import com.paymentsledger.accounts.AccountRoutes;
import com.paymentsledger.accounts.EntryRepository;
import com.paymentsledger.accounts.EntryRoutes;
import com.paymentsledger.api.ErrorHandling;
import com.paymentsledger.idempotency.IdempotencyCache;
import com.paymentsledger.transfers.DepositService;
import com.paymentsledger.transfers.TransferRoutes;
import com.paymentsledger.transfers.TransferService;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.jooq.DSLContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

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
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxWait(Duration.ofMillis(200));
        JedisPool redis = new JedisPool(poolConfig, URI.create(config.redisUrl()), 500, 500);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        TransferService transferService = new TransferService(dsl);
        DepositService depositService = new DepositService(dsl, transferService);

        Javalin javalin = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(mapper, false));
            ErrorHandling.register(cfg);
            AccountRepository accountRepository = new AccountRepository(dsl);
            new AccountRoutes(accountRepository).register(cfg);
            new EntryRoutes(accountRepository, new EntryRepository(dsl)).register(cfg);
            new TransferRoutes(transferService, depositService, new IdempotencyCache(redis, mapper), mapper).register(cfg);

            cfg.routes.get("/health", ctx -> {
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
