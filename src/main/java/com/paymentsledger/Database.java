package com.paymentsledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

public final class Database {

    private Database() {}

    public static HikariDataSource dataSource(Config config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.dbUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(20);
        hikari.setPoolName("ledger");
        return new HikariDataSource(hikari);
    }

    public static void migrate(HikariDataSource dataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    public static DSLContext dsl(HikariDataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }
}
