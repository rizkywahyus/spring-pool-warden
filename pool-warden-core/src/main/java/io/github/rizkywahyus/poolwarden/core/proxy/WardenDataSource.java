package io.github.rizkywahyus.poolwarden.core.proxy;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * A {@link DataSource} that hands out tracked connections.
 *
 * <p>Wraps any pool (HikariCP, Tomcat JDBC, DBCP2, ...) because it only relies on the
 * {@code DataSource} interface, never on pool internals. Every other method is delegated
 * untouched, so pool-specific behaviour and configuration keep working.
 *
 * <p>Implements {@link AutoCloseable} so shutting the warden down also shuts the wrapped pool
 * down. Without it, a container that replaces its pool bean with this wrapper would never run
 * the pool's own close method and would leak the pool on shutdown.
 */
public final class WardenDataSource implements DataSource, AutoCloseable {

    /** Used when the caller has no better name, for example a hand-built data source. */
    public static final String DEFAULT_NAME = "dataSource";

    private final DataSource delegate;
    private final String name;
    private final ConnectionTracker tracker;
    private final WardenConfig config;

    public WardenDataSource(DataSource delegate, ConnectionTracker tracker, WardenConfig config) {
        this(delegate, DEFAULT_NAME, tracker, config);
    }

    /**
     * @param name how this data source is reported in logs, metrics and the actuator endpoint.
     *             In a Spring application this is the bean name, which is what tells two pools
     *             apart when an application has several.
     */
    public WardenDataSource(DataSource delegate, String name, ConnectionTracker tracker, WardenConfig config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** The pool this warden wraps. */
    public DataSource delegate() {
        return delegate;
    }

    public String name() {
        return name;
    }

    public ConnectionTracker tracker() {
        return tracker;
    }

    public WardenConfig config() {
        return config;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return track(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return track(delegate.getConnection(username, password));
    }

    private Connection track(Connection connection) {
        return WardenConnectionHandler.wrap(name, connection, tracker, config.captureStackTrace());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /** Closes the wrapped pool if it is closeable; pools that are not simply stay untouched. */
    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Override
    public String toString() {
        return "WardenDataSource[" + name + " -> " + delegate + "]";
    }
}
