package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DocumentFileMetadataSequenceTest {
    @Test
    void editsHugeHomogeneousMetadataWithoutMaterializingItsSeparators() {
        DocumentFileMetadata original = DocumentFileMetadata.newDocument(1_000_000_001);
        DocumentFileMetadata mixed = original.withLineSeparator(500_000_000, "\r\n");
        DocumentFileMetadata inserted = mixed.insertLineSeparators(250_000_000, 10);
        DocumentFileMetadata removed = inserted.removeLineSeparators(0, 500_000_010);

        assertEquals(1_000_000_000, original.lineSeparators().size());
        assertEquals("\n", original.lineSeparators().get(500_000_000));
        assertEquals("\n", original.lineSeparators().getLast());
        assertEquals("\r\n", mixed.lineSeparators().get(500_000_000));
        assertEquals("\r\n", inserted.lineSeparators().get(500_000_010));
        assertEquals(500_000_000, removed.lineSeparators().size());
        assertEquals("\r\n", removed.lineSeparators().getFirst());
        assertEquals("\n", removed.lineSeparators().getLast());
        assertEquals(List.of("\n", "\r\n", "\n"), mixed.lineSeparators().subList(499_999_999, 500_000_002));
    }

    @Test
    void rangeEditsAndBidirectionalTraversalMatchAnOrdinaryMixedList() {
        Random random = new Random(0xE01);
        List<String> expected = new ArrayList<>(List.of("\r", "\n", "\r\n"));
        DocumentFileMetadata metadata = metadata(expected);
        for (int edit = 0; edit < 2_000; edit++) {
            DocumentFileMetadata before = metadata;
            List<String> saved = new ArrayList<>(expected);
            int index = random.nextInt(expected.size() + 1);
            int removed = random.nextInt(expected.size() - index + 1);
            switch (edit % 4) {
                case 0 -> {
                    List<String> inserted = randomSeparators(random, random.nextInt(8));
                    metadata = metadata.replaceLineSeparators(index, removed, inserted);
                    expected.subList(index, index + removed).clear();
                    expected.addAll(index, inserted);
                }
                case 1 -> {
                    int count = random.nextInt(8);
                    metadata = metadata.insertLineSeparators(index, count);
                    expected.addAll(index, java.util.Collections.nCopies(count, "\n"));
                }
                case 2 -> {
                    metadata = metadata.removeLineSeparators(index, removed);
                    expected.subList(index, index + removed).clear();
                }
                default -> {
                    if (!expected.isEmpty()) {
                        index = random.nextInt(expected.size());
                        String separator = randomSeparators(random, 1).getFirst();
                        metadata = metadata.withLineSeparator(index, separator);
                        expected.set(index, separator);
                    }
                }
            }
            assertEquals(saved, before.lineSeparators(), "an edit must retain earlier metadata");
            assertEquals(expected, metadata.lineSeparators());
            assertEquals(metadata.lineSeparators(), expected);
            assertEquals(expected.hashCode(), metadata.lineSeparators().hashCode());

            ListIterator<String> actual = metadata.lineSeparators().listIterator(expected.size());
            for (int position = expected.size() - 1; position >= 0; position--) {
                assertEquals(expected.get(position), actual.previous());
                assertEquals(expected.get(position), actual.next());
                assertEquals(expected.get(position), actual.previous());
            }
            assertFalse(actual.hasPrevious());
            for (String separator : expected) {
                assertEquals(separator, actual.next());
            }
            assertFalse(actual.hasNext());

            int from = random.nextInt(expected.size() + 1);
            int to = from + random.nextInt(expected.size() - from + 1);
            assertEquals(expected.subList(from, to), metadata.lineSeparators().subList(from, to));
        }
    }

    @Test
    void keepsLongMixedSequencesOrderedThroughAppendAndMiddleEdits() {
        String[] separators = {"\n", "\r\n", "\r"};
        List<String> expected = new ArrayList<>();
        DocumentFileMetadata metadata = metadata(List.of());
        for (int index = 0; index < 8_192; index++) {
            String separator = separators[index % separators.length];
            metadata = metadata.replaceLineSeparators(index, 0, List.of(separator));
            expected.add(separator);
        }
        DocumentFileMetadata original = metadata;
        for (int edit = 0; edit < 1_024; edit++) {
            int index = metadata.lineSeparators().size() / 2;
            metadata = metadata.replaceLineSeparators(index, 3, List.of("\r\n", "\n"));
            expected.subList(index, index + 3).clear();
            expected.addAll(index, List.of("\r\n", "\n"));
        }
        assertEquals(expected, metadata.lineSeparators());
        assertEquals(8_192, original.lineSeparators().size());
        for (int index = 0; index < original.lineSeparators().size(); index++) {
            assertEquals(separators[index % separators.length], original.lineSeparators().get(index));
        }
    }

    @Test
    void constructorSnapshotsExternalListsAndReusesOnlyItsOwnImmutableSequence() {
        List<String> external = new ArrayList<>(List.of("\r", "\n", "\r\n"));
        DocumentFileMetadata metadata = metadata(external);
        external.clear();

        assertEquals(List.of("\r", "\n", "\r\n"), metadata.lineSeparators());
        assertSame(metadata.lineSeparators(), metadata(metadata.lineSeparators()).lineSeparators());
        assertSame(metadata, metadata.insertLineSeparators(1, 0));
        assertSame(metadata, metadata.removeLineSeparators(1, 0));
        assertSame(metadata, metadata.withLineSeparator(1, "\n"));
        assertThrows(UnsupportedOperationException.class, () -> metadata.lineSeparators().set(0, "\n"));
        assertThrows(UnsupportedOperationException.class,
            () -> metadata.lineSeparators().subList(0, 2).clear());
        ListIterator<String> iterator = metadata.lineSeparators().listIterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> iterator.add("\n"));
        assertThrows(UnsupportedOperationException.class, () -> iterator.set("\n"));
    }

    @Test
    void validatesNewSeparatorsAndRangeBoundsBeforeCreatingMetadata() {
        DocumentFileMetadata metadata = metadata(List.of("\r", "\n"));
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of("\n", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> metadata.withLineSeparator(0, null));
        assertThrows(IllegalArgumentException.class,
            () -> metadata.replaceLineSeparators(0, 1, List.of("invalid")));
        assertThrows(IllegalArgumentException.class, () -> metadata.insertLineSeparators(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> metadata.removeLineSeparators(1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> metadata.insertLineSeparators(3, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> metadata.lineSeparators().listIterator(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> metadata.lineSeparators().subList(0, 3));
        assertEquals(List.of("\r", "\n"), metadata.lineSeparators());
    }

    private static List<String> randomSeparators(Random random, int count) {
        String[] separators = {"\n", "\r\n", "\r"};
        List<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(separators[random.nextInt(separators.length)]);
        }
        return result;
    }

    private static DocumentFileMetadata metadata(List<String> separators) {
        return new DocumentFileMetadata(StandardCharsets.UTF_8, BomKind.NONE, "\n", separators);
    }
}
