package dev.tuink.core;

import dev.tuink.editor.storage.AmbiguousDocumentEncodingException;

import dev.tuink.editor.storage.DocumentEncodingException;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.StorageOperation;
import dev.tuink.editor.document.ImmutableText;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.charset.UnsupportedCharsetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/** Strict document serialization over caller-owned streams, without filesystem access. */
public final class DocumentCodec {
    public static final int MAX_LOAD_BYTES = 64 * 1024 * 1024;

    private DocumentCodec() {}

    /** One load's captured heap admission, shared by the storage preflight and decoder. */
    public record LoadLimits(int maximumBytes, long memoryBudget) {
        public LoadLimits {
            if (maximumBytes < 0 || maximumBytes > MAX_LOAD_BYTES || memoryBudget < 0) {
                throw new IllegalArgumentException("Invalid document read limit");
            }
        }
    }

    /** Exact bytes read or emitted, including the byte-order marker. */
    public record Fingerprint(String sha256, long byteSize) {
        public Fingerprint {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || byteSize < 0) {
                throw new IllegalArgumentException("Invalid document byte fingerprint");
            }
        }
    }

    public record DecodedContent(List<String> lines, DocumentFileMetadata metadata, Fingerprint fingerprint) {
        public DecodedContent {
            lines = List.copyOf(lines);
            requireCompatibleMetadata(metadata, lines.size());
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    public record EncodedContent(DocumentFileMetadata metadata, Fingerprint fingerprint) {
        public EncodedContent {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    public static LoadLimits currentLoadLimits() {
        Runtime runtime = Runtime.getRuntime();
        long availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        long memoryBudget = Math.max(0L, availableHeap / 2);
        return new LoadLimits((int) Math.min(MAX_LOAD_BYTES, memoryBudget / 8), memoryBudget);
    }

    /** A backend can reject an advertised size before opening the resource's stream. */
    public static void requireLoadSize(long observedSize, LoadLimits limits, String displayName) throws IOException {
        Objects.requireNonNull(limits, "limits");
        if (observedSize > limits.maximumBytes()) {
            throw new IOException("File too large to open: " + displayName
                + ". Current limit: " + limits.maximumBytes()
                + " bytes (at most 64 MiB, reduced by available memory).");
        }
    }

    /** Reads at most maximumBytes + one sentinel byte. The caller owns and closes the stream. */
    public static DecodedContent read(InputStream input, DocumentOpenMode openMode,
                                      LoadLimits limits, String displayName) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(openMode, "openMode");
        Objects.requireNonNull(limits, "limits");
        byte[] data = input.readNBytes(limits.maximumBytes() + 1);
        requireLoadSize(data.length, limits, displayName);
        DetectedContent decoded = decodeWithDetectedCharset(data, openMode);
        requireLoadFitsMemory(decoded.text(), data.length, limits.memoryBudget(), displayName);
        ParsedContent parsed = parseContent(decoded.text());
        DocumentFileMetadata metadata = new DocumentFileMetadata(decoded.charset(), decoded.bomKind(),
            detectDefaultLineSeparator(parsed.lineSeparators()), parsed.lineSeparators());
        return new DecodedContent(parsed.lines(), metadata,
            new Fingerprint(HexFormat.of().formatHex(newDigest().digest(data)), data.length));
    }

    /** One encoder spans all logical lines. The caller owns and closes the output stream. */
    public static EncodedContent write(OutputStream output, List<? extends CharSequence> lines,
                                       DocumentFileMetadata metadata) throws IOException {
        return write(output, lines, metadata, StorageOperation.untracked());
    }

    /** Streaming save preparation; cancellation leaves publication to the storage provider. */
    public static EncodedContent write(OutputStream output, List<? extends CharSequence> lines,
                                       DocumentFileMetadata metadata, StorageOperation operation) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(operation, "operation");
        operation.progress(StorageOperation.Phase.STAGING, 0L, -1L);
        DocumentFileMetadata checked = requireCompatibleMetadata(metadata, lines.size());
        // Metadata normalization can itself span millions of logical lines.
        int lastLine = lines.size() - 2;
        for (int first = 1; first <= lastLine;) {
            operation.checkCancelled();
            int last = first + Math.min(8191, lastLine - first);
            checked = normalizeLineSeparators(checked, lines.size(),
                line -> lines.get(line).length() == 0, first, last);
            first = last + 1;
        }
        StagedEncoder encoded = new StagedEncoder(output, checked.charset(), operation);
        byte[] bom = checked.bomKind().bytes();
        encoded.writeBytes(bom, bom.length);
        var separators = checked.lineSeparators().iterator();
        for (CharSequence line : lines) {
            operation.checkCancelled();
            encoded.append(line);
            if (separators.hasNext()) encoded.append(separators.next());
        }
        encoded.finish();
        return new EncodedContent(checked,
            new Fingerprint(HexFormat.of().formatHex(encoded.digest.digest()), encoded.byteSize));
    }

    private static DocumentFileMetadata requireCompatibleMetadata(DocumentFileMetadata metadata, int lineCount) {
        Objects.requireNonNull(metadata, "metadata");
        if (lineCount < 1 || !metadata.isCompatibleWithLineCount(lineCount)) {
            throw new IllegalArgumentException("File metadata does not match document lines");
        }
        return metadata;
    }

    /** Shared by bounded edit normalization and complete serialization. */
    static DocumentFileMetadata normalizeLineSeparators(DocumentFileMetadata metadata, int lineCount,
                                                         IntPredicate emptyLine, int firstLine, int lastLine) {
        int first = Math.max(1, firstLine);
        int last = Math.min(lineCount - 2, lastLine);
        if (first > last) return metadata;
        var separators = metadata.lineSeparators().listIterator(first - 1);
        String previous = separators.next();
        DocumentFileMetadata normalized = metadata;
        for (int line = first; line <= last; line++) {
            String current = separators.next();
            if (emptyLine.test(line) && "\r".equals(previous) && "\n".equals(current)) {
                // CR + an empty line + LF would otherwise serialize as one CRLF.
                // Retain the preceding EOL and disambiguate the empty line's own EOL.
                normalized = normalized.withLineSeparator(line, "\r\n");
            }
            previous = current;
        }
        return normalized;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("The Java runtime must provide SHA-256", impossible);
        }
    }

    private static void requireLoadFitsMemory(String content, int encodedBytes, long budget, String target)
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

    private static DetectedContent decodeWithDetectedCharset(byte[] data, DocumentOpenMode openMode) throws IOException {
        if (openMode == DocumentOpenMode.UTF_16LE) {
            if (!hasUtf16LeBom(data)) {
                throw new IOException("Explicit UTF-16LE opening requires a UTF-16LE BOM");
            }
            return new DetectedContent(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE,
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
            return new DetectedContent(
                StandardCharsets.UTF_8,
                BomKind.UTF_8,
                decodeStrict(data, 3, StandardCharsets.UTF_8)
            );
        }
        if (hasUtf16LeBom(data)) {
            return new DetectedContent(
                StandardCharsets.UTF_16LE,
                BomKind.UTF_16_LE,
                decodeStrict(data, 2, StandardCharsets.UTF_16LE)
            );
        }
        if (hasUtf16BeBom(data)) {
            return new DetectedContent(
                StandardCharsets.UTF_16BE,
                BomKind.UTF_16_BE,
                decodeStrict(data, 2, StandardCharsets.UTF_16BE)
            );
        }
        if (isValidUtf8(data)) {
            return new DetectedContent(StandardCharsets.UTF_8, BomKind.NONE, new String(data, StandardCharsets.UTF_8));
        }
        Charset fallback;
        try {
            fallback = Charset.forName("windows-1251");
        } catch (UnsupportedCharsetException unavailable) {
            throw new IOException("Fallback document encoding is unavailable: windows-1251", unavailable);
        }
        return new DetectedContent(fallback, BomKind.NONE, decodeStrict(data, 0, fallback));
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
                separators.add("\r\n");
                i++;
            } else if (ch == '\r') {
                separators.add("\r");
            } else {
                separators.add("\n");
            }
            lineStart = i + 1;
        }
        lines.add(content.substring(lineStart));
        return new ParsedContent(List.copyOf(lines), List.copyOf(separators));
    }

    private static String detectDefaultLineSeparator(List<String> separators) {
        if (separators.isEmpty()) {
            return "\n";
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

    /** One encoder retains shift state and split surrogate pairs across every document chunk. */
    private static final class StagedEncoder {
        private final OutputStream output;
        private final Charset charset;
        private final CharsetEncoder encoder;
        private final CharBuffer input = CharBuffer.allocate(8192);
        private final ByteBuffer bytes = ByteBuffer.allocate(8192);
        private final MessageDigest digest = newDigest();
        private final StorageOperation operation;
        private long byteSize;

        StagedEncoder(OutputStream output, Charset charset, StorageOperation operation) {
            this.output = output;
            this.charset = charset;
            this.operation = operation;
            encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        }

        void append(CharSequence text) throws IOException {
            int offset = 0;
            while (offset < text.length()) {
                operation.checkCancelled();
                int end = offset + Math.min(input.remaining(), text.length() - offset);
                int position = input.position();
                if (text instanceof ImmutableText immutable) {
                    immutable.getChars(offset, end, input.array(), position);
                } else if (text instanceof LineText immutable) {
                    immutable.getChars(offset, end, input.array(), position);
                } else if (text instanceof String string) {
                    string.getChars(offset, end, input.array(), position);
                } else {
                    for (int index = offset; index < end; index++) input.array()[position++] = text.charAt(index);
                }
                input.position(input.position() + end - offset);
                offset = end;
                if (!input.hasRemaining()) encode(false);
            }
        }

        private void encode(boolean endOfInput) throws IOException {
            operation.checkCancelled();
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
            operation.checkCancelled();
            output.write(data, 0, length);
            digest.update(data, 0, length);
            byteSize += length;
            operation.progress(StorageOperation.Phase.STAGING, byteSize, -1L);
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

    private record DetectedContent(Charset charset, BomKind bomKind, String text) {
    }

    private record ParsedContent(List<String> lines, List<String> lineSeparators) {
    }

}
