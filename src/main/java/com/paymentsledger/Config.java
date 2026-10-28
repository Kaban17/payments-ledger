package com.paymentsledger;

public record Config(String dbUrl, String dbUser, String dbPassword, String redisUrl, int port) {

    public static Config fromEnv() {
        return new Config(
                env("DB_URL", "jdbc:postgresql://localhost:5433/ledger"),
                env("DB_USER", "ledger"),
                env("DB_PASSWORD", "ledger"),
                env("REDIS_URL", "redis://localhost:6379"),
                Integer.parseInt(env("PORT", "8080")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
