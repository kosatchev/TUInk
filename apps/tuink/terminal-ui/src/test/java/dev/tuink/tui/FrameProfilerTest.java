package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameProfilerTest {
    @TempDir Path directory;

    @Test void separatesCompositionDeliveredInputAndWriterOutputInTheSampledFrame() {
        var clock = new AtomicLong();
        var output = new StringWriter();
        var host = new TerminalEditorApp();
        var profiler = host.new FrameProfiler(clock::get);
        profiler.setOutput(new PrintWriter(output), false);
        warmSamples(profiler);

        clock.set(1_000_000L);
        long first = profiler.inputStarted();
        clock.set(3_000_000L);
        profiler.inputHandled(first);
        clock.set(4_000_000L);
        long second = profiler.inputStarted();
        clock.set(7_000_000L);
        profiler.inputHandled(second);
        clock.set(11_000_000L);
        profiler.beginFrame("preview");
        clock.set(15_000_000L);
        profiler.compositionFinished(false);
        profiler.presented(new ScreenPresenter.PresentResult(true, false, 75, 2_000_000L, 3_000_000L));
        clock.set(25_000_000L);
        profiler.endFrame(true);

        assertTrue(output.toString().startsWith("frame=30 build=0.00ms raster=0.00ms diff=2.00ms"
            + " search=0.00ms total=14.00ms compose=4.00ms input=5.00ms output=3.00ms"
            + " response=24.00ms chars=75 events=2 heap="), output.toString());
        assertTrue(output.toString().contains(" surface=preview ready=0"), output.toString());
    }

    @Test void aSkippedFrameDoesNotAttachItsInputOrPresentationToALaterFrame() {
        var clock = new AtomicLong();
        var output = new StringWriter();
        var host = new TerminalEditorApp();
        var profiler = host.new FrameProfiler(clock::get);
        profiler.setOutput(new PrintWriter(output), false);
        warmSamples(profiler);

        clock.set(1_000_000L);
        long input = profiler.inputStarted();
        clock.set(2_000_000L);
        profiler.inputHandled(input);
        profiler.beginFrame("preview");
        profiler.compositionFinished(false);
        profiler.presented(new ScreenPresenter.PresentResult(true, false, 90, 2_000_000L, 4_000_000L));
        profiler.endFrame(false);

        clock.set(100_000_000L);
        profiler.beginFrame("source");
        profiler.compositionFinished(true);
        clock.set(101_000_000L);
        profiler.endFrame(true);

        assertTrue(output.toString().startsWith("frame=30 build=0.00ms raster=0.00ms diff=0.00ms"
            + " search=0.00ms total=1.00ms compose=0.00ms input=0.00ms output=0.00ms"
            + " response=0.00ms chars=0 events=0 heap="), output.toString());
        assertTrue(output.toString().contains(" surface=source ready=1"), output.toString());
    }

    @Test void sourceAndReadyPreviewReportTheSameAdditionalPhaseFields() throws Exception {
        try (var window = new EditorWindowFixture(directory, false)) {
            var output = new StringWriter();
            window.host.frameProfiler.setOutput(new PrintWriter(output), false);
            window.host.profileEnabled = true;
            for (int index = 0; index < 30; index++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertTrue(output.toString().contains("surface=source ready=1"), output.toString());

            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.await(() -> window.preview().ready());
            for (int index = 0; index < 30; index++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertTrue(output.toString().contains("surface=preview ready=1"), output.toString());
            assertTrue(output.toString().lines().anyMatch(line -> line.contains("surface=source ready=1 metrics_builds=")), output.toString());
            assertTrue(output.toString().lines().anyMatch(line -> line.contains("surface=preview ready=1 metrics_builds=")), output.toString());
            for (String line : output.toString().lines().toList()) {
                assertTrue(line.matches("frame=\\d+ build=[\\d.]+ms raster=[\\d.]+ms diff=[\\d.]+ms"
                    + " search=[\\d.]+ms total=[\\d.]+ms compose=[\\d.]+ms input=[\\d.]+ms"
                    + " output=[\\d.]+ms response=[\\d.]+ms chars=\\d+ events=\\d+ heap=\\d+"
                    + " surface=(source|preview) ready=[01]"
                    + "(?: metrics_builds=\\d+ cache_misses=\\d+ rows_reused=\\d+ retained_cache_bytes=\\d+)?"), line);
            }
        }
    }

    @Test void cacheCountersDescribeOnlyThisRasterPassAndDisappearWithoutAnOwner() {
        var output = new StringWriter();
        var host = new TerminalEditorApp();
        var profiler = host.new FrameProfiler(() -> 0L);
        profiler.setOutput(new PrintWriter(output), false);
        warmSamples(profiler);
        profiler.beginFrame("source");
        profiler.beginRasterCache(42, 51, 60);
        profiler.endRasterCache(44, 56, 80, 512);
        profiler.endFrame(true);
        assertTrue(output.toString().contains(
            " metrics_builds=2 cache_misses=5 rows_reused=20 retained_cache_bytes=512"), output.toString());

        output.getBuffer().setLength(0);
        warmSamples(profiler);
        profiler.beginFrame("preview");
        profiler.compositionFinished(false);
        profiler.endFrame(true);
        assertTrue(output.toString().contains("surface=preview ready=0"), output.toString());
        assertFalse(output.toString().contains("metrics_builds="), output.toString());
    }

    private static void warmSamples(TerminalEditorApp.FrameProfiler profiler) {
        for (int index = 0; index < 29; index++) {
            profiler.beginFrame("source");
            profiler.compositionFinished(true);
            profiler.endFrame(true);
        }
    }
}
