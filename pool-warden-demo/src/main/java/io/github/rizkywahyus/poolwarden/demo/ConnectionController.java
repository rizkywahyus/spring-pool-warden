package io.github.rizkywahyus.poolwarden.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Two endpoints that use the same pool: one correctly, one the way a leak happens in real code.
 */
@RestController
public class ConnectionController {

    private final DataSource dataSource;

    /** Keeps leaked connections reachable, exactly like a cache or a field would in real code. */
    private final List<Connection> leaked = new CopyOnWriteArrayList<>();

    public ConnectionController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Checks a connection out and never closes it. */
    @GetMapping("/leak")
    public Map<String, Object> leak() throws SQLException {
        Connection connection = dataSource.getConnection();
        leaked.add(connection);
        return Map.of(
                "leakedConnections", leaked.size(),
                "hint", "watch the logs, /actuator/poolwarden and /actuator/prometheus");
    }

    /** The same work done properly, for comparison. */
    @GetMapping("/normal")
    public Map<String, Object> normal() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            return Map.of("result", resultSet.getInt(1));
        }
    }

    /** Releases everything the demo leaked, so the app can be reused without a restart. */
    @GetMapping("/release")
    public Map<String, Object> release() {
        int released = 0;
        for (Connection connection : leaked) {
            try {
                connection.close();
                released++;
            } catch (SQLException e) {
                // The sweeper may already have reaped it; nothing left to do here.
            }
        }
        leaked.clear();
        return Map.of("released", released);
    }
}
