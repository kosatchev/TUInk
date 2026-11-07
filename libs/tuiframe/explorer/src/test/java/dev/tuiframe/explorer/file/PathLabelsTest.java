package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathLabelsTest {
    @TempDir Path directory;

    @Test
    void readableUnicodeNamesKeepTheirLiteralSpelling() {
        for (String name : List.of("report.txt", "a%FF.txt", "a�.txt", "e\u0301👩‍💻界.txt")) {
            Path path = directory.resolve(name);
            assertEquals(name, PathLabels.name(path));
            assertEquals(path.toString(), PathLabels.full(path));
        }
    }

    @Test
    void unsafeAndNativeBytePathsHaveDistinctReversibleSpellings() {
        assumeTrue(File.separatorChar == '/');
        List<Path> paths = List.of(raw("a%FF.txt"), raw("a%FE.txt"), directory.resolve("a�.txt"),
            directory.resolve("a%FF.txt"), directory.resolve("file:a%FF.txt"), directory.resolve("a\n.txt"),
            directory.resolve("a\u202Eb.txt"), directory.resolve("a\u200Bb.txt"));
        var labels = new HashSet<String>();
        var inputs = new HashSet<String>();
        for (Path path : paths) {
            assertTrue(labels.add(PathLabels.name(path)));
            String input = PathLabels.full(path);
            assertTrue(inputs.add(input));
            assertEquals(path, input.startsWith("file:/") ? Path.of(URI.create(input)) : Path.of(input));
        }
        assertEquals("file:a%FF.txt", PathLabels.name(paths.get(0)));
        assertEquals("file:file%3Aa%25FF.txt", PathLabels.name(paths.get(4)));
        assertEquals("file:a%0A.txt", PathLabels.name(paths.get(5)));
        assertEquals("file:%CC%81.txt", PathLabels.name(directory.resolve("\u0301.txt")));
        assertTrue(PathLabels.full(Path.of("file:name.txt")).startsWith("file:/"));
    }

    private Path raw(String name) {
        return Path.of(URI.create(directory.toUri().toASCIIString() + name));
    }
}
