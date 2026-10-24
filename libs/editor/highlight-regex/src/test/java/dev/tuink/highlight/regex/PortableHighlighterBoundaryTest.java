package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.highlight.SyntaxHighlighter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class PortableHighlighterBoundaryTest {
    @Test
    void productionBytecodeDoesNotReferToMutableCoreOrFrontendImplementations() throws Exception {
        assertPortableReferences(RegexHighlighter.class);
        assertPortableReferences(SyntaxHighlighter.class);
    }

    private static void assertPortableReferences(Class<?> productionType) throws Exception {
        Path classes = Path.of(productionType.getProtectionDomain().getCodeSource().getLocation().toURI());
        int inspected = 0;
        if (Files.isDirectory(classes)) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    assertPortableReferences(Files.readAllBytes(file), file.toString());
                    inspected++;
                }
            }
        } else {
            assertTrue(Files.isRegularFile(classes), "Missing production classes: " + classes);
            try (ZipFile jar = new ZipFile(classes.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                    try (var input = jar.getInputStream(entry)) {
                        assertPortableReferences(input.readAllBytes(), classes + "!/" + entry.getName());
                    }
                    inspected++;
                }
            }
        }
        assertTrue(inspected > 0, "No production bytecode inspected: " + classes);
    }

    private static void assertPortableReferences(byte[] bytecode, String classFile) {
        String pool = new String(bytecode, StandardCharsets.ISO_8859_1);
        for (String forbidden : new String[] {"dev/tuink/core/", "dev/tuink/render/", "dev/tuiframe/",
            "org/jline/", "android/", "java/awt/", "javax/swing/", "javafx/"}) {
            assertFalse(pool.contains(forbidden), () -> classFile + " refers to " + forbidden);
        }
    }
}
