package io.github.rizkywahyus.poolwarden.core.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.junit.jupiter.params.provider.Arguments;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Builds the three connection pools the warden claims to support, each backed by its own
 * in-memory H2 database and capped at a known size so exhaustion is easy to provoke.
 */
final class TestPools {

    /** Small on purpose: the whole point is to fill the pool and watch it recover. */
    static final int MAX_POOL_SIZE = 2;
    /** How long a borrower waits for a free slot before the pool gives up. */
    static final int CHECKOUT_TIMEOUT_MS = 2_000;

    private static final String DRIVER = "org.h2.Driver";
    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    private TestPools() {
    }

    /** A pool implementation under test, together with the name shown in the test report. */
    interface PoolFactory {
        DataSource create(String jdbcUrl);
    }

    static Stream<Arguments> all() {
        return Stream.of(
                Arguments.of("HikariCP", (PoolFactory) TestPools::hikari),
                Arguments.of("Tomcat JDBC", (PoolFactory) TestPools::tomcat),
                Arguments.of("Commons DBCP2", (PoolFactory) TestPools::dbcp2));
    }

    /**
     * A fresh database per test so pools never share state. {@code DB_CLOSE_DELAY=-1} keeps it
     * alive while the pool is empty, which happens whenever every connection has been reaped.
     */
    static String newDatabaseUrl(String label) {
        String name = label.replaceAll("[^A-Za-z0-9]", "") + DATABASE_COUNTER.incrementAndGet();
        return "jdbc:h2:mem:poolwarden" + name + ";DB_CLOSE_DELAY=-1";
    }

    static void close(DataSource dataSource) throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static DataSource hikari(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(DRIVER);
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setConnectionTimeout(CHECKOUT_TIMEOUT_MS);
        // Hikari skips its liveness check for connections used very recently; a reaped
        // connection is dead the moment it goes back, so the check must always run.
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }

    private static DataSource tomcat(String jdbcUrl) {
        PoolProperties properties = new PoolProperties();
        properties.setUrl(jdbcUrl);
        properties.setDriverClassName(DRIVER);
        properties.setMaxActive(MAX_POOL_SIZE);
        properties.setMaxIdle(MAX_POOL_SIZE);
        properties.setMaxWait(CHECKOUT_TIMEOUT_MS);
        properties.setTestOnBorrow(true);
        properties.setValidationQuery("SELECT 1");
        // Tomcat has its own abandoned-connection reclaim; leave it off so the test measures
        // what pool-warden does, not what the pool would have done anyway.
        properties.setRemoveAbandoned(false);
        return new org.apache.tomcat.jdbc.pool.DataSource(properties);
    }

    private static DataSource dbcp2(String jdbcUrl) {
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setDriverClassName(DRIVER);
        dataSource.setMaxTotal(MAX_POOL_SIZE);
        dataSource.setMaxWait(java.time.Duration.ofMillis(CHECKOUT_TIMEOUT_MS));
        dataSource.setTestOnBorrow(true);
        dataSource.setValidationQuery("SELECT 1");
        dataSource.setRemoveAbandonedOnBorrow(false);
        dataSource.setRemoveAbandonedOnMaintenance(false);
        return dataSource;
    }
}
