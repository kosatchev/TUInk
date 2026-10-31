package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_ERR;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ListResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(SYSTEM_ERR)
@ResourceLock("org.jline.logger")
class TerminalLogScopeTest {
    @Test
    void messagesUseJulLocalizationAndParametersBeforeEscapingTheirTerminalControls() throws Exception {
        try (Fixture fixture = new Fixture(); TerminalLogScope scope = TerminalLogScope.open()) {
            LogRecord record = new LogRecord(Level.WARNING, "warning-key");
            record.setResourceBundle(new ListResourceBundle() {
                @Override protected Object[][] getContents() {
                    return new Object[][] {{"warning-key", "TERM {0}"}};
                }
            });
            record.setParameters(new Object[] {"name-\u001B]0;title\u0007\r\n\t\u0085\u007F\u202E-😀"});
            fixture.logger.log(record);

            String output = fixture.output();
            assertTrue(output.startsWith("JLine WARNING: TERM name-"), output);
            assertTrue(output.contains("title"));
            assertTrue(output.contains("😀"));
            assertEquals(1, output.lines().count(), output);
            assertPlain(output);
        }
    }

    @Test
    void failuresEscapeCausesSuppressedMessagesAndStackFrameFilenames() throws Exception {
        try (Fixture fixture = new Fixture(); TerminalLogScope scope = TerminalLogScope.open()) {
            IOException failure = new IOException("OUTER-\u001B]0;title\u0007",
                new IOException("CAUSE-\u0085\u202E\uD800"));
            failure.addSuppressed(new IOException("SUPPRESSED-\r\n\t\u2066"));
            failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("Audit", "read", "source-\u001B.txt", 12)
            });
            fixture.logger.log(Level.WARNING, "Unable to retrieve infocmp for type {0}", "unknown");
            fixture.logger.log(Level.WARNING, "Failed terminal initialization", failure);

            String output = fixture.output();
            assertTrue(output.contains("OUTER-"), output);
            assertTrue(output.contains("CAUSE-"), output);
            assertTrue(output.contains("SUPPRESSED-"), output);
            assertTrue(output.contains("source-"), output);
            assertTrue(output.contains("\\uD800"), output);
            assertPlain(output);
        }
    }

    @Test
    void closeRestoresHandlersAndParentRoutingWithoutChangingTheirLevelFilterOrLifetime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TrackingHandler previous = new TrackingHandler();
            fixture.logger.addHandler(previous);
            fixture.logger.setUseParentHandlers(true);
            Filter filter = record -> !record.getMessage().equals("ignored");
            fixture.logger.setFilter(filter);
            Handler[] before = fixture.logger.getHandlers();

            TerminalLogScope scope = TerminalLogScope.open();
            try {
                assertEquals(Level.FINE, fixture.logger.getLevel());
                assertSame(filter, fixture.logger.getFilter());
                assertFalse(fixture.logger.getUseParentHandlers());
                assertEquals(1, fixture.logger.getHandlers().length);
                assertEquals(Level.ALL, fixture.logger.getHandlers()[0].getLevel());
                fixture.logger.fine("visible");
                fixture.logger.warning("ignored");
                assertTrue(fixture.output().contains("visible"));
                assertFalse(fixture.output().contains("ignored"));
                assertEquals(0, previous.published);
            } finally {
                scope.close();
                scope.close();
            }

            assertArrayEquals(before, fixture.logger.getHandlers());
            assertTrue(fixture.logger.getUseParentHandlers());
            assertEquals(Level.FINE, fixture.logger.getLevel());
            assertSame(filter, fixture.logger.getFilter());
            assertFalse(previous.closed);
            fixture.logger.fine("after scope");
            assertEquals(1, previous.published);
        }
    }

    @Test
    void nestedScopesCanCloseOutOfOrderAndOnlyTheLastOwnerRestoresRouting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Handler[] before = fixture.logger.getHandlers();
            TerminalLogScope outer = TerminalLogScope.open();
            TerminalLogScope inner = TerminalLogScope.open();
            Handler installed = fixture.logger.getHandlers()[0];
            try {
                outer.close();
                outer.close();
                assertSame(installed, fixture.logger.getHandlers()[0]);
                fixture.logger.warning("still protected-\u001B]0;title\u0007");
                assertPlain(fixture.output());
            } finally {
                inner.close();
                outer.close();
            }
            assertArrayEquals(before, fixture.logger.getHandlers());
        }
    }

    @Test
    void concurrentOwnersRetainOneSafeHandlerUntilEveryOwnerReleasesIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Handler[] before = fixture.logger.getHandlers();
            TerminalLogScope outer = TerminalLogScope.open();
            Handler installed = fixture.logger.getHandlers()[0];
            var executor = Executors.newFixedThreadPool(3);
            CountDownLatch acquired = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            try {
                var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(index -> executor.submit(() -> {
                    try (TerminalLogScope scope = TerminalLogScope.open()) {
                        acquired.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    return null;
                })).toList();
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                outer.close();
                assertSame(installed, fixture.logger.getHandlers()[0]);
                release.countDown();
                for (var task : tasks) task.get(5, TimeUnit.SECONDS);
                assertArrayEquals(before, fixture.logger.getHandlers());
            } finally {
                release.countDown();
                outer.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertPlain(String output) {
        for (int index = 0; index < output.length(); index++) {
            char character = output.charAt(index);
            assertFalse(character <= 0x1F && character != '\n' && character != '\r'
                || character >= 0x7F && character <= 0x9F
                || character == '\u061C' || character >= '\u200E' && character <= '\u200F'
                || character >= '\u202A' && character <= '\u202E'
                || character >= '\u2066' && character <= '\u2069', output);
        }
    }

    private static final class TrackingHandler extends Handler {
        private int published;
        private boolean closed;
        @Override public void publish(LogRecord record) { published++; }
        @Override public void flush() {}
        @Override public void close() { closed = true; }
    }

    private static final class Fixture implements AutoCloseable {
        private final Logger logger = Logger.getLogger("org.jline");
        private final Handler[] previousHandlers = logger.getHandlers();
        private final boolean previousParentHandlers = logger.getUseParentHandlers();
        private final Level previousLevel = logger.getLevel();
        private final Filter previousFilter = logger.getFilter();
        private final PrintStream previousError = System.err;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final PrintStream error = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        private Fixture() {
            logger.setLevel(Level.FINE);
            logger.setFilter(null);
            System.setErr(error);
        }

        private String output() { return bytes.toString(StandardCharsets.UTF_8); }

        @Override public void close() {
            for (Handler handler : logger.getHandlers()) logger.removeHandler(handler);
            for (Handler handler : previousHandlers) logger.addHandler(handler);
            logger.setUseParentHandlers(previousParentHandlers);
            logger.setLevel(previousLevel);
            logger.setFilter(previousFilter);
            System.setErr(previousError);
            error.close();
        }
    }
}
