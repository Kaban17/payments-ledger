plugins {
    java
    groovy
    application
    id("org.jooq.jooq-codegen-gradle") version "3.21.9"
}

group = "com.paymentsledger"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

val jooqVersion = "3.21.9"
val jacksonVersion = "2.23.0"
val flywayVersion = "13.12.2"
val groovyVersion = "5.1.3"

dependencies {
    implementation("io.javalin:javalin:7.2.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("redis.clients:jedis:8.0.2")
    implementation("ch.qos.logback:logback-classic:1.6.7")

    jooqCodegen("org.jooq:jooq-meta-extensions:$jooqVersion")

    testImplementation(platform("org.spockframework:spock-bom:2.5-groovy-5.0"))
    testImplementation("org.spockframework:spock-core")
    testImplementation("org.apache.groovy:groovy:$groovyVersion")
    testImplementation("org.apache.groovy:groovy-json:$groovyVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
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
