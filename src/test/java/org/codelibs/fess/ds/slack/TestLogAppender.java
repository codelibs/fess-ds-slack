/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.slack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * A minimal, in-memory Log4j2 appender for asserting on log output in tests where the log level
 * actually used is the only observable effect of the behaviour under test -- for example, "a
 * warning is emitted when a value was previously discarded" or "this moved from warn to debug".
 *
 * <p>
 * Attach with {@link #attachTo(Class)}, exercise the code under test, inspect with
 * {@link #hasEventAt(Level)} or {@link #messagesAt(Level)}, then always {@link #detach()} --
 * ideally in a {@code finally} block -- so a failed assertion does not leave this appender
 * attached to a logger shared by every other test in the same JVM.
 * </p>
 */
public final class TestLogAppender extends AbstractAppender {

    /**
     * Source of this appender's unique name suffix. {@code System.identityHashCode} is not
     * reliably unique -- two distinct objects can collide on it -- and Log4j2 keys an appender by
     * name on a {@code LoggerConfig}, so a collision would silently replace one attached
     * appender with another instead of failing loudly. A monotonically increasing counter cannot
     * collide within this JVM.
     */
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    private Logger attachedLogger;

    private Level previousLevel;

    private TestLogAppender() {
        // The 5-arg constructor (with a Property[]) is used deliberately: the 4-arg overload
        // this used to call is deprecated in this module's Log4j2 version.
        super("test-capture-" + NEXT_ID.getAndIncrement(), null, null, false, Property.EMPTY_ARRAY);
    }

    /**
     * Creates a new capturing appender and attaches it to the given class's logger, raising that
     * logger's level to {@link Level#TRACE} first so debug-level events are captured regardless
     * of whatever level the class would otherwise be configured at.
     *
     * @param loggedClass the class whose {@code LogManager.getLogger(...)} logger to attach to
     * @return the attached appender; call {@link #detach()} when done with it
     */
    public static TestLogAppender attachTo(final Class<?> loggedClass) {
        final TestLogAppender appender = new TestLogAppender();
        appender.start();
        final Logger logger = (Logger) LogManager.getLogger(loggedClass);
        appender.attachedLogger = logger;
        appender.previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        return appender;
    }

    @Override
    public void append(final LogEvent event) {
        events.add(event.toImmutable());
    }

    /**
     * Detaches this appender from the logger it was attached to and restores that logger's
     * original level. Safe to call more than once.
     */
    public void detach() {
        if (attachedLogger != null) {
            attachedLogger.removeAppender(this);
            attachedLogger.setLevel(previousLevel);
            attachedLogger = null;
        }
        stop();
    }

    /**
     * Returns whether any event was captured at the given level.
     *
     * @param level the level to look for
     * @return true if at least one captured event is at that level
     */
    public boolean hasEventAt(final Level level) {
        return events.stream().anyMatch(e -> e.getLevel().equals(level));
    }

    /**
     * Returns the formatted messages of every captured event at the given level, in capture
     * order.
     *
     * @param level the level to filter by
     * @return the formatted messages at that level
     */
    public List<String> messagesAt(final Level level) {
        return events.stream()
                .filter(e -> e.getLevel().equals(level))
                .map(e -> e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
    }

}
