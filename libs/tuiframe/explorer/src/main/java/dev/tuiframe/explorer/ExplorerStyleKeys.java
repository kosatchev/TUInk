package dev.tuiframe.explorer;

import dev.tuiframe.widget.WidgetStyleRole;

/** Semantic roles supplied by the containing application theme. */
public final class ExplorerStyleKeys {
    private ExplorerStyleKeys() {}
    public static final WidgetStyleRole DROP_TARGET = new WidgetStyleRole("explorer.drop.target");
    public static final WidgetStyleRole DRAG_LABEL = new WidgetStyleRole("explorer.drag.label");
    public static final WidgetStyleRole WARNING = new WidgetStyleRole("explorer.notice.warning");
    public static final WidgetStyleRole ERROR = new WidgetStyleRole("explorer.notice.error");
}
