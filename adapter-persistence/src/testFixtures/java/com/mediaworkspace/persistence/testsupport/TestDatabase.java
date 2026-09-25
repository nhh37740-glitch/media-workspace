package com.mediaworkspace.persistence.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A schema created for one test run, migrated with the production Flyway scripts.
 *
 * <p>The migrations are the same files the application uses, so a schema defect shows up here
 * rather than only in the demo deployment. Teardown drops the run's own schema and nothing else.
 */
public final class TestDatabase implements AutoCloseable {

    private final IntegrationEnvironment environment;
    private final HikariDataSource dataSource;

    private TestDatabase(IntegrationEnvironment environment, HikariDataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    /** Creates the schema, runs the migrations and returns a pool bound to it. */
    public static TestDatabase start(IntegrationEnvironment environment) {
        try (Connection admin = DriverManager.getConnection(
                environment.adminJdbcUrl(), environment.username(), environment.password());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + environment.schema()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create the run schema " + environment.schema(), e);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(environment.jdbcUrl(), environment.username(), environment.password())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
        flyway.migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(environment.jdbcUrl());
        config.setUsername(environment.username());
        config.setPassword(environment.password());
        config.setMaximumPoolSize(4);
        config.setPoolName("mw-it-" + environment.schema());
        return new TestDatabase(environment, new HikariDataSource(config));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    public IntegrationEnvironment environment() {
        return environment;
    }

    /** Empties every table this project owns, keeping the schema and its version row. */
    public void truncateAll() {
        JdbcTemplate template = jdbc();
        template.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[] {
                "task_attempt", "processing_task", "media", "upload_chunk", "upload_session",
                "share_session", "share_link", "audit_event", "idempotency_record",
                "outbox_event", "inbox_event", "poison_message", "workspace_member", "workspace",
                "app_user"}) {
            template.execute("TRUNCATE TABLE " + table);
        }
        template.execute("SET FOREIGN_KEY_CHECKS = 1");
        template.update("UPDATE capacity_counter SET active_count = 0 WHERE name = 'processing'");
    }

    @Override
    public void close() {
        dataSource.close();
        try (Connection admin = DriverManager.getConnection(
                environment.adminJdbcUrl(), environment.username(), environment.password());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + environment.schema() + "`");
        } catch (SQLException e) {
            // Leaving the schema behind is untidy but harmless: it is isolated and named per run.
            System.err.println("could not drop the run schema " + environment.schema() + ": " + e.getMessage());
        }
    }
}
