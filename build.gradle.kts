plugins {
    java
    groovy
    application
    id("org.jooq.jooq-codegen-gradle") version "3.19.15"
}

group = "com.paymentsledger"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

val jooqVersion = "3.19.15"

dependencies {
    implementation("io.javalin:javalin:6.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("redis.clients:jedis:5.2.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    jooqCodegen("org.jooq:jooq-meta-extensions:$jooqVersion")

    testImplementation(platform("org.spockframework:spock-bom:2.3-groovy-4.0"))
    testImplementation("org.spockframework:spock-core")
    testImplementation("org.apache.groovy:groovy:4.0.24")
    testImplementation("org.apache.groovy:groovy-json:4.0.24")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.2"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers")
}

// Generate jOOQ classes straight from the Flyway migrations, so no live database is needed.
jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "${projectDir}/src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            target {
                packageName = "com.paymentsledger.jooq"
                directory = "build/generated-sources/jooq"
            }
        }
    }
}

sourceSets {
    main { java.srcDir("build/generated-sources/jooq") }
}

tasks.named("compileJava") { dependsOn("jooqCodegen") }

tasks.named("jooqCodegen") {
    inputs.dir("src/main/resources/db/migration")
    outputs.dir("build/generated-sources/jooq")
}

// On macOS the JDK imports the system SOCKS proxy (e.g. from a VPN app), whose bypass list usually
// lacks localhost. pgjdbc then refuses to resolve "localhost" itself and the connection fails, so
// always bypass the proxy for local addresses.
val localNoProxy = "socksNonProxyHosts=localhost|127.*|[::1]|local|*.local|169.254/16"

application {
    mainClass = "com.paymentsledger.Main"
    applicationDefaultJvmArgs = listOf("-D$localNoProxy")
}

tasks.test {
    jvmArgs("-D$localNoProxy")
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
