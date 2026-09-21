package io.github.rizkywahyus.poolwarden.core.proxy;

import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import io.github.rizkywahyus.poolwarden.core.tracking.TrackedEntry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Intercepts {@link Connection#close()} so a checkout can be untracked, and passes every other
 * call straight through to the pool's own connection.
 *
 * <p>The proxy adds one reflective hop per {@code Connection} method call. It deliberately does
 * not wrap {@code Statement} or {@code ResultSet}: the warden only cares about the connection
 * lifecycle, and wrapping deeper would multiply that overhead for no benefit.
 */
public final class WardenConnectionHandler implements InvocationHandler {

    private static final Class<?>[] PROXY_INTERFACES = {Connection.class};

    private final Connection delegate;
    private final ConnectionTracker tracker;
    private final TrackedEntry entry;

    private WardenConnectionHandler(Connection delegate, ConnectionTracker tracker, TrackedEntry entry) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.entry = entry;
    }

    /**
     * Registers the checkout and returns a proxy that untracks it on close.
     *
     * @param dataSourceName    the data source this connection came from
     * @param delegate          the connection handed out by the underlying pool
     * @param tracker           registry of open checkouts
     * @param captureStackTrace whether to record the application call-site
     */
    public static Connection wrap(String dataSourceName, Connection delegate, ConnectionTracker tracker,
                                  boolean captureStackTrace) {
        TrackedEntry entry = tracker.track(dataSourceName, delegate, captureStackTrace);
        WardenConnectionHandler handler = new WardenConnectionHandler(delegate, tracker, entry);
        return (Connection) Proxy.newProxyInstance(classLoaderFor(delegate), PROXY_INTERFACES, handler);
    }

    private static ClassLoader classLoaderFor(Connection delegate) {
        ClassLoader loader = delegate.getClass().getClassLoader();
        return loader != null ? loader : WardenConnectionHandler.class.getClassLoader();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int argCount = method.getParameterCount();

        if (argCount == 0) {
            switch (name) {
                case "close" -> {
                    return close();
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "WardenConnection[" + delegate + "]";
                }
                default -> {
                    // fall through to the delegate
                }
            }
        } else if (argCount == 1) {
            switch (name) {
                case "equals" -> {
                    return proxy == args[0];
                }
                case "unwrap" -> {
                    return unwrap(proxy, (Class<?>) args[0]);
                }
                case "isWrapperFor" -> {
                    Class<?> target = (Class<?>) args[0];
                    return target.isInstance(proxy) || delegate.isWrapperFor(target);
                }
                default -> {
                    // fall through to the delegate
                }
            }
        }

        return invokeOnDelegate(method, args);
    }

    /**
     * Untracks exactly once, then closes the pooled connection.
     *
     * <p>Untracking is guarded by {@link TrackedEntry#tryClose()} so a close that races the
     * sweeper cannot double-release the entry, and the entry is removed before delegating so a
     * connection that fails to close is not reported as a leak forever.
     *
     * <p>Losing that race means one of two things, and in both the pool has already been told:
     * the application closed this connection before (closing twice is a no-op by contract), or
     * the sweeper reaped it and returned the slot itself. Delegating anyway would hand the pool
     * a second release for a slot it may have already given to another caller, so this call
     * stops here.
     */
    private Object close() throws SQLException {
        if (entry.tryClose()) {
            tracker.untrack(entry.id());
            delegate.close();
        }
        return null;
    }

    private Object unwrap(Object proxy, Class<?> target) throws Exception {
        if (target.isInstance(proxy)) {
            return proxy;
        }
        return delegate.unwrap(target);
    }

    private Object invokeOnDelegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            // Rethrow what the driver actually threw, not the reflection wrapper.
            throw e.getCause();
        }
    }
}
