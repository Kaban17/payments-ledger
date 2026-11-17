package com.paymentsledger.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Best-effort response cache in front of Postgres. Every Redis failure degrades to a miss or a skipped write. */
public final class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    private static final String PREFIX = "idem:";
    private static final long TTL_SECONDS = 86400;

    private final JedisPool redis;
    private final ObjectMapper mapper;

    public IdempotencyCache(JedisPool redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Optional<CachedResponse> get(String key) {
        try (Jedis jedis = redis.getResource()) {
            String value = jedis.get(PREFIX + key);
            return value == null ? Optional.empty() : Optional.of(mapper.readValue(value, CachedResponse.class));
        } catch (Exception e) {
            log.warn("Idempotency cache read failed for key {}: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public void put(String key, String requestHash, String body) {
        try (Jedis jedis = redis.getResource()) {
            jedis.setex(PREFIX + key, TTL_SECONDS, mapper.writeValueAsString(new CachedResponse(requestHash, body)));
        } catch (Exception e) {
            log.warn("Idempotency cache write failed for key {}: {}", key, e.toString());
        }
    }
}
