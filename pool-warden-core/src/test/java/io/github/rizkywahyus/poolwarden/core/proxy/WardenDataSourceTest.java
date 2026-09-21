package io.github.rizkywahyus.poolwarden.core.proxy;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.core.tracking.TrackedEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WardenDataSourceTest {

    private DataSource poolDataSource;
    private Connection poolConnection;
    private ConnectionTracker tracker;
    private WardenDataSource wardenDataSource;

    @BeforeEach
    void setUp() throws SQLException {
        poolDataSource = mock(DataSource.class);
        poolConnection = mock(Connection.class);
        when(poolDataSource.getConnection()).thenReturn(poolConnection);
        when(poolDataSource.getConnection(anyString(), anyString())).thenReturn(poolConnection);

        tracker = new ConnectionTracker();
        wardenDataSource = new WardenDataSource(poolDataSource, tracker, WardenConfig.defaults());
    }

    @Test
    void tracksConnectionOnCheckout() throws SQLException {
        Connection connection = wardenDataSource.getConnection();

        assertThat(connection).isNotSameAs(poolConnection);
        assertThat(tracker.size()).isEqualTo(1);

        TrackedEntry entry = tracker.snapshot().get(0);
        assertThat(entry.connection()).isSameAs(poolConnection);
        assertThat(entry.threadName()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    void untracksConnectionOnClose() throws SQLException {
        try (Connection connection = wardenDataSource.getConnection()) {
            assertThat(tracker.size()).isEqualTo(1);
            assertThat(connection).isNotNull();
        }

        assertThat(tracker.size()).isZero();
        verify(poolConnection).close();
    }

    @Test
    void keepsTrackingWhenCloseIsNeverCalled() throws SQLException {
        wardenDataSource.getConnection();

        assertThat(tracker.size()).isEqualTo(1);
        verify(poolConnection, never()).close();
    }

    @Test
    void doubleCloseReleasesThePoolOnlyOnce() throws SQLException {
        Connection connection = wardenDataSource.getConnection();

        connection.close();
        connection.close();

        assertThat(tracker.size()).isZero();
        // Closing twice is a no-op by JDBC contract, and passing the second call on would hand
        // the pool a release for a slot it may have already lent to someone else.
        verify(poolConnection, times(1)).close();
    }

    @Test
    void untracksEvenWhenDelegateCloseFails() throws SQLException {
        doThrow(new SQLException("pool is broken")).when(poolConnection).close();
        Connection connection = wardenDataSource.getConnection();

        assertThatThrownBy(connection::close)
                .isInstanceOf(SQLException.class)
                .hasMessage("pool is broken");

        assertThat(tracker.size()).isZero();
    }

    @Test
    void propagatesDriverExceptionUnwrapped() throws SQLException {
        when(poolConnection.createStatement()).thenThrow(new SQLException("connection closed", "08003"));
        Connection connection = wardenDataSource.getConnection();

        assertThatThrownBy(connection::createStatement)
                .isInstanceOf(SQLException.class)
                .hasMessage("connection closed");
    }

    @Test
    void delegatesOrdinaryCalls() throws SQLException {
        Statement statement = mock(Statement.class);
        when(poolConnection.createStatement()).thenReturn(statement);
        Connection connection = wardenDataSource.getConnection();

        assertThat(connection.createStatement()).isSameAs(statement);
        verify(poolConnection).createStatement();
    }

    @Test
    void tracksConnectionCheckedOutWithCredentials() throws SQLException {
        wardenDataSource.getConnection("user", "secret");

        assertThat(tracker.size()).isEqualTo(1);
        verify(poolDataSource).getConnection("user", "secret");
    }

    @Test
    void capturesCallSiteOnlyWhenEnabled() throws SQLException {
        WardenConfig noCapture = WardenConfig.builder().captureStackTrace(false).build();
        ConnectionTracker quietTracker = new ConnectionTracker();
        new WardenDataSource(poolDataSource, quietTracker, noCapture).getConnection();

        assertThat(quietTracker.snapshot().get(0).callSite()).isEmpty();

        wardenDataSource.getConnection();
        List<StackTraceElement> callSite = tracker.snapshot().get(0).callSite();
        assertThat(callSite).isNotEmpty();
        // Warden's own frames are noise in a leak report, so they are filtered out. This test
        // class sits inside the warden's core package, so its frames are filtered too; in an
        // application the nearest remaining frame is the code that called getConnection().
        assertThat(callSite).noneMatch(frame ->
                frame.getClassName().startsWith("io.github.rizkywahyus.poolwarden.core."));
    }

    @Test
    void proxyIdentityIsStable() throws SQLException {
        Connection connection = wardenDataSource.getConnection();

        assertThat(connection).isEqualTo(connection);
        assertThat(connection).isNotEqualTo(poolConnection);
        assertThat(connection.hashCode()).isEqualTo(connection.hashCode());
        assertThat(connection.toString()).startsWith("WardenConnection[");
    }

    @Test
    void unwrapReachesTheUnderlyingDriverConnection() throws SQLException {
        when(poolConnection.unwrap(Statement.class)).thenThrow(new SQLException("not a wrapper"));
        Connection connection = wardenDataSource.getConnection();

        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThatThrownBy(() -> connection.unwrap(Statement.class)).isInstanceOf(SQLException.class);
    }

    @Test
    void dataSourceUnwrapPrefersItselfThenDelegates() throws SQLException {
        assertThat(wardenDataSource.unwrap(WardenDataSource.class)).isSameAs(wardenDataSource);
        assertThat(wardenDataSource.isWrapperFor(DataSource.class)).isTrue();

        wardenDataSource.unwrap(Statement.class);
        verify(poolDataSource).unwrap(Statement.class);
    }
}
