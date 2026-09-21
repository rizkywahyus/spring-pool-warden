package io.github.rizkywahyus.poolwarden.autoconfigure;

import io.github.rizkywahyus.poolwarden.core.config.WardenConfig;
import io.github.rizkywahyus.poolwarden.core.proxy.WardenDataSource;
import io.github.rizkywahyus.poolwarden.core.tracking.ConnectionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces every pooled {@link DataSource} bean with a {@link WardenDataSource} wrapping it, so
 * the application gets leak tracking without touching its own configuration.
 *
 * <p>Data sources that only forward to another one -- Spring's {@code DelegatingDataSource}
 * family and {@code AbstractRoutingDataSource} -- are skipped. They are usually beans alongside
 * the pool they point at, so wrapping both would track a single checkout twice: once through
 * the outer data source and once through the pool it delegates to. The pool underneath is the
 * one that actually hands out connections, and it is the one that gets wrapped.
 *
 * <p>Its collaborators arrive as {@link ObjectProvider}s and are resolved inside
 * {@code postProcessAfterInitialization}. A bean post-processor is created before ordinary
 * beans, so injecting them directly would drag them -- and anything they depend on -- into
 * early initialisation, where they cannot be post-processed themselves.
 */
public class PoolWardenBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PoolWardenBeanPostProcessor.class);

    private static final List<String> DELEGATING_DATA_SOURCE_TYPES = List.of(
            "org.springframework.jdbc.datasource.DelegatingDataSource",
            "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource");

    /** Resolved once: spring-jdbc is optional, so the types may simply not be on the classpath. */
    private static final List<Class<?>> DELEGATING_DATA_SOURCE_CLASSES = resolveDelegatingTypes();

    private final ObjectProvider<ConnectionTracker> trackerProvider;
    private final ObjectProvider<WardenConfig> configProvider;

    public PoolWardenBeanPostProcessor(ObjectProvider<ConnectionTracker> trackerProvider,
                                       ObjectProvider<WardenConfig> configProvider) {
        this.trackerProvider = trackerProvider;
        this.configProvider = configProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource dataSource) || bean instanceof WardenDataSource) {
            return bean;
        }
        if (isDelegating(bean)) {
            log.debug("pool-warden is skipping DataSource bean '{}' ({}): it delegates to another "
                    + "DataSource, which is wrapped instead", beanName, bean.getClass().getName());
            return bean;
        }
        log.debug("pool-warden is wrapping DataSource bean '{}' ({})", beanName,
                bean.getClass().getName());
        return new WardenDataSource(dataSource, beanName, trackerProvider.getObject(),
                configProvider.getObject());
    }

    private static boolean isDelegating(Object bean) {
        for (Class<?> type : DELEGATING_DATA_SOURCE_CLASSES) {
            if (type.isInstance(bean)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> resolveDelegatingTypes() {
        ClassLoader classLoader = PoolWardenBeanPostProcessor.class.getClassLoader();
        List<Class<?>> resolved = new ArrayList<>();
        for (String type : DELEGATING_DATA_SOURCE_TYPES) {
            if (ClassUtils.isPresent(type, classLoader)) {
                resolved.add(ClassUtils.resolveClassName(type, classLoader));
            }
        }
        return List.copyOf(resolved);
    }
}
