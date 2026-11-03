package dev.tuink.editor.storage;

/** An explicit choice applies to this open request only, never to later files. */
public enum DocumentOpenMode {
    DETECT,
    UTF_8,
    UTF_16LE,
    UTF_16BE,
    WINDOWS_1251,
    CP866,
    KOI8_R;

    /** Decoder for bytes actually loaded or saved in a supported format. */
    public static DocumentOpenMode forMetadata(DocumentFileMetadata metadata) {
        // Receipt publication must not allocate enum arrays or resolve charset providers.
        return switch (metadata.charset().name()) {
            case "UTF-8" -> UTF_8;
            case "UTF-16LE" -> metadata.bomKind() == BomKind.UTF_16_LE ? UTF_16LE : DETECT;
            case "UTF-16BE" -> metadata.bomKind() == BomKind.UTF_16_BE ? UTF_16BE : DETECT;
            case "windows-1251" -> WINDOWS_1251;
            case "IBM866" -> CP866;
            case "KOI8-R" -> KOI8_R;
            default -> DETECT;
        };
    }
}
