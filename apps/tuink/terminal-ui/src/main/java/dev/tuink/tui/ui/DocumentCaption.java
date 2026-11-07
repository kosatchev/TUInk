package dev.tuink.tui.ui;

import dev.tuiframe.explorer.ui.PathPresentation;

import dev.tuiframe.cell.CellText;
import java.nio.file.Path;

/** Application-owned file naming; terminal fitting itself remains in tuiframe. */
public final class DocumentCaption {
    private DocumentCaption() {}

    public static String name(Path file) {
        if (file == null) return TabBarView.NO_NAME_LABEL;
        return PathPresentation.name(file);
    }

    public static int extensionCells(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? 0 : CellText.width(name.substring(dot));
    }

    public static String fitPath(Path file, int cells) {
        String name = name(file);
        if (file == null || CellText.width(name) + 2 >= cells) {
            return PathPresentation.fit(name, cells, extensionCells(name));
        }
        String path = PathPresentation.full(file);
        int tail = CellText.width(file.getFileSystem().getSeparator() + name);
        return PathPresentation.fit(path, cells, tail);
    }
}
