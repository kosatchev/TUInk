package dev.tuink.core;

import dev.tuink.editor.document.ImmutableText;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.unicode.GraphemeText;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Editing operations and immutable resource handoffs over a {@link TextBuffer}.
 */
public class TextModel {
    private static final AtomicLong NEXT_CACHE_ID = new AtomicLong();
    private static final int CHANGE_HISTORY_CAPACITY = 128;
    private TextBuffer buffer;
    private final int[] firstChangedLines = new int[CHANGE_HISTORY_CAPACITY];
    private final LineChange[] lineChanges = new LineChange[CHANGE_HISTORY_CAPACITY];
    private long revision = 0L;
    private long contentEpoch = 0L;
    private long lastContentReplacementRevision;
    private final long cacheIdentity = NEXT_CACHE_ID.incrementAndGet();
    private DocumentFileMetadata fileMetadata;
    private ResourceVersion fileBaseline;
    private EditorDocument documentOwner;
    private LocalEdit localEdit;
    private GraphemeText cachedGraphemes;

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
        private final ResourceVersion baselineBefore = fileBaseline;
        private final long revisionBefore = revision;
        private final long epochBefore = contentEpoch;
        private final long replacementRevisionBefore = lastContentReplacementRevision;
        private final int[] changedBefore = firstChangedLines.clone();
        private final LineChange[] changesBefore = lineChanges.clone();
        private TextBuffer publication;

        void preparePublication() {
            // A whole-document replacement may already own an independent buffer.
            // Local line commands instead publish the overlay into prepared storage.
            publication = buffer == staged ? staged.preparePublication() : buffer;
        }

        void publish() {
            staged.publishTo(publication);
            buffer = publication;
            localEdit = null;
        }

        void abort() {
            buffer = source;
            fileMetadata = metadataBefore;
            fileBaseline = baselineBefore;
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

    private GraphemeText graphemes(LineText text) {
        if (cachedGraphemes != null && cachedGraphemes.sequence() == text) return cachedGraphemes;
        LineText.Edit edit = text.edit();
        if (cachedGraphemes != null && cachedGraphemes.sequence() instanceof LineText previous
            && edit != null && edit.sourceIdentity() == previous.identity()) {
            cachedGraphemes = cachedGraphemes.updated(text, edit.start(), edit.removedLength(), edit.insertedLength());
        } else cachedGraphemes = GraphemeText.of(text);
        return cachedGraphemes;
    }

    public Position insertChar(Position position, char ch) {
        if (ch == '\n' || ch == '\r') {
            throw new IllegalArgumentException("Use insertNewline or insertText for line separators");
        }
        if (Character.isSurrogate(ch)) {
            throw new IllegalArgumentException("A standalone UTF-16 surrogate is not valid text");
        }
        Position at = requireCodePointBoundary(position);
        LineText line = buffer.lineText(at.line());
        int col = at.col();
        buffer.setLineText(at.line(), line.replace(col, col, String.valueOf(ch)));
        markChanged(at.line());
        return new Position(at.line(), col + 1);
    }

    public Position insertNewline(Position position) {
        Position at = requireCodePointBoundary(position);
        LineText line = buffer.lineText(at.line());
        int col = at.col();
        buffer.setLineText(at.line(), line.subSequence(0, col));
        buffer.insertLineText(at.line() + 1, line.subSequence(col, line.length()));
        insertDefaultLineSeparators(at.line(), 1);
        markChanged(at.line(), 1, 2);
        return new Position(at.line() + 1, 0);
    }

    public Position deleteBackward(Position position) {
        if (position.col() > 0) {
            LineText line = buffer.lineText(position.line());
            int col = Math.min(position.col(), line.length());
            GraphemeText graphemes = graphemes(line);
            int end = graphemes.ceilBoundary(col);
            int previous = graphemes.previousBoundary(end);
            buffer.setLineText(position.line(), line.replace(previous, end, ""));
            markChanged(position.line());
            return new Position(position.line(), previous);
        }

        if (position.line() == 0) {
            return position;
        }

        LineText current = buffer.lineText(position.line());
        buffer.removeLines(position.line(), position.line() + 1);
        int prevLineIndex = position.line() - 1;
        LineText prev = buffer.lineText(prevLineIndex);
        buffer.setLineText(prevLineIndex, prev.replace(prev.length(), prev.length(), current));
        removeLineSeparators(prevLineIndex, 1);
        markChanged(prevLineIndex, 2, 1);
        return new Position(prevLineIndex, prev.length());
    }

    public Position deleteForward(Position position) {
        LineText line = buffer.lineText(position.line());
        int col = Math.min(position.col(), line.length());

        if (col < line.length()) {
            GraphemeText graphemes = graphemes(line);
            int start = graphemes.floorBoundary(col);
            int next = graphemes.nextBoundary(start);
            buffer.setLineText(position.line(), line.replace(start, next, ""));
            markChanged(position.line());
            return new Position(position.line(), start);
        }

        if (position.line() >= buffer.lineCount() - 1) {
            return new Position(position.line(), col);
        }

        LineText next = buffer.lineText(position.line() + 1);
        buffer.removeLines(position.line() + 1, position.line() + 2);
        buffer.setLineText(position.line(), line.replace(line.length(), line.length(), next));
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
            LineText line = buffer.lineText(a.line());
            return line.substring(a.col(), b.col());
        }

        long length = textRangeLengthOrdered(a, b);
        if (length > Integer.MAX_VALUE - 8L) {
            throw new OutOfMemoryError("Text range exceeds the maximum String size");
        }
        StringBuilder out = new StringBuilder((int) length);
        LineText first = buffer.lineText(a.line());
        out.append(first, a.col(), first.length());
        out.append('\n');
        for (int line = a.line() + 1; line < b.line(); line++) {
            out.append(buffer.lineText(line)).append('\n');
        }
        out.append(buffer.lineText(b.line()), 0, b.col());
        return out.toString();
    }

