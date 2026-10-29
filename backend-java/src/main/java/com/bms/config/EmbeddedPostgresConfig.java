package com.bms.config;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DEVELOPMENT ONLY. With DB_MODE=embedded the backend starts a genuine PostgreSQL server binary
 * (packaged by zonky) bound to localhost, with a persistent data directory. This lets the project run
 * on machines without Docker or a PostgreSQL installation. It is never used in external mode.
 */
@Configuration
@ConditionalOnProperty(name = "bms.db-mode", havingValue = "embedded")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);
    private static final String DB_NAME = "bms";

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(BmsProperties props) throws IOException {
        Path dataDir = Path.of(props.embeddedDb().dataDir()).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        log.warn("DB_MODE=embedded: starting development PostgreSQL on 127.0.0.1:{} (data: {})",
                props.embeddedDb().port(), dataDir);
        return EmbeddedPostgres.builder()
                .setPort(props.embeddedDb().port())
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setServerConfig("timezone", "UTC")
                .start();
    }

    @Bean
    public DataSource dataSource(EmbeddedPostgres pg, BmsProperties props) throws SQLException {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB_NAME + "'")) {
                if (!rs.next()) {
                    st.execute("CREATE DATABASE " + DB_NAME);
                    log.info("Created development database '{}'", DB_NAME);
                }
            }
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + props.embeddedDb().port() + "/" + DB_NAME);
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(20);
        return ds;
    }
}
