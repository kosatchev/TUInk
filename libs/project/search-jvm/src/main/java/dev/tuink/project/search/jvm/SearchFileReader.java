package dev.tuink.project.search.jvm;

import dev.tuink.core.DocumentCodec;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.storage.jvm.FileBaseline;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** A regular file is inspected and decoded in bounded buffers, never loaded as an editor. */
final class SearchFileReader {
    private SearchFileReader() {}

    static ProjectSearch.ReadVersion read(Path path, DocumentOpenMode requested, long maximumBytes,
                                         ProjectSearch.Control control, ProjectSearch.Lines lines) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Not a regular file");
        if (before.size() > maximumBytes) throw new IOException("File exceeds size limit");
        Probe probe = null;
        DocumentOpenMode mode = requested;
        if (mode == DocumentOpenMode.DETECT) {
            probe = new Probe();
            try (BoundedInput input = open(path, maximumBytes, control)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) probe.feed(buffer, count);
                probe.finish(input.digest());
            }
            mode = DocumentCodec.detectOpenMode(probe.prefix(), probe);
        }
        String fingerprint;
        long byteSize;
        try (BoundedInput input = open(path, maximumBytes, control);
             PushbackInputStream decodedInput = new PushbackInputStream(input, 4)) {
            byte[] prefix = decodedInput.readNBytes(4);
            int skip = bomLength(prefix, mode);
            if (prefix.length > skip) decodedInput.unread(prefix, skip, prefix.length - skip);
            var decoder = charset(mode).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            InputStreamReader reader = new InputStreamReader(decodedInput, decoder);
            char[] buffer = new char[16384];
            StringBuilder line = new StringBuilder();
            int lineNumber = 0;
            boolean afterCr = false;
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                control.checkpoint();
                for (int i = 0; i < count; i++) {
                    char value = buffer[i];
                    if (afterCr && value == '\n') { afterCr = false; continue; }
                    afterCr = false;
                    if (value == '\0') throw new IOException("Binary content (NUL character)");
                    if (value == '\r' || value == '\n') {
                        lines.accept(lineNumber++, line);
                        line.setLength(0);
                        afterCr = value == '\r';
                    } else {
                        if (line.length() >= ProjectSearch.MAX_LINE_LENGTH)
                            throw new IOException("Logical line exceeds 1 Mi UTF-16 units");
                        line.append(value);
                    }
                }
            }
            lines.accept(lineNumber, line);
            fingerprint = input.digest();
            byteSize = input.count;
        }
        control.checkpoint();
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || !Objects.equals(before.fileKey(), after.fileKey())
            || probe != null && !probe.fingerprint.equals(fingerprint))
            throw new IOException("File changed during search; refresh results");
        return new ProjectSearch.ReadVersion(new FileBaseline(path.toAbsolutePath().normalize(), fingerprint, byteSize), mode);
    }

    private static Charset charset(DocumentOpenMode mode) {
        return Charset.forName(switch (mode) {
            case UTF_8 -> "UTF-8"; case UTF_16LE -> "UTF-16LE"; case UTF_16BE -> "UTF-16BE";
            case WINDOWS_1251 -> "windows-1251"; case CP866 -> "IBM866"; case KOI8_R -> "KOI8-R";
            case DETECT -> throw new IllegalArgumentException("Resolve automatic encoding first");
        });
    }
    private static int bomLength(byte[] prefix, DocumentOpenMode mode) throws IOException {
        if (mode == DocumentOpenMode.UTF_8)
            return prefix.length >= 3 && (prefix[0] & 255) == 239 && (prefix[1] & 255) == 187
                && (prefix[2] & 255) == 191 ? 3 : 0;
        if (mode == DocumentOpenMode.UTF_16LE || mode == DocumentOpenMode.UTF_16BE) {
            int first = mode == DocumentOpenMode.UTF_16LE ? 255 : 254;
            if (prefix.length < 2 || (prefix[0] & 255) != first || (prefix[1] & 255) != 509 - first)
                throw new IOException("UTF-16 opening requires the corresponding BOM");
            return 2;
        }
        return 0;
    }
    private static BoundedInput open(Path path, long maximum, ProjectSearch.Control control) throws IOException {
        return new BoundedInput(Channels.newInputStream(Files.newByteChannel(path,
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))), maximum, control);
    }
    private static final class BoundedInput extends FilterInputStream {
        final long maximum; final ProjectSearch.Control control; final MessageDigest digest; long count;
        BoundedInput(InputStream input, long maximum, ProjectSearch.Control control) {
            super(input); this.maximum = maximum; this.control = control;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        public int read() throws IOException {
            byte[] one = new byte[1]; int result = read(one, 0, 1); return result < 0 ? -1 : one[0] & 255;
        }
        public int read(byte[] buffer, int offset, int length) throws IOException {
            control.checkpoint();
            int read = in.read(buffer, offset, (int) Math.min(length, maximum - count + 1));
            if (read > 0) {
                count += read;
                if (count > maximum) throw new IOException("File exceeds size limit");
                digest.update(buffer, offset, read);
            }
            return read;
        }
        String digest() { return HexFormat.of().formatHex(digest.digest()); }
    }

    /** Full-file validation, including sequences split between input buffers. */
    private static final class Probe implements DocumentCodec.EncodingProbe {
        final byte[] first = new byte[4]; int firstCount; long bytes;
        int required, codePoint, minimum; boolean utf8 = true;
        int word; boolean utf32Le = true, utf32Be = true;
        int lowByte = -1; boolean highSurrogate; boolean utf16Le = true;
        String fingerprint;
        void feed(byte[] data, int count) {
            for (int i = 0; i < count; i++) {
                int value = data[i] & 255;
                if (firstCount < 4) first[firstCount++] = data[i];
                if (required == 0) {
                    if (value < 128) { }
                    else if (value >= 194 && value <= 223) { required = 1; codePoint = value & 31; minimum = 128; }
                    else if (value >= 224 && value <= 239) { required = 2; codePoint = value & 15; minimum = 2048; }
                    else if (value >= 240 && value <= 244) { required = 3; codePoint = value & 7; minimum = 65536; }
                    else utf8 = false;
                } else if ((value & 192) != 128) { utf8 = false; required = 0; }
                else {
                    codePoint = (codePoint << 6) | (value & 63);
                    if (--required == 0 && (codePoint < minimum || !scalar(codePoint))) utf8 = false;
                }
                word = (word << 8) | value;
                if ((bytes & 3) == 3) {
                    if (!scalar(word)) utf32Be = false;
                    if (!scalar(Integer.reverseBytes(word))) utf32Le = false;
                    word = 0;
                }
                // Explicit UTF-16LE validation starts after its two-byte BOM.
                if (bytes >= 2) {
                    if (lowByte < 0) lowByte = value;
                    else {
                        int character = lowByte | value << 8; lowByte = -1;
                        if (highSurrogate) { if (character < 0xDC00 || character > 0xDFFF) utf16Le = false; highSurrogate = false; }
                        else if (character >= 0xD800 && character <= 0xDBFF) highSurrogate = true;
                        else if (character >= 0xDC00 && character <= 0xDFFF) utf16Le = false;
                    }
                }
                bytes++;
            }
        }
        void finish(String fingerprint) { this.fingerprint = fingerprint; }
        byte[] prefix() { return Arrays.copyOf(first, firstCount); }
        public boolean validUtf8() { return utf8 && required == 0; }
        public boolean validUtf32(boolean little) {
            // The first word is the UTF-32 BOM and must not be interpreted as text.
            // U+FEFF is a valid scalar in either endian's corresponding BOM.
            return bytes % 4 == 0 && bytes >= 4 && (little ? utf32Le : utf32Be);
        }
        public boolean validUtf16Le() { return utf16Le && lowByte < 0 && !highSurrogate; }
        private static boolean scalar(int value) { return value >= 0 && value <= 0x10FFFF && (value < 0xD800 || value > 0xDFFF); }
    }
}