    long textRangeLength(Position start, Position end) {
        Position a = requireCodePointBoundary(start);
        Position b = requireCodePointBoundary(end);
        return comesAfter(a, b) ? textRangeLengthOrdered(b, a) : textRangeLengthOrdered(a, b);
    }

    private long textRangeLengthOrdered(Position start, Position end) {
        if (start.line() == end.line()) return end.col() - (long) start.col();
        long length = buffer.lineText(start.line()).length() - (long) start.col() + 1 + end.col();
        for (int line = start.line() + 1; line < end.line(); line++) {
            length += buffer.lineText(line).length() + 1L;
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
            LineText line = buffer.lineText(a.line());
            buffer.setLineText(a.line(), line.replace(a.col(), b.col(), ""));
            markChanged(a.line());
            return a;
        }

        LineText head = buffer.lineText(a.line()).subSequence(0, a.col());
        LineText endLine = buffer.lineText(b.line());
        LineText tail = endLine.subSequence(b.col(), endLine.length());
        buffer.setLineText(a.line(), head.replace(head.length(), head.length(), tail));
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
        LineText line = buffer.lineText(at.line());
        int col = Math.min(at.col(), line.length());
        if (parts.length == 1) {
            buffer.setLineText(at.line(), line.replace(col, col, parts[0]));
            markChanged(at.line());
            return new Position(at.line(), col + parts[0].length());
        }

        LineText left = line.subSequence(0, col);
        LineText right = line.subSequence(col, line.length());
        buffer.setLineText(at.line(), left.replace(left.length(), left.length(), parts[0]));
        String last = parts[parts.length - 1];
        List<LineText> inserted = new ArrayList<>(parts.length - 1);
        for (int index = 1; index < parts.length - 1; index++) inserted.add(LineText.of(parts[index]));
        inserted.add(right.replace(0, 0, last));
        buffer.insertLineTexts(at.line() + 1, inserted);
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
            deletedUnits = lineLength(start.line()) - (long) start.col() + 1 + end.col();
            for (int line = start.line() + 1; line < end.line(); line++) {
                deletedUnits = DocumentEditBudget.add(deletedUnits, lineLength(line) + 1L);
            }
        }
        long retainedUnits = start.col() + (long) lineLength(end.line()) - end.col();
        long insertedUnits = replacement.text().length();
        long copiedUnits = DocumentEditBudget.add(deletedUnits,
            DocumentEditBudget.add(4L * LineText.BLOCK_SIZE, insertedUnits));
        boolean sharesBlocks = buffer instanceof SimpleLineBuffer simple && simple.supportsPreparedPublication()
            || localEdit != null && localEdit.staged.supportsLocalPublication();
        if (!sharesBlocks) copiedUnits = DocumentEditBudget.add(copiedUnits, retainedUnits);
        long changedLines = replacement.lineBreaks() + 1L;
        long resultLines = lineCount() - (end.line() - (long) start.line()) + replacement.lineBreaks();
        long longestLine = replacement.lineBreaks() == 0
            ? DocumentEditBudget.add(retainedUnits, insertedUnits)
            : Math.max(DocumentEditBudget.add(start.col(), replacement.firstEnd()),
                Math.max(replacement.longestInteriorLine(),
                    insertedUnits - replacement.lastStart() + lineLength(end.line()) - end.col()));
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
            LineText source = buffer.lineText(a.line());
            LineText updated = source.replace(a.col(), b.col(), inserted);
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
        nextBuffer.setLineText(0, buffer.lineText(0));
        for (int line = 1; line < lineCount(); line++) {
            nextBuffer.insertLineText(line, buffer.lineText(line));
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
            buffer.setLineText(prepared.change().firstLine(), prepared.line());
        } else {
            buffer = prepared.buffer();
        }
        fileMetadata = prepared.metadata();
        revision++;
        int slot = (int) (revision % CHANGE_HISTORY_CAPACITY);
        firstChangedLines[slot] = prepared.change().firstLine();
        lineChanges[slot] = prepared.change();
    }

    record PreparedReplacement(TextBuffer source, long revision, TextBuffer buffer, LineText line,
        DocumentFileMetadata metadata, ReplacementDelta delta, LineChange change) {
        LineText resultingLine(int index) {
            return buffer != null ? buffer.lineText(index)
                : index == change.firstLine() ? line : source.lineText(index);
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
            LineText source = buffer.lineText(a.line());
            buffer.setLineText(a.line(), source.replace(a.col(), b.col(), inserted));
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
        nextBuffer.setLineText(0, buffer.lineText(0));
        for (int line = 1; line < lineCount(); line++) {
            nextBuffer.insertLineText(line, buffer.lineText(line));
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
        int firstLine = replacements.get(0).start().line();
        int span = replacements.get(replacements.size() - 1).start().line() - firstLine + 1;
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
        int col = Math.max(0, Math.min(requested.col(), buffer.lineText(line).length()));
        return new Position(line, col);
    }

    private Position requireCodePointBoundary(Position requested) {
        Position clamped = clamp(Objects.requireNonNull(requested, "position"));
        LineText line = buffer.lineText(clamped.line());
        int offset = clamped.col();
        if (offset > 0
            && offset < line.length()
            && Character.isHighSurrogate(line.charAt(offset - 1))
            && Character.isLowSurrogate(line.charAt(offset))) {
            throw new IllegalArgumentException("Position splits a UTF-16 surrogate pair");
        }
        return clamped;
    }

    public DocumentLoad load(DocumentResource resource) throws IOException {
        return load(resource, DocumentOpenMode.DETECT);
    }

    public DocumentLoad load(DocumentResource resource, DocumentOpenMode openMode) throws IOException {
        DocumentLoad prepared = prepareLoad(resource, openMode);
        applyPreparedLoad(prepared);
        return prepared;
    }

    /** Reads independent content through the explicitly supplied resource provider. */
    public static DocumentLoad prepareLoad(DocumentResource resource, DocumentOpenMode openMode) throws IOException {
        return prepareLoad(resource, openMode, false);
    }

    public static DocumentLoad prepareLoad(DocumentResource resource, DocumentOpenMode openMode,
                                            boolean allowMissing) throws IOException {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(openMode, "openMode");
        return resource.storage().load(resource, openMode, allowMissing);
    }

    /** Owner-thread publication of a completed read; never opens a resource. */
    public void applyPreparedLoad(DocumentLoad prepared) {
        Objects.requireNonNull(prepared, "prepared");
        replaceLines(prepared.lines(), prepared.metadata());
        fileBaseline = prepared.baseline();
    }

    public void save(DocumentResource resource) throws IOException {
        save(resource, fileMetadata);
    }

    public void save(DocumentResource resource, Charset charset) throws IOException {
        save(resource, charset, EditorState.LINE_SEPARATOR_LF);
    }

    public void save(DocumentResource resource, Charset charset, String lineSeparator) throws IOException {
        save(resource, fileMetadata.canonicalized(charset, lineSeparator, lineCount()));
    }

    public void save(DocumentResource resource, DocumentFileMetadata metadata) throws IOException {
        save(resource, metadata, SaveDisposition.REPLACE_EXISTING);
    }

    /** Explicit export/overwrite; the inspected target is not treated as this model's owned version. */
    public void save(DocumentResource resource, DocumentFileMetadata metadata, SaveDisposition disposition)
        throws IOException {
        saveThroughStorage(resource, metadata, disposition, false);
    }

    /** Saves an owned resource only while its loaded/saved version still matches. */
    public void saveIfUnchanged(DocumentResource resource, DocumentFileMetadata metadata, SaveDisposition disposition)
        throws IOException {
        saveThroughStorage(resource, metadata, disposition, true);
    }

    private void saveThroughStorage(DocumentResource resource, DocumentFileMetadata metadata,
                                    SaveDisposition disposition, boolean checkBaseline) throws IOException {
        try {
            DocumentSave request = prepareSave(resource, metadata, disposition);
            SaveAuthorization authorization = checkBaseline ? new SaveAuthorization.Current()
                : new SaveAuthorization.Approved(resource.storage().inspect(resource, ResourceBinding.unbound()));
            completeSave(resource.storage().write(request, authorization));
        } catch (OutOfMemoryError | DocumentEditLimitException exhausted) {
            throw new IOException("Not enough memory to save this document", exhausted);
        }
    }

    /** Captures immutable save input; storage workers never access the live model. */
    public DocumentSave prepareSave(DocumentResource resource, DocumentFileMetadata metadata,
                                     SaveDisposition disposition) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(disposition, "disposition");
        DocumentFileMetadata checked = requireCompatibleMetadata(metadata, lineCount());
        DocumentEditBudget.requireTemporaryReferences(lineCount());
        List<ImmutableText> lines = new ArrayList<>(lineCount());
        for (int line = 0; line < lineCount(); line++) lines.add(lineText(line).immutableText());
        return new DocumentSave(resource, lines, checked, fileBaseline, disposition);
    }

    /** Adopt the committed format/version; content and its revision remain unchanged. */
    public void completeSave(SavedDocument saved) {
        Objects.requireNonNull(saved, "saved");
        fileMetadata = requireCompatibleMetadata(saved.metadata(), lineCount());
        fileBaseline = saved.baseline();
    }

    public List<String> snapshotLines() {
        return java.util.stream.IntStream.range(0, lineCount())
            .mapToObj(this::getLine)
            .toList();
    }

    public DocumentFileMetadata fileMetadata() {
        return fileMetadata;
    }

    public ResourceVersion fileBaseline() {
        return fileBaseline;
    }

    void restoreFileBaseline(ResourceVersion baseline) {
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
        return DocumentCodec.normalizeLineSeparators(metadata, lineCount(),
            line -> buffer.lineText(line).isEmpty(), firstLine, lastLine);
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
        TextBuffer nextBuffer = Objects.requireNonNull(buffer.createEmptySibling(), "buffer.createEmptySibling()");
        if (nextBuffer == buffer || nextBuffer.lineCount() != 1 || !nextBuffer.getLine(0).isEmpty()) {
            throw new IllegalArgumentException("createEmptySibling() must return an independent empty buffer");
        }
        nextBuffer.setLine(0, replacement.get(0));
        for (int i = 1; i < replacement.size(); i++) {
            nextBuffer.insertLine(i, replacement.get(i));
        }
        TextModel staged = new TextModel(nextBuffer, checkedMetadata);
        staged.normalizeLineSeparators(1, replacement.size() - 2);
        LineChange change = new LineChange(0, 1, 1);

        buffer = nextBuffer;
        fileMetadata = staged.fileMetadata;
        contentEpoch++;
        revision++;
        int slot = (int) (revision % CHANGE_HISTORY_CAPACITY);
        firstChangedLines[slot] = 0;
        lineChanges[slot] = change;
        lastContentReplacementRevision = revision;
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

    private static boolean comesAfter(Position a, Position b) {
        return a.line() > b.line() || (a.line() == b.line() && a.col() > b.col());
    }
}
