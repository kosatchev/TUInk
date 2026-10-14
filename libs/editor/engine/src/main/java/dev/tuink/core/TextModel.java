package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private final TextBuffer buffer;
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
    private EditorDocument documentOwner;

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

    public int lineCount() {
        return buffer.lineCount();
    }

    public String getLine(int line) {
        return buffer.getLine(line);
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

        StringBuilder out = new StringBuilder();
        out.append(buffer.getLine(a.line()).substring(a.col()));
        out.append('\n');
        for (int line = a.line() + 1; line < b.line(); line++) {
            out.append(buffer.getLine(line)).append('\n');
        }
        out.append(buffer.getLine(b.line()), 0, b.col());
        return out.toString();
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
        Path target = path.toRealPath();
        if (!Files.readAttributes(target, BasicFileAttributes.class).isRegularFile()) {
            throw new IOException("Not a regular file: " + path);
        }
        byte[] data = Files.readAllBytes(target);
        DecodedContent decoded = decodeWithDetectedCharset(data);
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
        DocumentFileMetadata checkedMetadata = requireCompatibleMetadata(metadata, lineCount());
        SaveDisposition checkedDisposition = Objects.requireNonNull(disposition, "disposition");
        Path requestedTarget = path.toAbsolutePath().normalize();
        if (checkBaseline && checkedDisposition == SaveDisposition.REPLACE_EXISTING) {
            requireUnchangedFile(requestedTarget);
        }
        byte[] content = encode(snapshotLines(), checkedMetadata);
        Path target = resolveSaveTarget(requestedTarget);
        FileBaseline savedBaseline = FileBaseline.fromBytes(target, content);
        boolean targetExists = Files.exists(target);
        FileSaveAttributes targetAttributes = targetExists ? FileSaveAttributes.read(target) : null;
        Path parent = target.getParent();
        Path tempDir = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
        String fileName = target.getFileName() == null ? "tuink" : target.getFileName().toString();
        String prefix = "." + sanitizePrefix(fileName) + ".";
        if (prefix.length() < 3) {
            prefix = ".tuink.";
        }

        Path tempFile = createSaveTempFile(tempDir, prefix, targetExists);
        boolean committed = false;
        try {
            Files.write(tempFile, content);
            if (targetAttributes != null) {
                targetAttributes.apply(tempFile);
            }
            if (checkBaseline && checkedDisposition == SaveDisposition.REPLACE_EXISTING) {
                // Recheck after staging bytes, immediately before the filesystem commit.
                requireUnchangedFile(requestedTarget);
            }
            if (checkedDisposition == SaveDisposition.CREATE_NEW) {
                publishNewFile(tempFile, target, content);
            } else {
                try {
                    Files.move(
                        tempFile,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            fileMetadata = checkedMetadata;
            fileBaseline = savedBaseline;
            committed = true;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupFailure) {
                if (!committed) {
                    throw cleanupFailure;
                }
                // The target is already committed. A private orphan temp file is
                // preferable to reporting a failed save and desynchronizing state.
            }
        }
    }

    private static void publishNewFile(Path tempFile, Path target, byte[] content) throws IOException {
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
        try (output) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
        } catch (IOException failure) {
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

    private static Path resolveSaveTarget(Path requestedTarget) throws IOException {
        if (requestedTarget.getFileName() == null) {
            throw new IOException("Save target must be a file: " + requestedTarget);
        }
        if (Files.isSymbolicLink(requestedTarget)) {
            return requestedTarget.toRealPath();
        }
        return requestedTarget.getParent().toRealPath().resolve(requestedTarget.getFileName());
    }

    private void requireUnchangedFile(Path path) throws IOException {
        if (fileBaseline == null) {
            throw new IOException("Cannot verify the original file: " + path
                + ". Use Save As with a different name, or reopen the file.");
        }
        fileBaseline.requireUnchanged(path);
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

    /** Restores only separators reinserted by an undo/redo operation. */
    void restoreLineSeparators(int firstSeparator, List<String> restored) {
        if (restored.isEmpty()) {
            return;
        }
        List<String> separators = new ArrayList<>(fileMetadata.lineSeparators());
        for (int index = 0; index < restored.size(); index++) {
            separators.set(firstSeparator + index, restored.get(index));
        }
        fileMetadata = fileMetadata.withLineSeparators(separators);
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
            List<String> separators = new ArrayList<>(Math.max(0, replacement.size() - 1));
            for (int i = 1; i < replacement.size(); i++) {
                separators.add(fileMetadata.defaultLineSeparator());
            }
            replacementMetadata = fileMetadata.withLineSeparators(separators);
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
        List<String> separators = new ArrayList<>(fileMetadata.lineSeparators());
        separators.addAll(index, Collections.nCopies(count, fileMetadata.defaultLineSeparator()));
        fileMetadata = fileMetadata.withLineSeparators(separators);
    }

    private void removeLineSeparators(int index, int count) {
        if (count <= 0) {
            return;
        }
        List<String> separators = new ArrayList<>(fileMetadata.lineSeparators());
        separators.subList(index, index + count).clear();
        fileMetadata = fileMetadata.withLineSeparators(separators);
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

    private DecodedContent decodeWithDetectedCharset(byte[] data) throws CharacterCodingException {
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

    private static byte[] encode(List<String> lines, DocumentFileMetadata metadata)
        throws CharacterCodingException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            content.append(lines.get(i));
            if (i < metadata.lineSeparators().size()) {
                content.append(metadata.lineSeparators().get(i));
            }
        }
        CharsetEncoder encoder = metadata.charset().newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encodedBuffer = encoder.encode(CharBuffer.wrap(content));
        byte[] encodedText = new byte[encodedBuffer.remaining()];
        encodedBuffer.get(encodedText);
        byte[] bom = metadata.bomKind().bytes();
        if (bom.length == 0) {
            return encodedText;
        }
        byte[] encoded = new byte[bom.length + encodedText.length];
        System.arraycopy(bom, 0, encoded, 0, bom.length);
        System.arraycopy(encodedText, 0, encoded, bom.length, encodedText.length);
        return encoded;
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
