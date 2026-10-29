package com.bms.support;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application on a random port, backed by a real PostgreSQL server (zonky embedded binaries) with the
 * Flyway migrations applied - no mocks of the database, security chain or transaction handling.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bms.db-mode=external",
        "server.address=127.0.0.1",
        "management.server.port=0",
        "bms.security.internal-api-token=test-internal-token-0123456789abcdef",
        "bms.security.max-failed-attempts=3",
        "bms.security.rate-limit.login-per-minute=1000",
        "bms.security.rate-limit.money-per-minute=10000",
        "bms.security.rate-limit.api-per-minute=100000",
        "bms.analytics.service-token=",
        "bms.demo.seed=false",
        "bms.bootstrap-admin.username=",
        "spring.datasource.hikari.maximum-pool-size=30"
})
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY,
        refresh = AutoConfigureEmbeddedDatabase.RefreshMode.NEVER)
@Import(TestData.class)
public @interface IntegrationTest {
}
