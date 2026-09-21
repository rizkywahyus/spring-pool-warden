package io.github.rizkywahyus.poolwarden.core.tracking;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Captures the application call-site that checked out a connection.
 *
 * <p>Uses {@link StackWalker} instead of {@code new Throwable().getStackTrace()} because it
 * can stop walking after a fixed depth instead of materialising the whole stack. Capturing is
 * still not free, which is why it is opt-out through
 * {@link io.github.rizkywahyus.poolwarden.core.config.WardenConfig#captureStackTrace()}.
 */
public final class CallSiteCapture {

    private static final int MAX_FRAMES = 8;
    /**
     * Only the warden's own internals are hidden -- not the whole {@code poolwarden} namespace,
     * so an application that happens to live under it still sees its own frames.
     */
    private static final List<String> WARDEN_PACKAGE_PREFIXES = List.of(
            "io.github.rizkywahyus.poolwarden.core.",
            "io.github.rizkywahyus.poolwarden.autoconfigure.");

    /**
     * Frames that only ever say "a framework called this", never where the application asked for
     * a connection. Spring's transaction and AOP machinery and the generated proxies sit between
     * the business method and the data source, so without this the report starts with them.
     */
    private static final List<String> INFRASTRUCTURE_PACKAGE_PREFIXES = List.of(
            "com.zaxxer.hikari.",
            "org.apache.tomcat.jdbc.",
            "org.apache.commons.dbcp2.",
            "org.springframework.jdbc.datasource.",
            "org.springframework.transaction.",
            "org.springframework.aop.",
            "org.springframework.orm.jpa.",
            "org.springframework.data.jpa.repository.support.",
            "org.hibernate.",
            "jdk.proxy",
            "com.sun.proxy.",
            "jdk.internal.reflect.",
            "java.lang.reflect.");

    private static final StackWalker WALKER = StackWalker.getInstance();

    private CallSiteCapture() {
    }

    /**
     * @return the first application frames of the current stack, or an empty list if the whole
     *         stack is infrastructure code. Frames are converted to {@link StackTraceElement}
     *         during the walk because a {@code StackFrame} is only valid while the walk runs.
     */
    public static List<StackTraceElement> capture() {
        return WALKER.walk(frames -> frames
                .filter(CallSiteCapture::isApplicationFrame)
                .limit(MAX_FRAMES)
                .map(StackWalker.StackFrame::toStackTraceElement)
                .collect(Collectors.toList()));
    }

    private static boolean isApplicationFrame(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        return !startsWithAny(className, WARDEN_PACKAGE_PREFIXES)
                && !startsWithAny(className, INFRASTRUCTURE_PACKAGE_PREFIXES);
    }

    private static boolean startsWithAny(String className, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Renders captured frames as an indented, log-friendly block. */
    public static String format(List<StackTraceElement> frames) {
        if (frames == null || frames.isEmpty()) {
            return "(call-site capture disabled)";
        }
        return frames.stream()
                .map(frame -> "\n\tat " + frame)
                .collect(Collectors.joining());
    }
}
