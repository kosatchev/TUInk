package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Editing operations and persistence over a {@link TextBuffer}.
 */
public class TextModel {
    private static final AtomicLong NEXT_CACHE_ID = new AtomicLong();
    private static final int CHANGE_HISTORY_CAPACITY = 128;
    static final int MAX_LOAD_BYTES = 64 * 1024 * 1024;
    private TextBuffer buffer;
    private final int[] firstChangedLines = new int[CHANGE_HISTORY_CAPACITY];
    private final LineChange[] lineChanges = new LineChange[CHANGE_HISTORY_CAPACITY];
    private static final Charset FALLBACK_CHARSET = Charset.forName("windows-1251");
    private static final Set<PosixFilePermission> NEW_FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE
    );
    private long revision = 0L;
    private long contentEpoch = 0L;
    private long lastContentReplacementRevision;
    private final long cacheIdentity = NEXT_CACHE_ID.incrementAndGet();
    private DocumentFileMetadata fileMetadata;
    private FileBaseline fileBaseline;
    private final FileBaseline.InputOpener baselineInput;
    private EditorDocument documentOwner;
    private LocalEdit localEdit;

    /** Isolates a keyboard command without copying untouched document lines. */
    LocalEdit beginLocalEdit() {
        if (localEdit != null) throw new IllegalStateException("A local edit is already active");
        LocalEdit prepared = new LocalEdit();
        localEdit = prepared;
        buffer = prepared.staged;
        return prepared;
    }

    final class LocalEdit {
        private final TextBuffer source = buffer;
        private final LocalEditBuffer staged = new LocalEditBuffer(source);
        private final DocumentFileMetadata metadataBefore = fileMetadata;
        private final long revisionBefore = revision;
        private final long epochBefore = contentEpoch;
        private final long replacementRevisionBefore = lastContentReplacementRevision;
        private final int[] changedBefore = firstChangedLines.clone();
        private final LineChange[] changesBefore = lineChanges.clone();
        private TextBuffer publication;

        void preparePublication() { publication = staged.preparePublication(); }

        void publish() {
            staged.publishTo(publication);
            buffer = publication;
            localEdit = null;
        }

        void abort() {
            buffer = source;
            fileMetadata = metadataBefore;
            revision = revisionBefore;
            contentEpoch = epochBefore;
            lastContentReplacementRevision = replacementRevisionBefore;
            System.arraycopy(changedBefore, 0, firstChangedLines, 0, CHANGE_HISTORY_CAPACITY);
            System.arraycopy(changesBefore, 0, lineChanges, 0, CHANGE_HISTORY_CAPACITY);
            localEdit = null;
        }
    }

    void claimDocument(EditorDocument owner) {
        if (documentOwner != null && documentOwner != owner) {
            throw new IllegalArgumentException("a text model already belongs to an editor document; attach a view instead");
        }
        documentOwner = Objects.requireNonNull(owner, "owner");
    }

    public TextModel(TextBuffer sourceBuffer) {
        this(sourceBuffer, Files::newInputStream);
    }

    TextModel(TextBuffer sourceBuffer, FileBaseline.InputOpener baselineInput) {
        this.baselineInput = Objects.requireNonNull(baselineInput, "baselineInput");
        TextBuffer source = Objects.requireNonNull(sourceBuffer, "buffer");
        int sourceLineCount = source.lineCount();
        List<String> imported = new ArrayList<>(Math.max(1, sourceLineCount));
        for (int line = 0; line < sourceLineCount; line++) {
            imported.add(source.getLine(line));
        }
        List<String> checked = LogicalDocumentLines.copyOfOrEmptyDocument(imported);
        TextBuffer owned = Objects.requireNonNull(
            source.createEmptySibling(),
            "buffer.createEmptySibling()"
        );
        if (owned == source || owned.lineCount() != 1 || !owned.getLine(0).isEmpty()) {
            throw new IllegalArgumentException(
                "createEmptySibling() must return an independent buffer with one empty line"
            );
        }
        owned.setLine(0, checked.get(0));
        for (int line = 1; line < checked.size(); line++) {
            owned.insertLine(line, checked.get(line));
        }
        this.buffer = owned;
        this.fileMetadata = DocumentFileMetadata.newDocument(checked.size());
    }

    private TextModel(TextBuffer ownedBuffer, DocumentFileMetadata metadata) {
        this.baselineInput = Files::newInputStream;
        this.buffer = ownedBuffer;
        this.fileMetadata = metadata;
    }

    public int lineCount() {
        return buffer.lineCount();
    }

    public String getLine(int line) {
        return buffer.getLine(line);
    }

    public LineText lineText(int line) { return buffer.lineText(line); }
    public int lineLength(int line) { return lineText(line).length(); }
    public String lineRange(int line, int start, int end) { return lineText(line).sliceString(start, end); }

    public List<LineText> snapshotTextLines() {
        if (buffer instanceof LocalEditBuffer staged) return staged.snapshotTextLines();
        List<LineText> lines = new ArrayList<>(lineCount());
        for (int line = 0; line < lineCount(); line++) lines.add(lineText(line));
        return List.copyOf(lines);
    }

    public Position insertChar(Position position, char ch) {
        if (ch == '\n' || ch == '\r') {
            throw new IllegalArgumentException("Use insertNewline or insertText for line separators");
        }
        if (Character.isSurrogate(ch)) {
            throw new IllegalArgumentException("A standalone UTF-16 surrogate is not valid text");
        }
        Position at = requireCodePointBoundary(position);
        String line = buffer.getLine(at.line());
        int col = at.col();
        String updated = line.substring(0, col) + ch + line.substring(col);
        buffer.setLine(at.line(), updated);
        markChanged(at.line());
        return new Position(at.line(), col + 1);
    }

    public Position insertNewline(Position position) {
        Position at = requireCodePointBoundary(position);
        String line = buffer.getLine(at.line());
        int col = at.col();
        String left = line.substring(0, col);
        String right = line.substring(col);
        buffer.setLine(at.line(), left);
        buffer.insertLine(at.line() + 1, right);
        insertDefaultLineSeparators(at.line(), 1);
        markChanged(at.line(), 1, 2);
        return new Position(at.line() + 1, 0);
    }

    public Position deleteBackward(Position position) {
        if (position.col() > 0) {
            String line = buffer.getLine(position.line());
            int col = Math.min(position.col(), line.length());
            GraphemeText graphemes = GraphemeText.of(line);
            int end = graphemes.ceilBoundary(col);
            int previous = graphemes.previousBoundary(end);
            String updated = line.substring(0, previous) + line.substring(end);
            buffer.setLine(position.line(), updated);
            markChanged(position.line());
            return new Position(position.line(), previous);
        }

        if (position.line() == 0) {
            return position;
        }

        String current = buffer.removeLine(position.line());
        int prevLineIndex = position.line() - 1;
        String prev = buffer.getLine(prevLineIndex);
        buffer.setLine(prevLineIndex, prev + current);
        removeLineSeparators(prevLineIndex, 1);
        markChanged(prevLineIndex, 2, 1);
        return new Position(prevLineIndex, prev.length());
    }

    public Position deleteForward(Position position) {
        String line = buffer.getLine(position.line());
        int col = Math.min(position.col(), line.length());

        if (col < line.length()) {
            GraphemeText graphemes = GraphemeText.of(line);
            int start = graphemes.floorBoundary(col);
            int next = graphemes.nextBoundary(start);
            String updated = line.substring(0, start) + line.substring(next);
            buffer.setLine(position.line(), updated);
            markChanged(position.line());
            return new Position(position.line(), start);
        }

        if (position.line() >= buffer.lineCount() - 1) {
            return new Position(position.line(), col);
        }

        String next = buffer.removeLine(position.line() + 1);
        buffer.setLine(position.line(), line + next);
        removeLineSeparators(position.line(), 1);
        markChanged(position.line(), 2, 1);
        return new Position(position.line(), col);
    }

    public String getTextRange(Position start, Position end) {
        Position a = requireCodePointBoundary(start);
        Position b = requireCodePointBoundary(end);
        if (comesAfter(a, b)) {
            Position t = a;
            a = b;
            b = t;
        }
        if (a.equals(b)) {
            return "";
        }
        if (a.line() == b.line()) {
            String line = buffer.getLine(a.line());
            return line.substring(a.col(), b.col());
        }

        long length = textRangeLengthOrdered(a, b);
        if (length > Integer.MAX_VALUE - 8L) {
            throw new OutOfMemoryError("Text range exceeds the maximum String size");
        }
        StringBuilder out = new StringBuilder((int) length);
        String first = buffer.getLine(a.line());
        out.append(first, a.col(), first.length());
        out.append('\n');
        for (int line = a.line() + 1; line < b.line(); line++) {
            out.append(buffer.getLine(line)).append('\n');
        }
        out.append(buffer.getLine(b.line()), 0, b.col());
        return out.toString();
    }

    long textRangeLength(Position start, Position end) {
        Position a = requireCodePointBoundary(start);
        Position b = requireCodePointBoundary(end);
        return comesAfter(a, b) ? textRangeLengthOrdered(b, a) : textRangeLengthOrdered(a, b);
    }

    private long textRangeLengthOrdered(Position start, Position end) {
        if (start.line() == end.line()) return end.col() - (long) start.col();
        long length = buffer.getLine(start.line()).length() - (long) start.col() + 1 + end.col();
        for (int line = start.line() + 1; line < end.line(); line++) {
            length += buffer.getLine(line).length() + 1L;
        }
        return length;
    }

    public Position deleteRange(Position start, Position end) {
        Position a = requireCodePointBoundary(start);
        Position b = requireCodePointBoundary(end);
        if (comesAfter(a, b)) {
            Position t = a;
            a = b;
            b = t;
        }
        if (a.equals(b)) {
            return a;
        }

        if (a.line() == b.line()) {
            String line = buffer.getLine(a.line());
            buffer.setLine(a.line(), line.substring(0, a.col()) + line.substring(b.col()));
            markChanged(a.line());
            return a;
        }

        String head = buffer.getLine(a.line()).substring(0, a.col());
        String tail = buffer.getLine(b.line()).substring(b.col());
        buffer.setLine(a.line(), head + tail);
        buffer.removeLines(a.line() + 1, b.line() + 1);
        removeLineSeparators(a.line(), b.line() - a.line());
        markChanged(a.line(), b.line() - a.line() + 1, 1);
        return a;
    }

    public Position insertText(Position position, String text) {
        if (text == null || text.isEmpty()) {
            return clamp(position);
        }
        LogicalDocumentLines.requireWellFormedUtf16(text);

        Position at = requireCodePointBoundary(position);
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        String line = buffer.getLine(at.line());
        int col = Math.min(at.col(), line.length());
        String left = line.substring(0, col);
        String right = line.substring(col);

        if (parts.length == 1) {
            buffer.setLine(at.line(), left + parts[0] + right);
            markChanged(at.line());
            return new Position(at.line(), col + parts[0].length());
        }

        buffer.setLine(at.line(), left + parts[0]);
        String last = parts[parts.length - 1];
        parts[parts.length - 1] = last + right;
        buffer.insertLines(at.line() + 1, Arrays.asList(parts).subList(1, parts.length));
        insertDefaultLineSeparators(at.line(), parts.length - 1);
        markChanged(at.line(), 1, parts.length);
        return new Position(at.line() + parts.length - 1, last.length());
    }

    /** Admits local replacement allocations, including undo, before deleting any source text. */
    void requireReplacementFits(Position start, Position end, ReplacementText replacement) {
        long deletedUnits;
        if (start.line() == end.line()) {
            deletedUnits = end.col() - (long) start.col();
        } else {
            deletedUnits = getLine(start.line()).length() - (long) start.col() + 1 + end.col();
            for (int line = start.line() + 1; line < end.line(); line++) {
                deletedUnits = DocumentEditBudget.add(deletedUnits, getLine(line).length() + 1L);
            }
        }
        long retainedUnits = start.col() + (long) getLine(end.line()).length() - end.col();
        long insertedUnits = replacement.text().length();
        long copiedUnits = DocumentEditBudget.add(deletedUnits,
            DocumentEditBudget.add(retainedUnits, insertedUnits));
        long changedLines = replacement.lineBreaks() + 1L;
        long resultLines = lineCount() - (end.line() - (long) start.line()) + replacement.lineBreaks();
        long longestLine = replacement.lineBreaks() == 0
            ? DocumentEditBudget.add(retainedUnits, insertedUnits)
            : Math.max(DocumentEditBudget.add(start.col(), replacement.firstEnd()),
                Math.max(replacement.longestInteriorLine(),
                    insertedUnits - replacement.lastStart() + getLine(end.line()).length() - end.col()));
        DocumentEditBudget budget = DocumentEditBudget.currentHeap(lineCount());
        if (localEdit != null && localEdit.staged.supportsLocalPublication()) {
            budget.requireStagedReplacement(copiedUnits, resultLines, changedLines, longestLine,
                localEdit.staged.additionalCapacity((int) resultLines),
                start.line() == end.line() && replacement.lineBreaks() == 0);
        } else {
            budget.requireLocalReplacement(copiedUnits, resultLines, changedLines, longestLine);
        }
    }

    /**
     * Builds a local replacement without touching the active buffer. Ordinary
     * single-line replacements retain just the changed line; structural changes
     * use a sibling buffer and share all untouched immutable line strings.
     */
    PreparedReplacement prepareReplacement(Position start, Position end, ReplacementText replacement) {
        Position a = requireCodePointBoundary(start);
        Position b = requireCodePointBoundary(end);
        if (comesAfter(a, b)) {
            Position swap = a;
            a = b;
            b = swap;
        }
        requireReplacementFits(a, b, replacement);
        String inserted = replacement.text().replace("\r\n", "\n").replace('\r', '\n');
        // Only this storage strategy guarantees that publishing setLine merely
        // assigns an already allocated reference. Other strategies stage a sibling.
        if (buffer.getClass() == SimpleLineBuffer.class
            && a.line() == b.line() && replacement.lineBreaks() == 0) {
            String source = buffer.getLine(a.line());
            String updated = source.substring(0, a.col()) + inserted + source.substring(b.col());
            DocumentFileMetadata metadata = fileMetadata;
            List<String> separators = metadata.lineSeparators();
            if (updated.isEmpty() && a.line() > 0 && a.line() < lineCount() - 1
                && EditorState.LINE_SEPARATOR_CR.equals(separators.get(a.line() - 1))
                && EditorState.LINE_SEPARATOR_LF.equals(separators.get(a.line()))) {
                metadata = metadata.withLineSeparator(a.line(), EditorState.LINE_SEPARATOR_CRLF);
            }
            int firstSeparator = Math.max(0, a.line() - 1);
            ReplacementDelta delta = new ReplacementDelta(
                EditOp.replace(a, source.substring(a.col(), b.col()), inserted), firstSeparator,
                separatorSlice(firstSeparator, b.line()),
                List.copyOf(metadata.lineSeparators().subList(firstSeparator,
                    Math.min(metadata.lineSeparators().size(), a.line() + 1))));
            return new PreparedReplacement(buffer, revision, null, updated, metadata, delta,
                new LineChange(a.line(), 1, 1));
        }
        TextBuffer nextBuffer = Objects.requireNonNull(buffer.createEmptySibling(), "buffer.createEmptySibling()");
        if (nextBuffer == buffer || nextBuffer.lineCount() != 1 || !nextBuffer.getLine(0).isEmpty()) {
            throw new IllegalArgumentException("createEmptySibling() must return an independent empty buffer");
        }
        nextBuffer.setLine(0, buffer.getLine(0));
        for (int line = 1; line < lineCount(); line++) {
            nextBuffer.insertLine(line, buffer.getLine(line));
        }
        TextModel staged = new TextModel(nextBuffer, fileMetadata);
        ReplacementDelta delta = staged.replaceRange(a, b, inserted);
        return new PreparedReplacement(buffer, revision, nextBuffer, null, staged.fileMetadata, delta,
            new LineChange(a.line(), b.line() - a.line() + 1, replacement.lineBreaks() + 1));
    }

    /** All text, metadata and change records have already been allocated. */
    void publishReplacement(PreparedReplacement prepared) {
        if (prepared.source() != buffer || prepared.revision() != revision) {
            throw new IllegalStateException("The prepared replacement is stale");
        }
        if (prepared.buffer() == null) {
            buffer.setLine(prepared.change().firstLine(), prepared.line());
        } else {
            buffer = prepared.buffer();
        }
        fileMetadata = prepared.metadata();
        revision++;
        int slot = (int) (revision % CHANGE_HISTORY_CAPACITY);
        firstChangedLines[slot] = prepared.change().firstLine();
        lineChanges[slot] = prepared.change();
    }

    record PreparedReplacement(TextBuffer source, long revision, TextBuffer buffer, String line,
        DocumentFileMetadata metadata, ReplacementDelta delta, LineChange change) {
        String resultingLine(int index) {
            return buffer != null ? buffer.getLine(index)
                : index == change.firstLine() ? line : source.getLine(index);
        }
    }

    /**
     * Completes one logical replacement, including any adjacent CR/LF disambiguation.
     * Ranges retain the clamping and ordering rules of {@link #deleteRange}; both
     * endpoints and the inserted UTF-16 text are checked before the first mutation.
     */
    ReplacementDelta replaceRange(Position start, Position end, String text) {
        Position a = requireCodePointBoundary(start);
        Position b = requireCodePointBoundary(end);
        if (comesAfter(a, b)) {
            Position swap = a;
            a = b;
            b = swap;
        }
        String inserted = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        LogicalDocumentLines.requireWellFormedUtf16(inserted);
        if (localEdit != null) requireReplacementFits(a, b, new ReplacementText(inserted));
        String deleted = getTextRange(a, b);
        EditOp.ReplaceRange operation = EditOp.replace(a, deleted, inserted);
        int firstSeparator = Math.max(0, a.line() - 1);
        if (deleted.isEmpty() && inserted.isEmpty()) {
            return new ReplacementDelta(operation, firstSeparator, List.of(), List.of());
        }
        List<String> before = separatorSlice(firstSeparator, b.line());
        if (localEdit != null && a.line() == b.line() && inserted.indexOf('\n') < 0) {
            String source = buffer.getLine(a.line());
            char[] updated = new char[source.length() - (b.col() - a.col()) + inserted.length()];
            source.getChars(0, a.col(), updated, 0);
            inserted.getChars(0, inserted.length(), updated, a.col());
            source.getChars(b.col(), source.length(), updated, a.col() + inserted.length());
            buffer.setLine(a.line(), new String(updated));
            markChanged(a.line());
            normalizeLineSeparators(a.line(), a.line());
            return new ReplacementDelta(operation, firstSeparator, before, separatorSlice(firstSeparator, a.line()));
        }
        Position after = deleteRange(a, b);
        if (!inserted.isEmpty()) {
            after = insertText(after, inserted);
        }
        normalizeLineSeparators(a.line(), after.line());
        return new ReplacementDelta(operation, firstSeparator, before, separatorSlice(firstSeparator, after.line()));
    }

    /** Builds a same-line batch without exposing intermediate text or separator changes. */
    PreparedLineReplacements prepareLineReplacements(List<TextReplacement> replacements) {
        TextBuffer nextBuffer = Objects.requireNonNull(buffer.createEmptySibling(), "buffer.createEmptySibling()");
        if (nextBuffer == buffer || nextBuffer.lineCount() != 1 || !nextBuffer.getLine(0).isEmpty()) {
            throw new IllegalArgumentException("createEmptySibling() must return an independent empty buffer");
        }
        nextBuffer.setLine(0, buffer.getLine(0));
        for (int line = 1; line < lineCount(); line++) {
            nextBuffer.insertLine(line, buffer.getLine(line));
        }
        TextModel staged = new TextModel(nextBuffer, fileMetadata);
        List<ReplacementDelta> operations = new ArrayList<>(replacements.size());
        int previousLine = -1;
        for (TextReplacement replacement : replacements) {
            if (replacement.start().line() != replacement.end().line()
                || replacement.start().line() <= previousLine
                || replacement.replacement().indexOf('\n') >= 0
                || replacement.replacement().indexOf('\r') >= 0) {
                throw new IllegalArgumentException("A line batch requires ordered, distinct single-line ranges");
            }
            staged.requireExactCodePointBoundary(replacement.start());
            staged.requireExactCodePointBoundary(replacement.end());
            ReplacementDelta delta = staged.replaceRange(
                replacement.start(), replacement.end(), replacement.replacement()
            );
            if (!delta.isEmpty()) operations.add(delta);
            previousLine = replacement.start().line();
        }
        int firstLine = replacements.getFirst().start().line();
        int span = replacements.getLast().start().line() - firstLine + 1;
        return new PreparedLineReplacements(buffer, revision, nextBuffer, staged.fileMetadata,
            operations, new LineChange(firstLine, span, span));
    }

    /** Publication only assigns prepared references; allocation failures cannot expose half a batch. */
    void publishLineReplacements(PreparedLineReplacements prepared) {
        if (prepared.source() != buffer || prepared.revision() != revision) {
            throw new IllegalStateException("The prepared line batch is stale");
        }
        buffer = prepared.buffer();
        fileMetadata = prepared.metadata();
        revision++;
        int slot = (int) (revision % CHANGE_HISTORY_CAPACITY);
        firstChangedLines[slot] = prepared.change().firstLine();
        lineChanges[slot] = prepared.change();
    }

    record PreparedLineReplacements(TextBuffer source, long revision, TextBuffer buffer,
        DocumentFileMetadata metadata, List<ReplacementDelta> operations, LineChange change) {
    }

    /**
     * Replays a captured replacement after checking its text and boundary EOLs.
     * Inversion is represented by {@link ReplacementDelta#inverse()}.
     */
    void replayReplacement(ReplacementDelta delta) {
        Objects.requireNonNull(delta, "delta");
        EditOp.ReplaceRange operation = delta.operation();
        Position start = requireExactCodePointBoundary(operation.start());
        Position end = requireExactCodePointBoundary(delta.endBefore());
        requireLogicalText(operation.deletedText());
        requireLogicalText(operation.insertedText());
        if (!getTextRange(start, end).equals(operation.deletedText())) {
            throw new IllegalArgumentException("Replacement no longer matches the document text");
        }
        if (delta.isEmpty()) {
            return;
        }
        List<String> currentSeparators = fileMetadata.lineSeparators();
        int currentCount = currentSeparators.size();
        int resultingCount = currentCount - (end.line() - start.line()) + (delta.endAfter().line() - start.line());
        int firstSeparator = Math.max(0, start.line() - 1);
        int beforeLimit = Math.min(currentCount, end.line() + 1);
        int afterLimit = Math.min(resultingCount, delta.endAfter().line() + 1);
        if (delta.firstSeparator() != firstSeparator
            || delta.deletedSeparators().size() != beforeLimit - firstSeparator
            || delta.insertedSeparators().size() != afterLimit - firstSeparator) {
            throw new IllegalArgumentException("Replacement separator slice does not fit the document");
        }
        if (!currentSeparators.subList(firstSeparator, beforeLimit).equals(delta.deletedSeparators())) {
            throw new IllegalArgumentException("Replacement no longer matches the document line separators");
        }
        Position after = deleteRange(start, end);
        if (!operation.insertedText().isEmpty()) {
            insertText(after, operation.insertedText());
        }
        // The delta already captures the completed replacement's exact boundary EOLs.
        // Re-normalizing the transient delete/insert states would lose that history.
        restoreLineSeparators(delta.firstSeparator(), delta.insertedSeparators());
    }

    private List<String> separatorSlice(int firstSeparator, int lastChangedLine) {
        List<String> separators = fileMetadata.lineSeparators();
        return List.copyOf(separators.subList(firstSeparator, Math.min(separators.size(), lastChangedLine + 1)));
    }

    private Position requireExactCodePointBoundary(Position position) {
        Position checked = requireCodePointBoundary(position);
        if (!checked.equals(position)) {
            throw new IllegalArgumentException("Replacement range is outside the document");
        }
        return checked;
    }

    private static void requireLogicalText(String text) {
        LogicalDocumentLines.requireWellFormedUtf16(text);
        if (text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Replacement text must use logical LF separators");
        }
    }

    /** An immutable text change with only the affected and adjacent separators. */
    record ReplacementDelta(
        EditOp.ReplaceRange operation,
        int firstSeparator,
        List<String> deletedSeparators,
        List<String> insertedSeparators
    ) {
        ReplacementDelta {
            operation = Objects.requireNonNull(operation, "operation");
            if (firstSeparator < 0) {
                throw new IllegalArgumentException("firstSeparator must not be negative");
            }
            deletedSeparators = copySeparators(deletedSeparators);
            insertedSeparators = copySeparators(insertedSeparators);
        }

        boolean isEmpty() {
            return operation.deletedText().isEmpty() && operation.insertedText().isEmpty();
        }

        Position endBefore() {
            return advance(operation.start(), operation.deletedText());
        }

        Position endAfter() {
            return advance(operation.start(), operation.insertedText());
        }

        ReplacementDelta inverse() {
            return new ReplacementDelta(
                EditOp.replace(operation.start(), operation.insertedText(), operation.deletedText()),
                firstSeparator, insertedSeparators, deletedSeparators
            );
        }

        private static List<String> copySeparators(List<String> separators) {
            List<String> owned = List.copyOf(separators);
            for (String separator : owned) {
                if (!EditorState.LINE_SEPARATOR_LF.equals(separator)
                    && !EditorState.LINE_SEPARATOR_CR.equals(separator)
                    && !EditorState.LINE_SEPARATOR_CRLF.equals(separator)) {
                    throw new IllegalArgumentException("Unknown replacement line separator");
                }
            }
            return owned;
        }

        private static Position advance(Position start, String text) {
            int line = start.line();
            int column = start.col();
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == '\n') {
                    line++;
                    column = 0;
                } else {
                    column++;
                }
            }
            return new Position(line, column);
        }
    }

    public Position clamp(Position requested) {
        int line = Math.max(0, Math.min(requested.line(), buffer.lineCount() - 1));
        int col = Math.max(0, Math.min(requested.col(), buffer.getLine(line).length()));
        return new Position(line, col);
    }

    private Position requireCodePointBoundary(Position requested) {
        Position clamped = clamp(Objects.requireNonNull(requested, "position"));
        String line = buffer.getLine(clamped.line());
        int offset = clamped.col();
        if (offset > 0
            && offset < line.length()
            && Character.isHighSurrogate(line.charAt(offset - 1))
            && Character.isLowSurrogate(line.charAt(offset))) {
            throw new IllegalArgumentException("Position splits a UTF-16 surrogate pair");
        }
        return clamped;
    }

    public LoadMetadata load(Path path) throws IOException {
        return load(path, DocumentOpenMode.DETECT);
    }

    public LoadMetadata load(Path path, DocumentOpenMode openMode) throws IOException {
        Objects.requireNonNull(openMode, "openMode");
        Path target = path.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("Not a regular file: " + path);
        }
        Runtime runtime = Runtime.getRuntime();
        long availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        // Leave headroom for existing tabs, decoding and the new view's derived state.
        long memoryBudget = Math.max(0L, availableHeap / 2);
        int maxBytes = (int) Math.min(MAX_LOAD_BYTES, memoryBudget / 8);
        if (attributes.size() > maxBytes) {
            throw loadSizeExceeded(target, maxBytes);
        }
        byte[] data;
        try (InputStream input = Files.newInputStream(target)) {
            data = readDocumentBytes(input, target, maxBytes);
        }
        DecodedContent decoded = decodeWithDetectedCharset(data, openMode);
        requireLoadFitsMemory(decoded.text(), data.length, memoryBudget, target);
        ParsedContent parsed = parseContent(decoded.text());
        DocumentFileMetadata loadedMetadata = new DocumentFileMetadata(
            decoded.charset(),
            decoded.bomKind(),
            detectDefaultLineSeparator(parsed.lineSeparators()),
            parsed.lineSeparators()
        );
        buffer.clear();
        buffer.setLine(0, parsed.lines().get(0));
        for (int i = 1; i < parsed.lines().size(); i++) {
            buffer.insertLine(i, parsed.lines().get(i));
        }
        fileMetadata = loadedMetadata;
        fileBaseline = FileBaseline.fromBytes(target, data);
        markContentReplaced();
        return new LoadMetadata(loadedMetadata);
    }

    /** The extra byte detects growth beyond the size observed before opening the stream. */
    static byte[] readDocumentBytes(InputStream input, Path target, int maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_LOAD_BYTES) {
            throw new IllegalArgumentException("Invalid document read limit");
        }
        byte[] data = input.readNBytes(maxBytes + 1);
        if (data.length > maxBytes) {
            throw loadSizeExceeded(target, maxBytes);
        }
        return data;
    }

    private static IOException loadSizeExceeded(Path target, int maxBytes) {
        return new IOException("File too large to open: " + target
            + ". Current limit: " + maxBytes + " bytes (at most 64 MiB, reduced by available memory).");
    }

    private static void requireLoadFitsMemory(String content, int encodedBytes, long budget, Path target)
        throws IOException {
        // A file made of short lines needs much more space than its byte count suggests.
        // Check before allocating the per-line strings, separator lists and view state.
        long lineCount = 1;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\r' || character == '\n') {
                lineCount++;
                if (character == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                    index++;
                }
            }
        }
        if (8L * encodedBytes + 128L * lineCount > budget) {
            throw new IOException("File too large to open with available memory: " + target
                + ". Too much text or too many lines.");
        }
    }

    public void save(Path path) throws IOException {
        save(path, fileMetadata);
    }

    public void save(Path path, Charset charset) throws IOException {
        save(path, charset, EditorState.LINE_SEPARATOR_LF);
    }

    public void save(Path path, Charset charset, String lineSeparator) throws IOException {
        save(path, fileMetadata.canonicalized(charset, lineSeparator, lineCount()));
    }

    public void save(Path path, DocumentFileMetadata metadata) throws IOException {
        save(path, metadata, SaveDisposition.REPLACE_EXISTING);
    }

    public void save(
        Path path,
        DocumentFileMetadata metadata,
        SaveDisposition disposition
    ) throws IOException {
        save(path, metadata, disposition, false);
    }

    /** Saves an owned path only while its last loaded/saved bytes still match. */
    public void saveIfUnchanged(
        Path path,
        DocumentFileMetadata metadata,
        SaveDisposition disposition
    ) throws IOException {
        save(path, metadata, disposition, true);
    }

    private void save(
        Path path,
        DocumentFileMetadata metadata,
        SaveDisposition disposition,
        boolean checkBaseline
    ) throws IOException {
        Path tempFile = null;
        boolean committed = false;
        FileSaveGuard guard = null;
        Throwable failure = null;
        try {
            DocumentFileMetadata checkedMetadata = unambiguousLineSeparators(
                requireCompatibleMetadata(metadata, lineCount()), 1, lineCount() - 2
            );
            SaveDisposition checkedDisposition = Objects.requireNonNull(disposition, "disposition");
            Path requestedTarget = path.toAbsolutePath();
            boolean verifyOriginal = checkBaseline
                && (checkedDisposition == SaveDisposition.REPLACE_EXISTING || fileBaseline != null);
            Path target;
            try {
                target = resolveSaveTarget(requestedTarget);
            } catch (IOException inaccessible) {
                if (verifyOriginal) throw new IOException(FileBaseline.conflictMessage(requestedTarget), inaccessible);
                throw inaccessible;
            }
            // Validate the address that will receive the staged bytes. Resolving
            // a changing opening alias again could verify one file and replace another.
            if (verifyOriginal) {
                requireUnchangedFile(target);
            }
            boolean targetExists = requireRegularSaveTargetOrMissing(target);
            Path parent = target.getParent();
            Path tempDir = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
            String fileName = target.getFileName() == null ? "tuink" : target.getFileName().toString();
            String prefix = "." + sanitizePrefix(fileName) + ".";
            if (prefix.length() < 3) {
                prefix = ".tuink.";
            }

            tempFile = createSaveTempFile(tempDir, prefix, targetExists);
            FileBaseline savedBaseline = encodeToFile(tempFile, target, checkedMetadata);
            guard = FileSaveGuard.acquire(target);
            if (verifyOriginal) {
                requireUnchangedFile(target);
            }
            requireSameSaveTarget(requestedTarget, target);
            boolean currentTargetExists = requireRegularSaveTargetOrMissing(target);
            if (checkedDisposition == SaveDisposition.CREATE_NEW && currentTargetExists) {
                throw new FileAlreadyExistsException(target.toString());
            }
            // Read and apply the current attributes inside the same publication
            // guard used by settings saves and every other TUInk document.
            FileSaveAttributes targetAttributes = currentTargetExists ? FileSaveAttributes.read(target) : null;
            if (targetAttributes != null) {
                targetAttributes.apply(tempFile);
            }
            if (verifyOriginal) {
                // Recheck after staging bytes, immediately before the filesystem commit.
                requireUnchangedFile(target);
            }
            requireSameSaveTarget(requestedTarget, target);
            requireRegularSaveTargetOrMissing(target);
            if (checkedDisposition == SaveDisposition.CREATE_NEW) {
                publishNewFile(tempFile, target);
            } else {
                try {
                    Files.move(
                        tempFile,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (AtomicMoveNotSupportedException e) {
                    if (verifyOriginal) requireUnchangedFile(target);
                    requireSameSaveTarget(requestedTarget, target);
                    requireRegularSaveTargetOrMissing(target);
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            committed = true;
            fileMetadata = checkedMetadata;
            fileBaseline = savedBaseline;
        } catch (OutOfMemoryError allocationFailure) {
            IOException saveFailure = new IOException("Not enough memory to save this document", allocationFailure);
            failure = saveFailure;
            throw saveFailure;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (guard != null) {
                try {
                    guard.close();
                } catch (IOException | OutOfMemoryError cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    // Publication already completed when there is no primary
                    // failure. Releasing the guard cannot undo a committed save.
                }
            }
            try {
                if (tempFile != null) Files.deleteIfExists(tempFile);
            } catch (IOException | OutOfMemoryError cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (!committed) throw new IOException("Cannot remove a staged save file", cleanupFailure);
                // The target is already committed. A private orphan temp file is
                // preferable to reporting a failed save and desynchronizing state.
            }
        }
    }

    private static void publishNewFile(Path tempFile, Path target) throws IOException {
        Throwable linkFailure;
        try {
            // Linking the fully staged inode publishes complete bytes without
            // overwriting a name created concurrently. Files.move cannot offer
            // that no-overwrite guarantee on Unix, even without REPLACE_EXISTING.
            Files.createLink(target, tempFile);
            return;
        } catch (FileAlreadyExistsException conflict) {
            throw conflict;
        } catch (IOException | UnsupportedOperationException failure) {
            linkFailure = failure;
        }

        byte[] buffer = new byte[8192];
        ByteBuffer bytes = ByteBuffer.wrap(buffer);
        SeekableByteChannel output;
        try {
            output = createNewSaveChannel(target);
        } catch (IOException failure) {
            failure.addSuppressed(linkFailure);
            throw failure;
        } catch (UnsupportedOperationException failure) {
            IOException unsupported = new IOException("Cannot create a new file: " + target, failure);
            unsupported.addSuppressed(linkFailure);
            throw unsupported;
        }
        try (output; InputStream input = Files.newInputStream(tempFile)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.clear().limit(read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
        } catch (IOException | OutOfMemoryError failure) {
            // Keep the new path: another writer may have replaced it after the
            // exclusive open, so deleting it by name could remove their file.
            IOException incomplete = new IOException(
                "Saving the new file failed: " + target
                    + ". The file remains at this path and may contain incomplete data.",
                failure
            );
            incomplete.addSuppressed(linkFailure);
            throw incomplete;
        }
    }

    private static SeekableByteChannel createNewSaveChannel(Path target) throws IOException {
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return Files.newByteChannel(
                target,
                options,
                PosixFilePermissions.asFileAttribute(NEW_FILE_PERMISSIONS)
            );
        } catch (UnsupportedOperationException unsupportedAttributes) {
            return Files.newByteChannel(target, options);
        }
    }

    /** Resolves parent aliases before dot segments while retaining a final symlink alias. */
    static Path resolveDocumentPath(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath();
        Path leaf = absolute.getFileName();
        if (leaf == null || leaf.toString().equals(".") || leaf.toString().equals("..")) {
            throw new IOException("Document target must be a file: " + requested);
        }
        return absolute.getParent().toRealPath().resolve(leaf);
    }

    private static Path resolveSaveTarget(Path requestedTarget) throws IOException {
        return FileSaveGuard.canonicalTarget(requestedTarget);
    }

    private static void requireSameSaveTarget(Path requestedTarget, Path target) throws IOException {
        // Staged bytes, attributes and baseline belong to this exact name.
        if (!target.equals(resolveSaveTarget(requestedTarget))) {
            throw new IOException("Save target changed outside TUInk during saving: " + requestedTarget
                + ". Choose the target again and retry Save As.");
        }
    }

    private static boolean requireRegularSaveTargetOrMissing(Path target) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            if (Files.isSymbolicLink(target)) {
                throw missing;
            }
            return false;
        }
        if (attributes.isDirectory()) {
            throw new IOException("Save target is a directory: " + target);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("Save target is not a regular file: " + target);
        }
        return true;
    }

    private void requireUnchangedFile(Path path) throws IOException {
        if (fileBaseline == null) {
            throw new IOException("Cannot verify the original file: " + path
                + ". Use Save As with a different name, or reopen the file.");
        }
        fileBaseline.requireUnchanged(path, baselineInput);
    }

    private static Path createSaveTempFile(Path tempDir, String prefix, boolean targetExists)
        throws IOException {
        if (targetExists) {
            return Files.createTempFile(tempDir, prefix, ".tmp");
        }
        try {
            return Files.createTempFile(
                tempDir,
                prefix,
                ".tmp",
                PosixFilePermissions.asFileAttribute(NEW_FILE_PERMISSIONS)
            );
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile(tempDir, prefix, ".tmp");
        }
    }

    public List<String> snapshotLines() {
        return java.util.stream.IntStream.range(0, lineCount())
            .mapToObj(this::getLine)
            .toList();
    }

    public DocumentFileMetadata fileMetadata() {
        return fileMetadata;
    }

    public FileBaseline fileBaseline() {
        return fileBaseline;
    }

    void restoreFileBaseline(FileBaseline baseline) {
        fileBaseline = baseline;
    }

    /** Restores file metadata captured together with an undo/redo content state. */
    void restoreFileMetadata(DocumentFileMetadata metadata) {
        fileMetadata = requireCompatibleMetadata(metadata, lineCount());
    }

    /** Restores the separator slice captured for an undo/redo operation. */
    private void restoreLineSeparators(int firstSeparator, List<String> restored) {
        if (restored.isEmpty()) {
            return;
        }
        List<String> current = fileMetadata.lineSeparators();
        if (current.subList(firstSeparator, firstSeparator + restored.size()).equals(restored)) {
            return;
        }
        fileMetadata = fileMetadata.replaceLineSeparators(firstSeparator, restored.size(), restored);
    }

    /** Called after a complete replacement, once its resulting logical lines are known. */
    private void normalizeLineSeparators(int firstLine, int lastLine) {
        fileMetadata = unambiguousLineSeparators(fileMetadata, firstLine, lastLine);
    }

    private DocumentFileMetadata unambiguousLineSeparators(
        DocumentFileMetadata metadata, int firstLine, int lastLine
    ) {
        int first = Math.max(1, firstLine);
        int last = Math.min(lineCount() - 2, lastLine);
        if (first > last) return metadata;
        var separators = metadata.lineSeparators().listIterator(first - 1);
        String previous = separators.next();
        DocumentFileMetadata normalized = metadata;
        for (int line = first; line <= last; line++) {
            String current = separators.next();
            if (buffer.getLine(line).isEmpty()
                && EditorState.LINE_SEPARATOR_CR.equals(previous)
                && EditorState.LINE_SEPARATOR_LF.equals(current)) {
                // CR + an empty line + LF would otherwise serialize as one CRLF.
                // Keep the preceding line's separator and disambiguate the empty line's own EOL.
                normalized = normalized.withLineSeparator(line, EditorState.LINE_SEPARATOR_CRLF);
            }
            previous = current;
        }
        return normalized;
    }

    public long revision() {
        return revision;
    }

    /**
     * Earliest logical line changed after {@code sinceRevision}.
     *
     * <p>A consumer may retain the prefix before this line and rebuild the suffix.
     * The current revision returns {@link #lineCount()} because no lines changed.
     * Unknown, future and older-than-retained revisions conservatively return zero.
     * Only the most recent 128 mutations are retained.</p>
     */
    public int firstChangedLineSince(long sinceRevision) {
        if (sinceRevision < 0 || sinceRevision > revision
            || revision - sinceRevision > CHANGE_HISTORY_CAPACITY) {
            return 0;
        }
        int firstLine = lineCount();
        for (long changedRevision = sinceRevision + 1; changedRevision <= revision; changedRevision++) {
            firstLine = Math.min(
                firstLine, firstChangedLines[(int) (changedRevision % CHANGE_HISTORY_CAPACITY)]
            );
        }
        return firstLine;
    }

    /**
     * Ordered line replacements since a revision, or {@code null} when that
     * history is unavailable or includes a whole-document replacement.
     * Coordinates address the document after the preceding replacement.
     */
    public List<LineChange> lineChangesSince(long sinceRevision) {
        if (sinceRevision < lastContentReplacementRevision || sinceRevision < 0
            || sinceRevision > revision || revision - sinceRevision > CHANGE_HISTORY_CAPACITY) {
            return null;
        }
        List<LineChange> changes = new ArrayList<>((int) (revision - sinceRevision));
        for (long changedRevision = sinceRevision + 1; changedRevision <= revision; changedRevision++) {
            changes.add(lineChanges[(int) (changedRevision % CHANGE_HISTORY_CAPACITY)]);
        }
        return List.copyOf(changes);
    }

    /** A replacement of complete logical lines, including the edited boundary lines. */
    public record LineChange(int firstLine, int removedLineCount, int addedLineCount) {
    }

    /**
     * Generation of whole-document replacements such as load and recovery restore.
     */
    public long contentEpoch() {
        return contentEpoch;
    }

    public long cacheIdentity() {
        return cacheIdentity;
    }

    public void replaceLines(List<String> lines) {
        List<String> replacement = LogicalDocumentLines.copyOfOrEmptyDocument(lines);
        DocumentFileMetadata replacementMetadata = fileMetadata;
        if (replacement.size() != lineCount()) {
            replacementMetadata = DocumentFileMetadata.canonical(fileMetadata.charset(), fileMetadata.bomKind(),
                fileMetadata.defaultLineSeparator(), replacement.size());
        }
        replaceLines(replacement, replacementMetadata);
    }

    public void replaceLines(List<String> lines, DocumentFileMetadata metadata) {
        List<String> replacement = LogicalDocumentLines.copyOfOrEmptyDocument(lines);
        DocumentFileMetadata checkedMetadata = requireCompatibleMetadata(metadata, replacement.size());
        buffer.clear();
        buffer.setLine(0, replacement.get(0));
        for (int i = 1; i < replacement.size(); i++) {
            buffer.insertLine(i, replacement.get(i));
        }
        fileMetadata = checkedMetadata;
        normalizeLineSeparators(1, lineCount() - 2);
        markContentReplaced();
    }

    private void markChanged(int firstLine) {
        markChanged(firstLine, 1, 1);
    }

    private void markChanged(int firstLine, int removedLineCount, int addedLineCount) {
        revision++;
        int slot = (int) (revision % CHANGE_HISTORY_CAPACITY);
        firstChangedLines[slot] = firstLine;
        lineChanges[slot] = new LineChange(firstLine, removedLineCount, addedLineCount);
    }

    private void markContentReplaced() {
        contentEpoch++;
        markChanged(0);
        lastContentReplacementRevision = revision;
    }

    private void insertDefaultLineSeparators(int index, int count) {
        if (count <= 0) {
            return;
        }
        fileMetadata = fileMetadata.insertLineSeparators(index, count);
    }

    private void removeLineSeparators(int index, int count) {
        if (count <= 0) {
            return;
        }
        fileMetadata = fileMetadata.removeLineSeparators(index, count);
    }

    private static DocumentFileMetadata requireCompatibleMetadata(
        DocumentFileMetadata metadata,
        int lineCount
    ) {
        DocumentFileMetadata checked = Objects.requireNonNull(metadata, "metadata");
        if (!checked.isCompatibleWithLineCount(lineCount)) {
            throw new IllegalArgumentException(
                "Line separator count " + checked.lineSeparators().size()
                    + " does not match line count " + lineCount
            );
        }
        return checked;
    }

    private DecodedContent decodeWithDetectedCharset(byte[] data, DocumentOpenMode openMode) throws IOException {
        if (openMode == DocumentOpenMode.UTF_16LE) {
            if (!hasUtf16LeBom(data)) {
                throw new IOException("Explicit UTF-16LE opening requires a UTF-16LE BOM");
            }
            return new DecodedContent(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE,
                decodeStrict(data, 2, StandardCharsets.UTF_16LE));
        }
        if (hasUtf32Bom(data, true) && isValidUtf32(data, true)) {
            // A valid UTF-32LE stream may also be valid UTF-16LE beginning with
            // NUL. Bytes alone cannot decide which interpretation was intended.
            try {
                decodeStrict(data, 2, StandardCharsets.UTF_16LE);
            } catch (CharacterCodingException invalidUtf16) {
                throw unsupportedUtf32("UTF-32LE");
            }
            throw new AmbiguousDocumentEncodingException();
        }
        if (hasUtf32Bom(data, false) && isValidUtf32(data, false)) {
            throw unsupportedUtf32("UTF-32BE");
        }
        if (hasUtf8Bom(data)) {
            return new DecodedContent(
                StandardCharsets.UTF_8,
                BomKind.UTF_8,
                decodeStrict(data, 3, StandardCharsets.UTF_8)
            );
        }
        if (hasUtf16LeBom(data)) {
            return new DecodedContent(
                StandardCharsets.UTF_16LE,
                BomKind.UTF_16_LE,
                decodeStrict(data, 2, StandardCharsets.UTF_16LE)
            );
        }
        if (hasUtf16BeBom(data)) {
            return new DecodedContent(
                StandardCharsets.UTF_16BE,
                BomKind.UTF_16_BE,
                decodeStrict(data, 2, StandardCharsets.UTF_16BE)
            );
        }
        if (isValidUtf8(data)) {
            return new DecodedContent(StandardCharsets.UTF_8, BomKind.NONE, new String(data, StandardCharsets.UTF_8));
        }
        return new DecodedContent(
            FALLBACK_CHARSET,
            BomKind.NONE,
            decodeStrict(data, 0, FALLBACK_CHARSET)
        );
    }

    private static IOException unsupportedUtf32(String encoding) {
        return new IOException("Unsupported document encoding: " + encoding
            + ". Convert the file to UTF-8 before opening.");
    }

    private static boolean hasUtf32Bom(byte[] data, boolean littleEndian) {
        return data.length >= 4 && (littleEndian
            ? data[0] == (byte) 0xFF && data[1] == (byte) 0xFE && data[2] == 0 && data[3] == 0
            : data[0] == 0 && data[1] == 0 && data[2] == (byte) 0xFE && data[3] == (byte) 0xFF);
    }

    private static boolean isValidUtf32(byte[] data, boolean littleEndian) {
        if ((data.length & 3) != 0) {
            return false;
        }
        for (int offset = 4; offset < data.length; offset += 4) {
            long scalar = 0;
            for (int index = 0; index < 4; index++) {
                scalar = (scalar << 8) | (data[offset + (littleEndian ? 3 - index : index)] & 0xFF);
            }
            if (scalar > Character.MAX_CODE_POINT || scalar >= 0xD800 && scalar <= 0xDFFF) {
                return false;
            }
        }
        return true;
    }

    private static String decodeStrict(byte[] data, int offset, Charset charset)
        throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
    }

    private static ParsedContent parseContent(String content) {
        List<String> lines = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        int lineStart = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch != '\r' && ch != '\n') {
                continue;
            }
            lines.add(content.substring(lineStart, i));
            if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                separators.add(EditorState.LINE_SEPARATOR_CRLF);
                i++;
            } else if (ch == '\r') {
                separators.add(EditorState.LINE_SEPARATOR_CR);
            } else {
                separators.add(EditorState.LINE_SEPARATOR_LF);
            }
            lineStart = i + 1;
        }
        lines.add(content.substring(lineStart));
        return new ParsedContent(List.copyOf(lines), List.copyOf(separators));
    }

    private static String detectDefaultLineSeparator(List<String> separators) {
        if (separators.isEmpty()) {
            return EditorState.LINE_SEPARATOR_LF;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String separator : separators) {
            counts.merge(separator, 1, Integer::sum);
        }
        String mostFrequent = separators.get(0);
        int highestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > highestCount) {
                mostFrequent = entry.getKey();
                highestCount = entry.getValue();
            }
        }
        return mostFrequent;
    }

    private FileBaseline encodeToFile(Path stagedFile, Path target, DocumentFileMetadata metadata)
        throws IOException {
        StagedEncoder encoded;
        try (OutputStream output = Files.newOutputStream(stagedFile)) {
            encoded = new StagedEncoder(output, metadata.charset());
            byte[] bom = metadata.bomKind().bytes();
            encoded.writeBytes(bom, bom.length);
            var separators = metadata.lineSeparators().iterator();
            for (int line = 0; line < lineCount(); line++) {
                encoded.append(getLine(line));
                if (separators.hasNext()) encoded.append(separators.next());
            }
            encoded.finish();
        }
        return new FileBaseline(target, HexFormat.of().formatHex(encoded.digest.digest()), encoded.byteSize);
    }

    /** One encoder retains shift state and split surrogate pairs across every document chunk. */
    private static final class StagedEncoder {
        private final OutputStream output;
        private final Charset charset;
        private final CharsetEncoder encoder;
        private final CharBuffer input = CharBuffer.allocate(8192);
        private final ByteBuffer bytes = ByteBuffer.allocate(8192);
        private final MessageDigest digest = FileBaseline.digest();
        private long byteSize;

        StagedEncoder(OutputStream output, Charset charset) {
            this.output = output;
            this.charset = charset;
            encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        }

        void append(String text) throws IOException {
            int offset = 0;
            while (offset < text.length()) {
                int end = offset + Math.min(input.remaining(), text.length() - offset);
                input.put(text, offset, end);
                offset = end;
                if (!input.hasRemaining()) encode(false);
            }
        }

        private void encode(boolean endOfInput) throws IOException {
            input.flip();
            CoderResult result;
            do {
                result = encoder.encode(input, bytes, endOfInput);
                if (result.isError()) {
                    try {
                        result.throwException();
                    } catch (UnmappableCharacterException failure) {
                        throw new DocumentEncodingException(charset,
                            Character.codePointAt(input, 0), failure);
                    }
                }
                drain();
            } while (result.isOverflow());
            // A trailing high surrogate remains for the next input chunk.
            input.compact();
        }

        void finish() throws IOException {
            encode(true);
            CoderResult result;
            do {
                result = encoder.flush(bytes);
                if (result.isError()) result.throwException();
                drain();
            } while (result.isOverflow());
        }

        private void drain() throws IOException {
            writeBytes(bytes.array(), bytes.position());
            bytes.clear();
        }

        private void writeBytes(byte[] data, int length) throws IOException {
            output.write(data, 0, length);
            digest.update(data, 0, length);
            byteSize += length;
        }
    }

    private static boolean isValidUtf8(byte[] data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean hasUtf8Bom(byte[] data) {
        return data.length >= 3
            && (data[0] & 0xFF) == 0xEF
            && (data[1] & 0xFF) == 0xBB
            && (data[2] & 0xFF) == 0xBF;
    }

    private static boolean hasUtf16LeBom(byte[] data) {
        return data.length >= 2
            && (data[0] & 0xFF) == 0xFF
            && (data[1] & 0xFF) == 0xFE;
    }

    private static boolean hasUtf16BeBom(byte[] data) {
        return data.length >= 2
            && (data[0] & 0xFF) == 0xFE
            && (data[1] & 0xFF) == 0xFF;
    }

    private record DecodedContent(Charset charset, BomKind bomKind, String text) {
    }

    private record ParsedContent(List<String> lines, List<String> lineSeparators) {
    }

    public record LoadMetadata(DocumentFileMetadata fileMetadata) {
        public LoadMetadata {
            fileMetadata = Objects.requireNonNull(fileMetadata, "fileMetadata");
        }

        public Charset charset() {
            return fileMetadata.charset();
        }

        public String lineSeparator() {
            return fileMetadata.defaultLineSeparator();
        }
    }

    private static boolean comesAfter(Position a, Position b) {
        return a.line() > b.line() || (a.line() == b.line() && a.col() > b.col());
    }

    private static String sanitizePrefix(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                out.append(ch);
            }
        }
        if (out.length() < 3) {
            out.append("tuink");
        }
        return out.substring(0, Math.max(3, Math.min(out.length(), 32)));
    }
}
