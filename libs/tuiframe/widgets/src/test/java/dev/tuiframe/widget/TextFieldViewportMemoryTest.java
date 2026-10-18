package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TextFieldViewportMemoryTest {
    @Test
    void longAsciiAndSparseUnicodeFieldsLayoutAndPanWithinASmallHeap() throws Exception {
        runProbe("ascii");
    }

    @Test
    void denseUnicodeFieldsKeepWholeGraphemesWhilePanningAndEditingWithinASmallHeap() throws Exception {
        runProbe("unicode");
    }

    @Test
    void repeatedLegalDenseUnicodePastesAreRejectedBeforeExhaustingTheHeap() throws Exception {
        runProbe("growth");
    }

    @Test
    void sixteenMiBOfAsciiPasteCanStillBeEditedOn64MiB() throws Exception {
        runProbe("ascii-growth");
    }

    @Test
    void disabledCompactStringsStillRejectGrowthWithoutPublishingPartialInput() throws Exception {
        runProbe("wide-ascii-growth");
    }

    private static void runProbe(String mode) throws Exception {
        String classpath = String.join(File.pathSeparator, location(TextFieldViewportMemoryTest.class),
            location(TextField.class), location(TerminalText.class));
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            mode.equals("growth") ? "-Xmx128m" : "-Xmx64m"));
        if (mode.equals("wide-ascii-growth")) command.add("-XX:-CompactStrings");
        command.addAll(List.of("-cp", classpath, MemoryProbe.class.getName(), mode));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Field viewport probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        public static void main(String[] args) {
            if (args[0].equals("growth")) {
                repeatedPastes("界", 8);
                return;
            }
            if (args[0].equals("wide-ascii-growth")) {
                repeatedPastes("b", 24);
                return;
            }
            if (args[0].equals("ascii-growth")) {
                repeatedAsciiPastes();
                return;
            }
            String prefix = args[0].equals("ascii") ? "a".repeat(4 * 1024 * 1024) : "界".repeat(1_000_000);
            String source = prefix + "\t界e\u0301👩‍💻Z";
            // Exercise both the generic scanner and the field's indexed viewport at each end.
            require(CellText.clip(source, 0, 8).equals(args[0].equals("ascii") ? "aaaaaaaa" : "界界界界"),
                "The visible prefix changed");
            int width = CellText.width(source);
            require(CellText.clip(source, width - 6, 6).equals("界e\u0301👩‍💻Z"), "The visible Unicode tail changed");
            TextField field = new TextField(TextField.PastePolicy.PRESERVE_TABS);
            field.paste(source);
            paint(field, 8);
            require(field.cursor() == source.length(), "Paste must retain its final caret");
            field.moveHome();
            paint(field, 8);
            field.moveEnd();
            field.backspace();
            field.backspace();
            require(field.text().endsWith("\t界e\u0301"), "Backspace split the emoji grapheme");
            require(field.cursor() == field.text().length(), "Editing must retain a grapheme boundary");
            paint(field, 3);
            field.insert("👩‍💻Z");
            require(field.text().equals(source), "Panning and editing changed the field text");
            paint(field, 12);
        }

        private static void repeatedAsciiPastes() {
            TextField field = new TextField();
            String chunk = "b".repeat(1_048_576);
            Rect bounds = new Rect(0, 0, 15, 1);
            FocusContext focused = new FocusContext(true, true, FocusSpace.root(bounds));
            for (int attempt = 0; attempt < 16; attempt++) {
                var result = field.layout(bounds).handle(new PasteEvent(chunk), focused);
                require(result.output().orElseThrow() == TextField.Action.VALUE_CHANGED,
                    "A legal ASCII paste was refused at chunk " + (attempt + 1));
            }
            require(field.length() == 16 * 1_048_576, "ASCII paste growth lost input");
            require(field.cursor() == field.length(), "ASCII paste growth moved the caret");
            field.backspace();
            require(field.length() == 16 * 1_048_576 - 1, "ASCII deletion was refused");
            field.selectAll();
            require(field.insert("done"), "Replacing the large ASCII selection was refused");
            require(field.text().equals("done"), "Replacing the large ASCII selection lost input");
            paint(field, 15);
        }

        private static void repeatedPastes(String character, int attempts) {
            TextField field = new TextField();
            String paste = character.repeat(1_048_576);
            Rect bounds = new Rect(0, 0, 15, 1);
            FocusContext focused = new FocusContext(true, true, FocusSpace.root(bounds));
            int accepted = 0;
            boolean rejected = false;
            for (int attempt = 0; attempt < attempts; attempt++) {
                if (!field.isEmpty()) field.navigate(KeyStroke.Key.ARROW_LEFT, false, true);
                String before = field.text();
                int cursor = field.cursor();
                int selectionStart = field.selectionStart();
                int selectionEnd = field.selectionEnd();
                var result = field.layout(bounds).handle(new PasteEvent(paste), focused);
                if (result.output().orElseThrow() == TextField.Action.INPUT_REJECTED) {
                    rejected = true;
                    require(field.text() == before, "Rejected paste replaced the published string");
                    require(field.cursor() == cursor, "Rejected paste moved the caret");
                    require(field.selectionStart() == selectionStart && field.selectionEnd() == selectionEnd,
                        "Rejected paste changed the selection");
                    paint(field, 15);
                    field.clear();
                    require(field.insert("still usable"), "The field did not recover after rejection");
                    require(field.text().equals("still usable"), "The field lost subsequent input");
                    break;
                }
                accepted++;
                require(result.output().orElseThrow() == TextField.Action.VALUE_CHANGED, "Unexpected paste action");
            }
            require(accepted > 0, "The memory policy rejected every normal 1 MiB paste");
            require(rejected, "Field growth did not reach the admission limit");
        }

        private static void paint(TextField field, int width) {
            CellBuffer buffer = new CellBuffer(1, width);
            field.layout(new Rect(0, 0, width, 1)).paint(new Canvas(buffer), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
