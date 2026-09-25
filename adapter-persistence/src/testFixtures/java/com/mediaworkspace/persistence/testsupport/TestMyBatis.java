package com.mediaworkspace.persistence.testsupport;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import javax.sql.DataSource;

/**
 * Builds a MyBatis factory with the same settings the applications use.
 *
 * <p>The mapper XML files are loaded from the compiled classpath, so a test exercises the very
 * statements that run in production rather than a parallel set written for the test.
 */
public final class TestMyBatis {

    private TestMyBatis() {
    }

    /** A factory that participates in Spring-managed transactions when one is active. */
    public static SqlSessionFactory forDataSource(DataSource dataSource) {
        Environment environment =
                new Environment("integration", new SpringManagedTransactionFactory(), dataSource);
        return new SqlSessionFactoryBuilder().build(configuration(environment));
    }

    /** A factory that commits each statement immediately, for setup performed outside a test case. */
    public static SqlSessionFactory autocommit(DataSource dataSource) {
        Environment environment = new Environment("integration-auto", new JdbcTransactionFactory(), dataSource);
        return new SqlSessionFactoryBuilder().build(configuration(environment));
    }

    private static Configuration configuration(Environment environment) {
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        // Immutable records are built by constructor argument name, which is why every mapper
        // aliases its columns to the exact record component names.
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setCacheEnabled(false);
        configuration.addMappers("com.mediaworkspace.persistence.mapper");
        return configuration;
    }
}
