package com.mediaworkspace.persistence.testsupport;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Connection settings for the isolated integration environment.
 *
 * <p>Every test run gets its own schema and its own storage root, so a run can never read, modify
 * or delete the demonstration data. Nothing here is ever allowed to point at the demo schema: the
 * schema name is generated per run and the demo names are rejected outright.
 *
 * <p>Values come from the process environment so no credential is written into the repository or a
 * test resource file.
 */
public final class IntegrationEnvironment {

    /** Schema names this harness must never touch. */
    private static final Map<String, String> FORBIDDEN_SCHEMAS =
            Map.of("media_workspace", "the demo schema", "media_workspace_test", "the shared test schema");

    private final String jdbcUrl;
    private final String adminJdbcUrl;
    private final String username;
    private final String password;
    private final String schema;
    private final Path storageRoot;
    private final String kafkaBootstrap;
    private final String topicPrefix;

    private IntegrationEnvironment(String jdbcUrl, String adminJdbcUrl, String username, String password,
                                   String schema, Path storageRoot, String kafkaBootstrap,
                                   String topicPrefix) {
        this.jdbcUrl = jdbcUrl;
        this.adminJdbcUrl = adminJdbcUrl;
        this.username = username;
        this.password = password;
        this.schema = schema;
        this.storageRoot = storageRoot;
        this.kafkaBootstrap = kafkaBootstrap;
        this.topicPrefix = topicPrefix;
    }

    public static IntegrationEnvironment create(String runTag) {
        String host = env("MW_DB_HOST", "127.0.0.1");
        String port = env("MW_DB_PORT", "3306");
        String username = env("MW_DB_USER", "media_app");
        String password = requireEnv("MW_DB_PASSWORD");
        String baseUrl = "jdbc:mysql://" + host + ":" + port + "/";
        String schema = schemaName(runTag);
        return new IntegrationEnvironment(
                baseUrl + schema + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                        + "&sessionVariables=transaction_isolation='READ-COMMITTED'"
                        + "&characterEncoding=UTF-8&rewriteBatchedStatements=true",
                baseUrl + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                username, password, schema,
                Paths.get(env("MW_IT_STORAGE_ROOT", System.getProperty("java.io.tmpdir") + "/mw-it"))
                        .resolve(schema),
                env("MW_KAFKA_BOOTSTRAP", "127.0.0.1:9092"),
                "mw-it-" + schema.replace("mw_it_", "") + "-");
    }

    private static String schemaName(String runTag) {
        String suffix = (runTag == null ? "" : runTag.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String schema = "mw_it_" + suffix;
        if (FORBIDDEN_SCHEMAS.containsKey(schema)) {
            throw new IllegalStateException("refusing to run against " + FORBIDDEN_SCHEMAS.get(schema));
        }
        return schema;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "required integration environment variable " + key + " is not set; "
                            + "integration tests read credentials from the environment, never from the repository");
        }
        return value;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** URL without a schema, used to create and drop the run's own schema. */
    public String adminJdbcUrl() {
        return adminJdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String schema() {
        return schema;
    }

    public Path storageRoot() {
        return storageRoot;
    }

    public String kafkaBootstrap() {
        return kafkaBootstrap;
    }

    public String topicPrefix() {
        return topicPrefix;
    }
}
