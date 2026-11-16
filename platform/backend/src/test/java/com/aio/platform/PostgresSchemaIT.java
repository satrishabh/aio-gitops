package com.aio.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.aio.platform.saga.SagaInstanceRepo;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs every Flyway migration against a real PostgreSQL, then lets Hibernate validate that each entity matches the
 * migrated schema (ddl-auto=validate). Opt-in, never against a real database:
 * <ul>
 *   <li>{@code AIO_IT_EMBEDDED_PG=true mvn test -Dtest=PostgresSchemaIT}: a throwaway embedded PostgreSQL 16 (no Docker);</li>
 *   <li>{@code AIO_IT_DB_URL=jdbc:postgresql://localhost:55432/aio}: a local container (localhost only).</li>
 * </ul>
 */
@EnabledIf("enabled")
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostgresSchemaIT {
    private static EmbeddedPostgres embedded;

    static boolean enabled() {
        String url = System.getenv("AIO_IT_DB_URL");
        return "true".equals(System.getenv("AIO_IT_EMBEDDED_PG"))
                || (url != null && url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)[:/].*"));
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) throws Exception {
        if ("true".equals(System.getenv("AIO_IT_EMBEDDED_PG"))) {
            embedded = EmbeddedPostgres.builder().start();
            r.add("spring.datasource.url", () -> embedded.getJdbcUrl("postgres", "postgres"));
            r.add("spring.datasource.username", () -> "postgres");
            r.add("spring.datasource.password", () -> "postgres");
        } else {
            r.add("spring.datasource.url", () -> System.getenv("AIO_IT_DB_URL"));
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("AIO_IT_DB_USER", "aio"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("AIO_IT_DB_PASSWORD", "aio"));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (embedded != null) embedded.close();   // nothing is left running after the test
    }

    @Autowired DataSource dataSource;
    @Autowired SagaInstanceRepo sagas;

    @Test
    void allMigrationsApplyAndEntitiesMatchTheSchema() {
        var info = Flyway.configure().dataSource(dataSource).load().info();
        assertThat(info.pending()).isEmpty();
        assertThat(info.current().getVersion().getVersion()).isEqualTo("12");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("PostgreSQL " + jdbc.queryForObject("show server_version", String.class)
                + ", schema version " + info.current().getVersion());
        assertThat(jdbc.queryForObject("select pg_get_indexdef('ix_saga_due'::regclass)", String.class)).contains("PAUSED");
        assertThat(sagas.nextDue()).isNull();   // the saga worker's query runs on PostgreSQL
    }
}
