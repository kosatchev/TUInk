package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.runtime.TuiEventLoop;
import java.time.Duration;
import org.jline.utils.NonBlockingPumpReader;
import org.junit.jupiter.api.Test;

class TerminalDeadlineTest {
    @Test
    void expiredDeadlineReturnsWithoutInputAndKeepsInputAndSignalsResponsive() throws Exception {
        try (NonBlockingPumpReader reader = new NonBlockingPumpReader()) {
            KeyDecoder decoder = new KeyDecoder(reader);
            TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> {
                KeyDecoder.ReadResult result = decoder.read(timeout);
                return switch (result.status()) {
                    case EVENT -> TuiEventLoop.Read.event(result.event());
                    case IDLE -> TuiEventLoop.Read.idle();
                    case END -> TuiEventLoop.Read.end();
                };
            });

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                TuiEventLoop.Poll<TuiInputEvent> idle = loop.poll(now -> 0L);
                assertNull(idle.event());
                assertFalse(idle.endOfInput());

                reader.getWriter().write("x");
                assertEquals(new TextInput("x"), loop.poll(now -> 0L).event());

                ResizeEvent resize = new ResizeEvent(30, 100);
                loop.requestResize(resize);
                TuiEventLoop.Poll<TuiInputEvent> resized = loop.poll(now -> 0L);
                assertEquals(resize, resized.event());
                assertTrue(resized.resized());

                loop.requestStop();
                assertFalse(loop.running());
            });
        }
    }
}
