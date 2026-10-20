package dev.tuink.tui.flow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Versioned separator descriptors, validated without allocating one object per document line. */
final class RecoverySeparators {
    private static final String PREFIX = "packed2:";
    private final int count;
    private final byte[] packed;
    private final String legacy;

    private RecoverySeparators(int count, byte[] packed, String legacy) {
        this.count = count;
        this.packed = packed;
        this.legacy = legacy;
    }

    int count() { return count; }

    static String encode(List<String> separators) throws IOException {
        RecoveryLimits.requireSize(separators.size(), RecoveryLimits.MAX_CODE_UNITS,
            "Recovery document has too many line separators");
        long byteCount = (separators.size() + 3L) / 4L;
        long encodedLength = PREFIX.length() + Integer.toString(separators.size()).length() + 1L
            + 4L * ((byteCount + 2) / 3);
        RecoveryLimits.requireSize(encodedLength, RecoveryLimits.MAX_METADATA_BYTES,
            "Recovery line separators exceed the metadata storage limit");
        byte[] packed = new byte[(separators.size() + 3) / 4];
        int index = 0;
        for (String separator : separators) {
            int code = switch (separator) {
                case "\n" -> 0;
                case "\r\n" -> 1;
                case "\r" -> 2;
                default -> throw new IOException("Unknown recovery line separator");
            };
            packed[index / 4] |= (byte) (code << ((index % 4) * 2));
            index++;
        }
        return PREFIX + separators.size() + ":" + Base64.getEncoder().encodeToString(packed);
    }

    static RecoverySeparators parse(String encoded, boolean legacyFormat) throws IOException {
        if (legacyFormat) return parseLegacy(encoded);
        if (!encoded.startsWith(PREFIX)) throw invalid();
        int colon = encoded.indexOf(':', PREFIX.length());
        if (colon < 0) throw invalid();
        String rawCount = encoded.substring(PREFIX.length(), colon);
        if (!rawCount.matches("0|[1-9][0-9]{0,8}")) throw invalid();
        int count;
        try {
            count = Integer.parseInt(rawCount);
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
        RecoveryLimits.requireSize(count, RecoveryLimits.MAX_CODE_UNITS, "Recovery separator count exceeds its limit");
        int bytes = (count + 3) / 4;
        String payload = encoded.substring(colon + 1);
        if (payload.length() != 4L * ((bytes + 2L) / 3L)) throw invalid();
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (packed.length != bytes || !Base64.getEncoder().encodeToString(packed).equals(payload)) throw invalid();
        for (int index = 0; index < count; index++) {
            if (((packed[index / 4] >>> ((index % 4) * 2)) & 3) == 3) throw invalid();
        }
        int remainder = count % 4;
        if (remainder != 0 && ((packed[bytes - 1] & 0xff) >>> (remainder * 2)) != 0) throw invalid();
        return new RecoverySeparators(count, packed, null);
    }

    private static RecoverySeparators parseLegacy(String encoded) throws IOException {
        int count = 0;
        for (int start = 0; start < encoded.length();) {
            int comma = encoded.indexOf(',', start);
            int end = comma < 0 ? encoded.length() : comma;
            legacySeparator(encoded, start, end);
            if (++count > RecoveryLimits.MAX_CODE_UNITS || comma == encoded.length() - 1) throw invalid();
            start = end + 1;
        }
        return new RecoverySeparators(count, null, encoded);
    }

    /** Called only after the expanded line count has passed the reader's heap admission. */
    List<String> materialize() throws IOException {
        ArrayList<String> separators = new ArrayList<>(count);
        if (packed != null) {
            for (int index = 0; index < count; index++) {
                separators.add(switch ((packed[index / 4] >>> ((index % 4) * 2)) & 3) {
                    case 0 -> "\n";
                    case 1 -> "\r\n";
                    case 2 -> "\r";
                    default -> throw invalid();
                });
            }
        } else {
            for (int start = 0; start < legacy.length();) {
                int comma = legacy.indexOf(',', start);
                int end = comma < 0 ? legacy.length() : comma;
                separators.add(legacySeparator(legacy, start, end));
                start = end + 1;
            }
        }
        return separators;
    }

    private static String legacySeparator(String text, int start, int end) throws IOException {
        int length = end - start;
        if (length == 2 && text.startsWith("LF", start)) return "\n";
        if (length == 2 && text.startsWith("CR", start)) return "\r";
        if (length == 4 && text.startsWith("CRLF", start)) return "\r\n";
        throw invalid();
    }

    private static IOException invalid() {
        return new IOException("Invalid recovery lineSeparators encoding");
    }
}
