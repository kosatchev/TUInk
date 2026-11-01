package dev.tuink.editor.format;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import java.nio.charset.Charset;

/** Supported output formats. Optional charset providers are resolved only when selected. */
public enum DocumentEncoding {
    UTF8("UTF-8", "UTF-8", BomKind.NONE),
    UTF8_BOM("UTF-8 BOM", "UTF-8", BomKind.UTF_8),
    UTF16LE_BOM("UTF-16LE BOM", "UTF-16LE", BomKind.UTF_16_LE),
    UTF16BE_BOM("UTF-16BE BOM", "UTF-16BE", BomKind.UTF_16_BE),
    WINDOWS_1251("Windows-1251", "windows-1251", BomKind.NONE),
    CP866("CP866", "IBM866", BomKind.NONE),
    KOI8_R("KOI8-R", "KOI8-R", BomKind.NONE);

    private final String displayName;
    private final String charsetName;
    private final BomKind bomKind;

    DocumentEncoding(String displayName, String charsetName, BomKind bomKind) {
        this.displayName = displayName;
        this.charsetName = charsetName;
        this.bomKind = bomKind;
    }

    public String displayName() { return displayName; }
    public String charsetName() { return charsetName; }
    public Charset charset() { return Charset.forName(charsetName); }
    public BomKind bomKind() { return bomKind; }

    /** Null means the metadata uses a format outside the built-in presets. */
    public static DocumentEncoding from(DocumentFileMetadata metadata) {
        for (DocumentEncoding value : values()) {
            if (metadata.bomKind() == value.bomKind
                && metadata.charset().name().equalsIgnoreCase(value.charsetName)) return value;
        }
        return null;
    }
}
