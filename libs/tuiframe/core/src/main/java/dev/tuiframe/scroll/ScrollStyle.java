package dev.tuiframe.scroll;

import dev.tuiframe.style.CellStyle;
import java.util.Objects;

/** Scrollbar colors inherited from the containing surface and its border. */
public record ScrollStyle(CellStyle track, CellStyle thumb) {
    public ScrollStyle {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(thumb, "thumb");
    }

    public static ScrollStyle fromParent(CellStyle body, CellStyle border) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(border, "border");
        CellStyle inherited = new CellStyle(border.fg(), body.bg(), border.bold(), border.underline(), body.inverse());
        return new ScrollStyle(inherited, inherited);
    }
}
