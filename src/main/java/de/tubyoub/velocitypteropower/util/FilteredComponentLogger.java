package de.tubyoub.velocitypteropower.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.function.Supplier;

/**
 * A ComponentLogger wrapper that allows runtime dynamic level filtering.
 * This is a fallback solution if the underlying logging framework cannot be
 * configured directly. It requires implementing all ComponentLogger methods.
 */
public class FilteredComponentLogger implements ComponentLogger {

    private final ComponentLogger delegate;
    private volatile Level currentLevel; // Use volatile for thread safety
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public FilteredComponentLogger(ComponentLogger delegate, Level initialLevel) {
        this.delegate = delegate;
        this.currentLevel = initialLevel;
    }

    /**
     * Sets the logging level for this logger.
     *
     * @param newLevel The new logging level.
     */
    public void setLevel(Level newLevel) {
        this.currentLevel = newLevel;
    }


    /**
     * Helper method to check if a given level is enabled based on the current level.
     *
     * @param level The level to check.
     * @return true if the level is enabled, false otherwise.
     */
    private boolean isLevelEnabled(Level level) {
        return level.toInt() >= currentLevel.toInt();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    // --- is...Enabled() Methods ---

    @Override
    public boolean isTraceEnabled() {
        return isLevelEnabled(Level.TRACE);
    }
    @Override
    public boolean isTraceEnabled(Marker marker) {
        return isTraceEnabled() && delegate.isTraceEnabled(marker);
    }

    @Override
    public boolean isDebugEnabled() {
        return isLevelEnabled(Level.DEBUG);
    }
    @Override
    public boolean isDebugEnabled(Marker marker) {
        return isDebugEnabled() && delegate.isDebugEnabled(marker);
    }

    @Override
    public boolean isInfoEnabled() {
        return isLevelEnabled(Level.INFO);
    }
    @Override
    public boolean isInfoEnabled(Marker marker) {
        return isInfoEnabled() && delegate.isInfoEnabled(marker);
    }

    @Override
    public boolean isWarnEnabled() {
        return isLevelEnabled(Level.WARN);
    }
    @Override
    public boolean isWarnEnabled(Marker marker) {
        return isWarnEnabled() && delegate.isWarnEnabled(marker);
    }

    @Override
    public boolean isErrorEnabled() {
        return isLevelEnabled(Level.ERROR);
    }
    @Override
    public boolean isErrorEnabled(Marker marker) {
        return isErrorEnabled() && delegate.isErrorEnabled(marker);
    }

    // --- Component Logging Methods ---

    @Override
    public void trace(@NotNull Component msg) {
        if (isTraceEnabled()) {
            delegate.trace(msg);
        }
    }
    @Override
    public void trace(@NotNull Component format, @Nullable Object arg) {
        if (isTraceEnabled()) {
            delegate.trace(format, arg);
        }
    }
    @Override
    public void trace(@NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isTraceEnabled()) {
            delegate.trace(format, arg1, arg2);
        }
    }
    @Override
    public void trace(@NotNull Component format, @NotNull Object... arguments) {
        if (isTraceEnabled()) {
            delegate.trace(format, arguments);
        }
    }
    @Override
    public void trace(@NotNull Component msg, @Nullable Throwable t) {
        if (isTraceEnabled()) {
            delegate.trace(msg, t);
        }
    }
    @Override
    public void trace(@NotNull Marker marker, @NotNull Component msg) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, msg);
        }
    }
    @Override
    public void trace(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, format, arg);
        }
    }
    @Override
    public void trace(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, format, arg1, arg2);
        }
    }
    @Override
    public void trace(@NotNull Marker marker, @NotNull Component format, @NotNull Object... argArray) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, format, argArray);
        }
    }
    @Override
    public void trace(@NotNull Marker marker, @NotNull Component msg, @Nullable Throwable t) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, msg, t);
        }
    }

    @Override
    public void debug(@NotNull Component msg) {
        if (isDebugEnabled()) {
            delegate.debug(msg);
        }
    }
    @Override
    public void debug(@NotNull Component format, @Nullable Object arg) {
        if (isDebugEnabled()) {
            delegate.debug(format, arg);
        }
    }
    @Override
    public void debug(@NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isDebugEnabled()) {
            delegate.debug(format, arg1, arg2);
        }
    }
    @Override
    public void debug(@NotNull Component format, @NotNull Object... arguments) {
        if (isDebugEnabled()) {
            delegate.debug(format, arguments);
        }
    }
    @Override
    public void debug(@NotNull Component msg, @Nullable Throwable t) {
        if (isDebugEnabled()) {
            delegate.debug(msg, t);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, String msg) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, msg);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, String format, Object arg) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, format, arg);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, String format, Object arg1, Object arg2) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, format, arg1, arg2);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, String format, Object... arguments) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, format, arguments);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, String msg, Throwable t) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, msg, t);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, @NotNull Component msg) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, msg);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, format, arg);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, format, arg1, arg2);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, @NotNull Component format, @NotNull Object... arguments) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, format, arguments);
        }
    }
    @Override
    public void debug(@NotNull Marker marker, @NotNull Component msg, @NotNull Throwable t) {
        if (isDebugEnabled(marker)) {
            delegate.debug(marker, msg, t);
        }
    }

    @Override
    public void info(@NotNull Component msg) {
        if (isInfoEnabled()) {
            delegate.info(msg);
        }
    }
    @Override
    public void info(@NotNull Component format, @Nullable Object arg) {
        if (isInfoEnabled()) {
            delegate.info(format, arg);
        }
    }
    @Override
    public void info(@NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isInfoEnabled()) {
            delegate.info(format, arg1, arg2);
        }
    }
    @Override
    public void info(@NotNull Component format, @NotNull Object... arguments) {
        if (isInfoEnabled()) {
            delegate.info(format, arguments);
        }
    }
    @Override
    public void info(@NotNull Component msg, @Nullable Throwable t) {
        if (isInfoEnabled()) {
            delegate.info(msg, t);
        }
    }
    @Override
    public void info(@NotNull Marker marker, @NotNull Component msg) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, msg);
        }
    }
    @Override
    public void info(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, format, arg);
        }
    }
    @Override
    public void info(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, format, arg1, arg2);
        }
    }
    @Override
    public void info(@NotNull Marker marker, @NotNull Component format, @NotNull Object... arguments) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, format, arguments);
        }
    }
    @Override
    public void info(@NotNull Marker marker, @NotNull Component msg, @NotNull Throwable t) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, msg, t);
        }
    }

    @Override
    public void warn(@NotNull Component msg) {
        if (isWarnEnabled()) {
            delegate.warn(msg);
        }
    }
    @Override
    public void warn(@NotNull Component format, @Nullable Object arg) {
        if (isWarnEnabled()) {
            delegate.warn(format, arg);
        }
    }
    @Override
    public void warn(@NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isWarnEnabled()) {
            delegate.warn(format, arg1, arg2);
        }
    }
    @Override
    public void warn(@NotNull Component format, @NotNull Object... arguments) {
        if (isWarnEnabled()) {
            delegate.warn(format, arguments);
        }
    }
    @Override
    public void warn(@NotNull Component msg, @NotNull Throwable t) {
        if (isWarnEnabled()) {
            delegate.warn(msg, t);
        }
    }
    @Override
    public void warn(@NotNull Marker marker, @NotNull Component msg) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, msg);
        }
    }
    @Override
    public void warn(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, format, arg);
        }
    }
    @Override
    public void warn(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, format, arg1, arg2);
        }
    }
    @Override
    public void warn(@NotNull Marker marker, @NotNull Component format, @NotNull Object... arguments) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, format, arguments);
        }
    }
    @Override
    public void warn(@NotNull Marker marker, @NotNull Component msg, @NotNull Throwable t) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, msg, t);
        }
    }

    @Override
    public void error(@NotNull Component msg) {
        if (isErrorEnabled()) {
            delegate.error(msg);
        }
    }
    @Override
    public void error(@NotNull Component format, @Nullable Object arg) {
        if (isErrorEnabled()) {
            delegate.error(format, arg);
        }
    }
    @Override
    public void error(@NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isErrorEnabled()) {
            delegate.error(format, arg1, arg2);
        }
    }
    @Override
    public void error(@NotNull Component format, @NotNull Object... arguments) {
        if (isErrorEnabled()) {
            delegate.error(format, arguments);
        }
    }
    @Override
    public void error(@NotNull Component msg, @NotNull Throwable t) {
        if (isErrorEnabled()) {
            delegate.error(msg, t);
        }
    }
    @Override
    public void error(@NotNull Marker marker, @NotNull Component msg) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, msg);
        }
    }
    @Override
    public void error(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, format, arg);
        }
    }
    @Override
    public void error(@NotNull Marker marker, @NotNull Component format, @Nullable Object arg1, @Nullable Object arg2) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, format, arg1, arg2);
        }
    }
    @Override
    public void error(@NotNull Marker marker, @NotNull Component format, @NotNull Object... arguments) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, format, arguments);
        }
    }
    @Override
    public void error(@NotNull Marker marker, @NotNull Component msg, @NotNull Throwable t) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, msg, t);
        }
    }

    // --- Standard SLF4J Logger Methods ---

    @Override
    public void trace(String msg) {
        if (isTraceEnabled()) {
            delegate.trace(msg);
        }
    }
    @Override
    public void trace(String format, Object arg) {
        if (isTraceEnabled()) {
            delegate.trace(format, arg);
        }
    }
    @Override
    public void trace(String format, Object arg1, Object arg2) {
        if (isTraceEnabled()) {
            delegate.trace(format, arg1, arg2);
        }
    }
    @Override
    public void trace(String format, Object... arguments) {
        if (isTraceEnabled()) {
            delegate.trace(format, arguments);
        }
    }
    @Override
    public void trace(String msg, Throwable t) {
        if (isTraceEnabled()) {
            delegate.trace(msg, t);
        }
    }
    @Override
    public void trace(Marker marker, String msg) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, msg);
        }
    }
    @Override
    public void trace(Marker marker, String format, Object arg) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, format, arg);
        }
    }
    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, format, arg1, arg2);
        }
    }
    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, format, argArray);
        }
    }
    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        if (isTraceEnabled(marker)) {
            delegate.trace(marker, msg, t);
        }
    }

     @Override
    public void debug(String msg) {
        if (isDebugEnabled()) {
            delegate.debug(msg);
        }
    }

    @Override
    public void debug(String format, Object arg) {
        if (isDebugEnabled()) {
            delegate.debug(format, arg);
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        if (isDebugEnabled()) {
            delegate.debug(format, arg1, arg2);
        }
    }

    @Override
    public void debug(String format, Object... arguments) {
        if (isDebugEnabled()) {
            delegate.debug(format, arguments);
        }
    }

    @Override
    public void debug(String msg, Throwable t) {
        if (isDebugEnabled()) {
            delegate.debug(msg, t);
        }
    }

    @Override
    public void info(String msg) {
        if (isInfoEnabled()) {
            delegate.info(msg);
        }
    }
    @Override
    public void info(String format, Object arg) {
        if (isInfoEnabled()) {
            delegate.info(format, arg);
        }
    }
    @Override
    public void info(String format, Object arg1, Object arg2) {
        if (isInfoEnabled()) {
            delegate.info(format, arg1, arg2);
        }
    }
    @Override
    public void info(String format, Object... arguments) {
        if (isInfoEnabled()) {
            delegate.info(format, arguments);
        }
    }
    @Override
    public void info(String msg, Throwable t) {
        if (isInfoEnabled()) {
            delegate.info(msg, t);
        }
    }
    @Override
    public void info(Marker marker, String msg) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, msg);
        }
    }
    @Override
    public void info(Marker marker, String format, Object arg) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, format, arg);
        }
    }
    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, format, arg1, arg2);
        }
    }
    @Override
    public void info(Marker marker, String format, Object... arguments) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, format, arguments);
        }
    }
    @Override
    public void info(Marker marker, String msg, Throwable t) {
        if (isInfoEnabled(marker)) {
            delegate.info(marker, msg, t);
        }
    }

    @Override
    public void warn(String msg) {
        if (isWarnEnabled()) {
            delegate.warn(msg);
        }
    }
    @Override
    public void warn(String format, Object arg) {
        if (isWarnEnabled()) {
            delegate.warn(format, arg);
        }
    }
    @Override
    public void warn(String format, Object... arguments) {
        if (isWarnEnabled()) {
            delegate.warn(format, arguments);
        }
    }
    @Override
    public void warn(String format, Object arg1, Object arg2) {
        if (isWarnEnabled()) {
            delegate.warn(format, arg1, arg2);
        }
    }
    @Override
    public void warn(String msg, Throwable t) {
        if (isWarnEnabled()) {
            delegate.warn(msg, t);
        }
    }
    @Override
    public void warn(Marker marker, String msg) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, msg);
        }
    }
    @Override
    public void warn(Marker marker, String format, Object arg) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, format, arg);
        }
    }
    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, format, arg1, arg2);
        }
    }
    @Override
    public void warn(Marker marker, String format, Object... arguments) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, format, arguments);
        }
    }
    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        if (isWarnEnabled(marker)) {
            delegate.warn(marker, msg, t);
        }
    }

    @Override
    public void error(String msg) {
        if (isErrorEnabled()) {
            delegate.error(msg);
        }
    }
    @Override
    public void error(String format, Object arg) {
        if (isErrorEnabled()) {
            delegate.error(format, arg);
        }
    }
    @Override
    public void error(String format, Object arg1, Object arg2) {
        if (isErrorEnabled()) {
            delegate.error(format, arg1, arg2);
        }
    }
    @Override
    public void error(String format, Object... arguments) {
        if (isErrorEnabled()) {
            delegate.error(format, arguments);
        }
    }
    @Override
    public void error(String msg, Throwable t) {
        if (isErrorEnabled()) {
            delegate.error(msg, t);
        }
    }
    @Override
    public void error(Marker marker, String msg) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, msg);
        }
    }
    @Override
    public void error(Marker marker, String format, Object arg) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, format, arg);
        }
    }
    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, format, arg1, arg2);
        }
    }
    @Override
    public void error(Marker marker, String format, Object... arguments) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, format, arguments);
        }
    }
    @Override
    public void error(Marker marker, String msg, Throwable t) {
        if (isErrorEnabled(marker)) {
            delegate.error(marker, msg, t);
        }
    }

    // --- LoggingEventBuilder Methods ---

    @Override
    public LoggingEventBuilder atTrace() {
        return isTraceEnabled() ? delegate.atTrace() : NoOpLoggingEventBuilder.INSTANCE;
    }

    @Override
    public LoggingEventBuilder atDebug() {
        return isDebugEnabled() ? delegate.atDebug() : NoOpLoggingEventBuilder.INSTANCE;
    }

    @Override
    public LoggingEventBuilder atInfo() {
        return isInfoEnabled() ? delegate.atInfo() : NoOpLoggingEventBuilder.INSTANCE;
    }

    @Override
    public LoggingEventBuilder atWarn() {
        return isWarnEnabled() ? delegate.atWarn() : NoOpLoggingEventBuilder.INSTANCE;
    }

    @Override
    public LoggingEventBuilder atError() {
        return isErrorEnabled() ? delegate.atError() : NoOpLoggingEventBuilder.INSTANCE;
    }

    @Override
    public LoggingEventBuilder atLevel(Level level) {
        return isLevelEnabled(level) ? delegate.atLevel(level) : NoOpLoggingEventBuilder.INSTANCE;
    }

    /**
     * A no-operation implementation of LoggingEventBuilder that's used when
     * the current logging level doesn't allow the log message to be emitted.
     */
    private static class NoOpLoggingEventBuilder implements LoggingEventBuilder {
        static final NoOpLoggingEventBuilder INSTANCE = new NoOpLoggingEventBuilder();

        @Override
        public LoggingEventBuilder setCause(Throwable cause) {
            return this;
        }

        @Override
        public LoggingEventBuilder addMarker(Marker marker) {
            return this;
        }

        @Override
        public LoggingEventBuilder addArgument(Object p) {
            return this;
        }
        @Override
        public LoggingEventBuilder addArgument(Supplier<?> objectSupplier) {
            return this;
        }

        @Override
        public LoggingEventBuilder addKeyValue(String key, Object value) {
            return this;
        }
        @Override
        public LoggingEventBuilder addKeyValue(String key, Supplier<Object> valueSupplier) {
            return this;
        }

        @Override
        public LoggingEventBuilder setMessage(String message) {
            return this;
        }
        @Override
        public LoggingEventBuilder setMessage(Supplier<String> messageSupplier) {
            return this;
        }

        @Override
        public void log() {
            // No-op
        }
        @Override
        public void log(String message) {
            // No-op
        }
        @Override
        public void log(String message, Object arg) {
            // No-op
        }
        @Override
        public void log(String message, Object arg1, Object arg2) {
            // No-op
        }
        @Override
        public void log(String message, Object... args) {
            // No-op
        }
        @Override
        public void log(Supplier<String> messageSupplier) {
            // No-op
        }
    }
}