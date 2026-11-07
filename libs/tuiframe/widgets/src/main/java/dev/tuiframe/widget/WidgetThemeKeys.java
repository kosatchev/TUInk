package dev.tuiframe.widget;

/** Standard style roles used by the reusable widgets. */
public final class WidgetThemeKeys {
    public static final WidgetStyleRole NOTICE_SUCCESS = new WidgetStyleRole("widget.notice.success");
    public static final WidgetStyleRole NOTICE_INFO = new WidgetStyleRole("widget.notice.info");
    public static final WidgetStyleRole NOTICE_WARNING = new WidgetStyleRole("widget.notice.warning");
    public static final WidgetStyleRole NOTICE_ERROR = new WidgetStyleRole("widget.notice.error");
    public static final WidgetStyleRole BUTTON = new WidgetStyleRole("widget.button");
    public static final WidgetStyleRole BUTTON_FOCUSED = new WidgetStyleRole("widget.button.focused");
    public static final WidgetStyleRole BUTTON_DISABLED = new WidgetStyleRole("widget.button.disabled");
    public static final WidgetStyleRole TEXT_FIELD = new WidgetStyleRole("widget.text-field");
    public static final WidgetStyleRole TEXT_FIELD_FOCUSED = new WidgetStyleRole("widget.text-field.focused");
    public static final WidgetStyleRole TEXT_FIELD_DISABLED = new WidgetStyleRole("widget.text-field.disabled");
    public static final WidgetStyleRole TEXT_FIELD_SELECTION = new WidgetStyleRole("widget.text-field.selection");
    public static final WidgetStyleRole MODAL_BODY = new WidgetStyleRole("widget.modal.body");
    public static final WidgetStyleRole MODAL_BORDER = new WidgetStyleRole("widget.modal.border");
    public static final WidgetStyleRole MODAL_TITLE = new WidgetStyleRole("widget.modal.title");
    public static final WidgetStyleRole MODAL_ERROR = new WidgetStyleRole("widget.modal.error");
    public static final WidgetStyleRole SPLITTER = new WidgetStyleRole("widget.splitter");
    public static final WidgetStyleRole SPLITTER_FOCUSED = new WidgetStyleRole("widget.splitter.focused");
    public static final WidgetStyleRole SPLITTER_DISABLED = new WidgetStyleRole("widget.splitter.disabled");
    /** Additive bold/underline emphasis for branch labels; row colors stay inherited. */
    public static final WidgetStyleRole TREE_BRANCH = new WidgetStyleRole("widget.tree.branch");
    public static final WidgetStyleRole LIST_ITEM = new WidgetStyleRole("widget.list.item");
    public static final WidgetStyleRole LIST_ITEM_SELECTED = new WidgetStyleRole(
        "widget.list.item.selected"
    );
    public static final WidgetStyleRole LIST_ITEM_SELECTED_FOCUSED = new WidgetStyleRole(
        "widget.list.item.selected-focused"
    );
    public static final WidgetStyleRole LIST_ITEM_DISABLED = new WidgetStyleRole(
        "widget.list.item.disabled"
    );
    public static final WidgetStyleRole TAB_BAR = new WidgetStyleRole("widget.tab-bar");
    public static final WidgetStyleRole TAB_ITEM = new WidgetStyleRole("widget.tab.item");
    public static final WidgetStyleRole TAB_ITEM_ACTIVE = new WidgetStyleRole(
        "widget.tab.item.active"
    );
    public static final WidgetStyleRole TAB_ITEM_DISABLED = new WidgetStyleRole(
        "widget.tab.item.disabled"
    );
    public static final WidgetStyleRole MENU_BAR = new WidgetStyleRole("widget.menu-bar");
    public static final WidgetStyleRole MENU_BAR_ACTIVE = new WidgetStyleRole(
        "widget.menu-bar.active"
    );
    public static final WidgetStyleRole MENU_BAR_DISABLED = new WidgetStyleRole(
        "widget.menu-bar.disabled"
    );
    public static final WidgetStyleRole MENU_POPUP = new WidgetStyleRole("widget.menu.popup");
    public static final WidgetStyleRole MENU_ITEM = new WidgetStyleRole("widget.menu.item");
    public static final WidgetStyleRole MENU_ITEM_SELECTED = new WidgetStyleRole(
        "widget.menu.item.selected"
    );
    public static final WidgetStyleRole MENU_ITEM_DISABLED = new WidgetStyleRole(
        "widget.menu.item.disabled"
    );

    private WidgetThemeKeys() {
    }
}
