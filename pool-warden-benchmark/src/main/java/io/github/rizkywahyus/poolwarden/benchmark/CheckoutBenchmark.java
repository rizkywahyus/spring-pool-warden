package io.github.rizkywahyus.poolwarden.benchmark;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Measures what wrapping a data source costs on the checkout path: one {@code getConnection()}
 * plus the matching {@code close()}.
 *
 * <p>Two baselines on purpose. The stub data source strips everything but the warden, so the
 * numbers are the proxy's own cost; the HikariCP + H2 pair shows what that cost looks like next
 * to a real checkout, which is the figure that decides whether the warden is affordable.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class CheckoutBenchmark {

    private DataSource stub;
    private DataSource stubWarden;
    private DataSource stubWardenWithCallSite;
    private HikariDataSource hikari;
    private DataSource hikariWarden;

    @Setup
    public void setUp() {
        stub = new StubDataSource();
        stubWarden = warden(stub, false);
        stubWardenWithCallSite = warden(stub, true);
        hikari = hikari();
        hikariWarden = warden(hikari, false);
    }

    @TearDown
    public void tearDown() {
        hikari.close();
    }

    @Benchmark
    public void stubBaseline() throws SQLException {
        checkout(stub);
    }

    @Benchmark
    public void stubThroughWarden() throws SQLException {
        checkout(stubWarden);
    }

    @Benchmark
    public void stubThroughWardenCapturingCallSite() throws SQLException {
        checkout(stubWardenWithCallSite);
    }

    @Benchmark
    public void hikariBaseline() throws SQLException {
        checkout(hikari);
    }

    @Benchmark
    public void hikariThroughWarden() throws SQLException {
        checkout(hikariWarden);
    }

    private static void checkout(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.getAutoCommit();
        }
    }

    private static DataSource warden(DataSource delegate, boolean captureStackTrace) {
        WardenConfig config = WardenConfig.builder()
                .captureStackTrace(captureStackTrace)
                .build();
        return new WardenDataSource(delegate, "benchmark", new ConnectionTracker(), config);
    }

    private static HikariDataSource hikari() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:poolwarden-benchmark;DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }
}
