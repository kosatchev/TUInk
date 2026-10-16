package dev.tuink.tui;

import dev.tuink.core.DocumentEncodingException;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SearchMatcher;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSessions;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.AppSettingsStore;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.input.EditorInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuiframe.runtime.TuiApplicationRunner;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScreenCursor;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.style.CellColor;
import dev.tuink.tui.style.UiStyles;
import dev.tuiframe.jline.TerminalSession;
import dev.tuiframe.jline.TerminalRuntime;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.DialogAttention;
import dev.tuiframe.widget.DialogAttentionPainter;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.WidgetStyleRole;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.DirtyTabSummary;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.HelpPages;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.CursorPolicy;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.ViewportController;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * JLine terminal application wiring core + render + highlighter.
 */
public class TerminalEditorApp {
    private static final String DEFAULT_FILE_NAME = "untitled.txt";
    static final Object SAVE_AS_SCOPE_ID = new Object();
    static final Object SEARCH_SCOPE_ID = new Object();
    static final Object OPEN_FILE_SCOPE_ID = new Object();
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final int PROFILE_LOG_EVERY_FRAMES = 30;
    private static final int AUTO_SELECTION_HIGHLIGHT_MIN_CHARS = 2;
    private static final int AUTO_SELECTION_HIGHLIGHT_MAX_CHARS = 128;
    private static final int CTRL_F_SELECTION_MAX_CHARS = 256;
    private static final long RECOVERY_AUTOSAVE_DEBOUNCE_NANOS = 2_000_000_000L;
    private static final long RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS = 5_000_000_000L;
    final EditorClipboard clipboard = new EditorClipboard();
    final DialogAttention modalAttention = new DialogAttention();
    UiStyles.Theme theme = UiStyles.Theme.CYAN;
    UiStyles.Palette styles = UiStyles.forTheme(theme);
    private final Theme colorOverlayWidgetTheme = key -> resolveOverlayWidgetStyle(key, true);
    private final Theme monoOverlayWidgetTheme = key -> resolveOverlayWidgetStyle(key, false);
    final FocusManager<HelpOverlayInputRouter.Target> helpFocusManager = new FocusManager<>();
    private HelpPage helpScrollPage;
    private ListView.WrappedText helpText;
    ListView.State helpScrollState = ListView.State.initial();
    private final Theme confirmWidgetTheme = this::resolveConfirmWidgetStyle;
    final FocusManager<ConfirmChoice> confirmFocusManager = new FocusManager<>();
    private final Theme saveAsWidgetTheme = this::resolveSaveAsWidgetStyle;
    final FocusManager<SaveAsBarView.Target> saveAsFocusManager = new FocusManager<>();
    private final Theme searchWidgetTheme = this::resolveSearchWidgetStyle;
    final FocusManager<SearchFocus> searchFocusManager = new FocusManager<>();
    private final Theme colorTabBarWidgetTheme = key -> resolveTabBarWidgetStyle(key, true);
    private final Theme monoTabBarWidgetTheme = key -> resolveTabBarWidgetStyle(key, false);
    private final Theme colorTopMenuWidgetTheme = key -> resolveTopMenuWidgetStyle(key, true);
    private final Theme monoTopMenuWidgetTheme = key -> resolveTopMenuWidgetStyle(key, false);
    private final Theme colorFileTreeWidgetTheme = key -> resolveFileTreeWidgetStyle(key, true);
    private final Theme monoFileTreeWidgetTheme = key -> resolveFileTreeWidgetStyle(key, false);
    private HelpOverlayPlacement helpOverlayCache;
    boolean profileEnabled = false;
    final FrameProfiler frameProfiler = new FrameProfiler();
    private FrameComposition.OpenTreeDiffKey lastOpenTreeDiffKey;

    public static void main(String[] args) throws Exception {
        int exitCode = launch(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Runs the frontend and returns zero on success or two for invalid CLI arguments. */
    public static int launch(String[] args) throws Exception {
        return new TerminalEditorApp().run(args);
    }

    private int run(String[] args) throws Exception {
        TuiCliOptions cli;
        try {
            cli = TuiCliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            return 2;
        }
        if (cli.showHelp()) {
            printUsage(System.out);
            return 0;
        }
        try (RecoveryStore recoveryStore = new RecoveryStore()) {
            run(cli, recoveryStore);
        } finally {
            frameProfiler.close();
        }
        return 0;
    }

    private void run(TuiCliOptions cli, RecoveryStore recoveryStore) throws Exception {
        profileEnabled = cli.profileEnabled();
        if (cli.profileFile() != null) {
            PrintWriter profileWriter = ProfileLog.open(cli.profileFile(), cli.file());
            frameProfiler.setOutput(profileWriter, true);
        }

        AppSettings loadedSettings = AppSettings.defaults();
        boolean settingsFileAccepted = true;
        try {
            loadedSettings = AppSettingsStore.load();
        } catch (IOException e) {
            settingsFileAccepted = false;
            System.err.println("Warning: failed to load settings from " + AppSettingsStore.path() + ": " + e.getMessage());
        }

        AppSettingsSession settingsSession = new AppSettingsSession(loadedSettings, cli);
        AppSettings initialSettings = settingsSession.initialSettings();
        applyTheme(initialSettings.theme());
        List<EditorTab> tabs = new ArrayList<>();
        tabs.add(newEditorTab());
        SessionContext sessionContext = new SessionContext(tabs, 0);
        int activeTabIndex = sessionContext.activeTabIndex();
        TextModel model = sessionContext.model();
        EditorState state = sessionContext.state();
        EditorEngine engine = sessionContext.engine();
        RenderPlanBuilder renderPlanBuilder = sessionContext.renderPlanBuilder();
        RenderRasterizer renderer = new RenderRasterizer(1, theme);
        EditorView editorView = new EditorView();

        NotificationState notification = new NotificationState();
        UiModalState modalState = UiModalState.none();
        RecoveryAutosaveManager recoveryAutosave = new RecoveryAutosaveManager(recoveryStore);
        List<RecoveryStore.Entry> startupRecoveryEntries = new ArrayList<>();
        TextField saveAsInput = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        SaveAsSuggestionStore saveAsSuggestions = new SaveAsSuggestionStore();
        UiSessionState uiSessionState = UiSessionState.from(
            false,
            initialSettings.showTabBar(),
            initialSettings.wrapLines(),
            initialSettings.wrapWords(),
            initialSettings.readingMode(),
            initialSettings.confirmOnClose(),
            initialSettings.mouseMode(),
            initialSettings.highlightActiveGutter(),
            initialSettings.smartHomeEnd(),
            initialSettings.autoIndent(),
            initialSettings.autoPairs(),
            initialSettings.autoList(),
            initialSettings.visibleWhitespace(),
            initialSettings.syntaxHighlighting(),
            initialSettings.rainbowBrackets(),
            initialSettings.autoSelectionHighlight(),
            initialSettings.gutterDiffMode(),
            initialSettings.tabWidth(),
            initialSettings.tabInsertMode(),
            false,
            false,
            false
        );
        TextField searchQueryInput = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        TextField searchReplaceInput = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        SearchController.SearchUiOptions searchUi = SearchController.SearchUiOptions.DEFAULT;
        TopMenuController topMenu = new TopMenuController();
        Path initialOpenFileRoot = initialOpenRoot(state);
        try {
            RecoveryStore.LoadResult recoveryLoad = recoveryStore.loadAll();
            startupRecoveryEntries.addAll(recoveryLoad.entries());
            if (!recoveryLoad.skipped().isEmpty()) {
                String ids = recoveryLoad.skipped().stream()
                    .map(RecoveryStore.SkippedSnapshot::id)
                    .collect(java.util.stream.Collectors.joining(", "));
                notification.warning(
                    "Skipped " + recoveryLoad.skipped().size()
                        + " incompatible recovery snapshot(s): " + ids
                );
                System.err.println(
                    "Warning: incompatible recovery snapshots were left unchanged in "
                        + recoveryStore.directory() + ": " + recoveryLoad.skipped()
                );
            }
            if (!startupRecoveryEntries.isEmpty()) {
                modalState = UiModalState.recoveryConfirm();
            }
        } catch (IOException e) {
            System.err.println(
                "Warning: failed to load recovery snapshots from " + recoveryStore.directory() + ": " + e.getMessage()
            );
        }

        if (cli.file() != null) {
            Path file = cli.file();
            try {
                engine.open(file);
                renderPlanBuilder = new RenderPlanBuilder(resolveHighlighter(file), engine.textLayout());
                sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
            } catch (IOException e) {
                notification.error("Open failed: " + e.getMessage());
            }
        }

        try (
            OpenFileTreeController openFileTree = new OpenFileTreeController(
                initialOpenFileRoot,
                true,
                true
            );
            TerminalRuntime terminal = TerminalRuntime.open(
                new TerminalSession.Options(
                    cli.altScreenEnabled(),
                    uiSessionState.mouseModeSetting,
                    shouldForceExecTerminalProvider()
                ),
                cli.colorEnabled()
            )
        ) {
            TerminalCapabilities effectiveCapabilities = terminal.capabilities();
            boolean colorEnabled = effectiveCapabilities.colorEnabled();
            uiSessionState.mouseModeAvailable = effectiveCapabilities.mouseTracking();

            ScreenPresenter screenPresenter = terminal.screenPresenter();
            try {
                CommandRouter commandRouter = new CommandRouter();
                TuiEventLoop<TuiInputEvent> eventLoop = terminal.eventLoop();
                RenderFrameController renderFrameController = new RenderFrameController(
                    renderer,
                    editorView,
                    colorEnabled,
                    screenPresenter
                );
                UiModalState initialModalState = modalState;
                SearchController.SearchUiOptions initialSearchUi = searchUi;



                runWithRecovery(
                    eventLoop,
                    new EditorWindowController(this, new EditorWindowController.Resources(
                        commandRouter,
                        editorView,
                        eventLoop,
                        initialModalState,
                        initialSearchUi,
                        notification,
                        openFileTree,
                        recoveryAutosave,
                        renderFrameController,
                        renderer,
                        saveAsInput,
                        saveAsSuggestions,
                        screenPresenter,
                        searchQueryInput,
                        searchReplaceInput,
                        sessionContext,
                        startupRecoveryEntries,
                        tabs,
                        new EditorWindowController.TerminalAccess(terminal::width, terminal::height, terminal::setMouseTracking),
                        topMenu,
                        uiSessionState,
                        settingsSession
                    )),
                    recoveryAutosave,
                    () -> toTabSessions(tabs),
                    warning -> System.err.println("Warning: " + warning)
                );
            } finally {
                frameProfiler.close();
            }
        }

        AppSettings preferences = settingsSession.preferences(uiSessionState.settingsSnapshot(theme));
        if (settingsFileAccepted && !preferences.equals(loadedSettings)) {
            try {
                AppSettingsStore.save(preferences);
            } catch (IOException e) {
                System.err.println("Warning: failed to save settings to " + AppSettingsStore.path() + ": " + e.getMessage());
            }
        }
    }

    EditorActionApplyResult applyEditorActionResult(
        EditorActionController.Result editorAction,
        SessionContext sessionContext,
        List<EditorTab> tabs,
        int activeTabIndex,
        EditorState state,
        EditorEngine engine,
        NotificationState notification,
        TopMenuController topMenu,
        OpenFileTreeController openFileTree,
        boolean openFileMode,
        OpenFileInputRouter.Focus openFileFocus,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        RenderRasterizer renderer
    ) {
        boolean running = editorAction.running();
        UiModalState modalState = editorAction.modalState();
        boolean nextOpenFileMode = openFileMode;
        OpenFileInputRouter.Focus nextOpenFileFocus = openFileFocus;

        if (editorAction.clearNotification()) {
            notification.clear();
        }
        if (editorAction.notificationError() != null) {
            notification.error(editorAction.notificationError());
        }
        if (editorAction.requestSaveCurrent()) {
            try {
                engine.save();
                saveAsSuggestions.rememberSavedPath(tabs.get(activeTabIndex).tabSession(), state.getCurrentFile());
                notification.success(savedMessage(state));
            } catch (IOException e) {
                notification.error(saveErrorMessage(e));
            }
        }
        if (editorAction.requestOpenFile()) {
            topMenu.close();
            nextOpenFileMode = true;
            openFileTree.setRoot(initialOpenRoot(state));
            nextOpenFileFocus = OpenFileInputRouter.Focus.LIST;
        }
        if (editorAction.requestToggleTheme()) {
            applyTheme(theme.next());
            renderer.setTheme(theme);
            notification.info("Theme: " + theme.label());
        }

        switch (editorAction.saveAsPreset()) {
            case NONE -> {
            }
            case DEFAULT_FILE_NAME -> saveAsInput.set(
                saveAsSuggestions.suggestionFor(
                    tabs.get(activeTabIndex).tabSession(),
                    state.getCurrentFile(),
                    DEFAULT_FILE_NAME
                )
            );
            case FROM_CURRENT_OR_DEFAULT -> {
                saveAsInput.set(
                    saveAsSuggestions.suggestionFor(
                        tabs.get(activeTabIndex).tabSession(),
                        state.getCurrentFile(),
                        DEFAULT_FILE_NAME
                    )
                );
            }
            case SUGGESTED_FROM_CURRENT -> {
                String suggestion = saveAsSuggestions.suggestionFor(
                    tabs.get(activeTabIndex).tabSession(),
                    null,
                    suggestWritableName(state.getCurrentFile()),
                    this::isSuitableSaveAsDraft
                );
                saveAsInput.set(suggestion);
                saveAsSuggestions.rememberDraft(tabs.get(activeTabIndex).tabSession(), suggestion);
            }
        }

        switch (editorAction.tabCommand()) {
            case NONE -> {
            }
            case NEW_TAB -> sessionContext.addNewTabAndActivate(newEditorTab());
            case NEXT_TAB -> {
                int nextIndex = switchTab(tabs, activeTabIndex, 1);
                sessionContext.activate(nextIndex);
            }
            case PREV_TAB -> {
                int nextIndex = switchTab(tabs, activeTabIndex, -1);
                sessionContext.activate(nextIndex);
            }
            case CLOSE_TAB -> sessionContext.activate(closeCurrentTab(tabs, activeTabIndex, saveAsSuggestions));
        }

        return new EditorActionApplyResult(
            running,
            modalState,
            nextOpenFileMode,
            nextOpenFileFocus
        );
    }

    void applySaveAsTextInputEffect(TextField saveAsInput, SaveFlowController.TextInputEffect effect) {
        if (effect == null) {
            return;
        }
        switch (effect.kind()) {
            case NONE -> {
            }
            case SET_TEXT -> saveAsInput.set(effect.text());
            case CLEAR -> saveAsInput.clear();
        }
    }

    static void applyWheelVerticalScroll(
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        RenderPlan plan,
        int deltaRows,
        int tabWidth
    ) {
        scrollDocumentViewport(
            model, textLayout, state, plan == null ? 1 : plan.contentRows(),
            plan == null ? 0 : Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth()),
            plan != null && plan.wrapLines(), plan != null && plan.wrapByWords(), deltaRows, tabWidth
        );
    }

    static void scrollDocumentViewport(
        TextModel model,
        EditorTextLayout layout,
        EditorState state,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords,
        int delta,
        int tabWidth
    ) {
        EditorViewCoordinates coordinates = new EditorViewCoordinates(
            layout, Math.max(1, contentCols), wrapLines && contentCols > 0, wrapByWords, tabWidth
        );
        int target = ScrollInteraction.scrollBy(
            coordinates.rowOffset(EditorViewCoordinates.Top.from(state)), delta,
            Math.max(1, contentRows), coordinates.totalRows()
        );
        coordinates.applyTop(state, coordinates.topAtRow(target));
    }

    record EditorActionApplyResult(
        boolean running,
        UiModalState modalState,
        boolean openFileMode,
        OpenFileInputRouter.Focus openFileFocus
    ) {
    }

    record OpenFileApplyResult(boolean openFileMode, int activeTabIndex) {
    }

    record SaveFlowProgress(boolean running, UiModalState modalState) {
    }

    private void drawTopFileNameBold(
        CellBuffer buffer,
        Canvas canvas,
        int cols,
        EditorState state,
        int startCol
    ) {
        if (buffer.rows() < 1 || cols <= startCol || startCol < 0) {
            return;
        }
        String file = state.getCurrentFile() == null ? "--no name--" : state.getCurrentFile().toString();
        int endCol = Math.min(cols, startCol + CellText.width(file));
        for (int col = startCol; col < endCol;) {
            var cell = buffer.get(0, col);
            if (cell.continuation()) {
                col++;
                continue;
            }
            canvas.put(col, 0, cell.grapheme(), styles.topbarFilenameHighlight());
            col += Math.max(1, cell.width());
        }
    }

    private void drawMenuLine(Canvas canvas, int row, int startCol, String text, CellStyle style) {
        CellText.draw(canvas, startCol, row, text, style);
    }

    private Canvas frameCanvas(CellBuffer buffer, int rows, int cols) {
        Canvas canvas = new Canvas(buffer);
        canvas.clip(0, 0, Math.max(0, cols), Math.max(0, rows));
        return canvas;
    }

    private void paintConfirmButtons(Canvas canvas, ConfirmBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, confirmWidgetTheme, confirmFocusManager);
    }

    private void paintSaveAsField(Canvas canvas, SaveAsBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, saveAsWidgetTheme, saveAsFocusManager);
    }

    private void paintSearchBar(Canvas canvas, SearchBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, searchWidgetTheme, searchFocusManager);
    }

    SearchFocus currentSearchFocus() {
        return searchFocusManager.focused().orElse(SearchFocus.FIND);
    }

    void focusSearch(SearchBarView.Placement placement, SearchFocus target) {
        if (placement != null && target != null) {
            searchFocusManager.focus(placement.focusScope(), target);
        }
    }

    static SearchFocus toggleTarget(SearchBarView.Action action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case TOGGLE_CASE -> SearchFocus.CASE;
            case TOGGLE_WORD -> SearchFocus.WORD;
            case TOGGLE_WILDCARD -> SearchFocus.WILDCARD;
            case TOGGLE_ALL_TABS -> SearchFocus.ALL;
            default -> null;
        };
    }

    static TuinkCommand searchCommand(SearchBarView.Action action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case FIND_NEXT -> TuinkCommand.SEARCH_NEXT;
            case FIND_PREVIOUS -> TuinkCommand.SEARCH_PREVIOUS;
            case REPLACE -> TuinkCommand.SEARCH_REPLACE;
            case REPLACE_ALL -> TuinkCommand.SEARCH_REPLACE_ALL;
            default -> null;
        };
    }

    static boolean searchOwnsInteractiveRow(UiModalState modalState) {
        return modalState != null && modalState.isNone();
    }

    static boolean shouldExtendEditorSelection(TuiInputEvent event) {
        return event instanceof PointerEvent pointer
            && pointer.kind() == PointerEvent.Kind.PRESS
            && pointer.button() == PointerEvent.Button.LEFT
            && pointer.modifiers().contains(KeyStroke.Modifier.SHIFT);
    }

    static EditorInput resolveEditorInput(
        CommandRouter commandRouter,
        TuiInputEvent event,
        CommandInvocation pendingCommand,
        CommandRouter.RoutedCommand routedMenuResult,
        boolean searchOwnsInput,
        SearchFocus searchFocus
    ) {
        Objects.requireNonNull(commandRouter, "commandRouter");
        Objects.requireNonNull(event, "event");
        if (routedMenuResult instanceof CommandRouter.RoutedAction selected) {
            return new EditorInput(event, selected.action());
        }
        if (pendingCommand != null) {
            if (searchOwnsInput) {
                TuinkCommand command = TuinkCommand.fromId(pendingCommand.id()).orElse(null);
                if (command == null) {
                    return null;
                }
                pendingCommand = commandRouter.invocationForSearch(command, searchFocus, pendingCommand);
            }
            TuinkCommand command = commandRouter.invoke(pendingCommand).orElse(null);
            return command == null ? null : new EditorInput(event, command);
        }
        return event instanceof TextInput || event instanceof PointerEvent
            ? new EditorInput(event, null)
            : null;
    }

    static boolean searchOwnsRawInput(
        boolean searchMode,
        UiModalState modalState,
        boolean openFileMode,
        boolean topMenuOpen
    ) {
        return searchOwnsRawInput(
            searchMode,
            modalState,
            openFileMode,
            topMenuOpen,
            false
        );
    }

    static boolean searchOwnsRawInput(
        boolean searchMode,
        UiModalState modalState,
        boolean openFileMode,
        boolean topMenuOpen,
        boolean typedMenuCommandSelected
    ) {
        return searchMode
            && searchOwnsInteractiveRow(modalState)
            && !openFileMode
            && !topMenuOpen
            && !typedMenuCommandSelected;
    }

    static TabBarInputRouter.Policy tabBarInputPolicy(
        boolean placementAvailable,
        boolean mouseEnabled,
        UiModalState modalState,
        boolean openFileMode,
        boolean topMenuOpen,
        boolean searchMode
    ) {
        if (!placementAvailable || !mouseEnabled || modalState == null
            || openFileMode || topMenuOpen) {
            return null;
        }
        if (modalState.isCloseTabConfirm()) {
            return TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM;
        }
        if (!modalState.isNone()) {
            return null;
        }
        return searchMode ? TabBarInputRouter.Policy.SEARCH : TabBarInputRouter.Policy.NORMAL;
    }

    static ModalInputPolicy.Kind modalInputKind(UiModalState modal, boolean openFileMode) {
        if (modal.isNone()) {
            return openFileMode ? ModalInputPolicy.Kind.OPEN_FILE : ModalInputPolicy.Kind.NONE;
        }
        if (modal.isSaveAs()) {
            return ModalInputPolicy.Kind.SAVE_AS;
        }
        if (modal.isHelpHints() || modal.isHelpLicenses() || modal.isHelpAuthor()) {
            return ModalInputPolicy.Kind.HELP;
        }
        return ModalInputPolicy.Kind.CONFIRM;
    }

    static Object modalInputOwner(UiModalState modal, boolean openFileMode) {
        return modal.isNone() ? openFileMode ? ModalInputPolicy.Kind.OPEN_FILE : null : modal;
    }

    static boolean belongsToTabChrome(TuiInputEvent event, Rect bounds) {
        if (!(event instanceof PointerEvent pointer)) {
            return false;
        }
        boolean verticalWheel = pointer.kind() == PointerEvent.Kind.SCROLL_UP
            || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN;
        return bounds.contains(pointer.column(), pointer.row())
            || verticalWheel && bounds.y() > 0 && pointer.row() == bounds.y() - 1
                && pointer.column() >= bounds.x() && pointer.column() < bounds.rightExclusive();
    }

    static long earlierDeadline(long first, long second) {
        return first < 0L ? second : second < 0L ? first : Math.min(first, second);
    }

    /** Geometry of the visible owner, shared by blocked-pointer feedback and painting. */
    Rect blockingSurface(FrameComposition composition) {
        FrameComposition.Layers layers = composition.layers();
        int rows = composition.document().layout().rows();
        int cols = composition.document().layout().cols();
        return blockingSurface(rows, cols, composition.document().layout().contentTopRow(),
            composition.surface().inlinePrompt().height(), layers.saveAsMode() || layers.confirm() != null,
            layers.helpPage(), layers.openFile().visible() ? layers.openFile().layout() : null);
    }

    Rect blockingSurface(int rows, int cols, int contentTop, int promptHeight,
                                boolean inline, HelpPage help, OpenDialogLayout open) {
        if (help != null || open != null) {
            if (rows < 8 || cols < 24) {
                return new Rect(0, 0, 0, 0);
            }
            if (help != null) {
                HelpDialogLayout layout = helpDialogLayout(rows, cols, help.rows().size());
                return new Rect(layout.left(), layout.top(), layout.width(), layout.height());
            }
            return new Rect(open.left(), open.top(), open.width(), open.height());
        }
        if (inline) {
            Rect prompt = new Rect(0, rows - 1 - promptHeight, cols, promptHeight);
            return prompt.intersection(new Rect(0, contentTop, cols, Math.max(0, rows - 1 - contentTop)));
        }
        return new Rect(0, 0, 0, 0);
    }

    void paintBlockingAttention(CellBuffer buffer, FrameComposition composition, boolean colorEnabled) {
        if (!composition.layers().blockedInputHighlight()) {
            return;
        }
        CellStyle accent = blockingAccent(colorEnabled);
        DialogAttentionPainter.paint(
            buffer,
            blockingSurface(composition),
            composition.layers().helpPage() != null || composition.layers().openFile().visible(),
            accent
        );
    }

    private CellStyle blockingAccent(boolean colorEnabled) {
        return colorEnabled ? styles.notifyWarning()
            : new CellStyle(CellColor.DEFAULT, CellColor.DEFAULT, true, true);
    }

    static UiModalState consumeRequestedHelpModal(UiSessionState ui, UiModalState current) {
        if (ui.helpHintsMode) {
            ui.helpHintsMode = false;
            return UiModalState.helpHints();
        }
        if (ui.helpLicensesMode) {
            ui.helpLicensesMode = false;
            return UiModalState.helpLicenses();
        }
        if (ui.helpAuthorMode) {
            ui.helpAuthorMode = false;
            return UiModalState.helpAuthor();
        }
        return current;
    }

    private CellStyle focusedButtonStyle(CellStyle parentStyle) {
        if (parentStyle == null) {
            return styles.menuSelected();
        }
        return new CellStyle(parentStyle.bg(), parentStyle.fg(), true, parentStyle.underline());
    }

    private String padRight(String text, int width) {
        return CellText.fit(text, width);
    }

    Path initialOpenRoot(EditorState state) {
        if (state.getCurrentFile() != null) {
            Path parent = state.getCurrentFile().toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private void drawOpenFileOverlay(
        Canvas canvas,
        int rows,
        int cols,
        FrameComposition.OpenFile openFile,
        boolean colorEnabled
    ) {
        drawOpenFileOverlay(canvas, rows, cols, openFile, colorEnabled, false);
    }

    private void drawOpenFileOverlay(
        Canvas canvas,
        int rows,
        int cols,
        FrameComposition.OpenFile openFile,
        boolean colorEnabled,
        boolean highlighted
    ) {
        drawOpenFileOverlay(canvas, rows, cols, openFile, colorEnabled, highlighted, false);
    }

    private void drawOpenFileOverlay(
        Canvas canvas, int rows, int cols, FrameComposition.OpenFile openFile,
        boolean colorEnabled, boolean highlighted, boolean contentFocused
    ) {
        if (!openFile.visible() || rows < 8 || cols < 24) {
            return;
        }
        OpenDialogLayout layout = openFile.layout();
        Modal frame = openFileFrame(
            layout, openFile.placement() != null && openFile.placement().loading(),
            highlighted ? blockingAccent(colorEnabled) : styles.dialogBorder()
        );
        Theme childTheme = frame.childTheme(colorEnabled ? colorFileTreeWidgetTheme : monoFileTreeWidgetTheme);
        frame.layout(new Rect(layout.left(), layout.top(), layout.width(), layout.height())).paint(canvas, childTheme);
        String pathLine = " " + openFile.rootLabel();
        drawMenuLine(
            canvas,
            layout.top() + 1,
            layout.left() + 1,
            padRight(pathLine, Math.max(0, layout.width() - 2)),
            styles.dialog()
        );
        FileTreeView.Placement treePlacement = openFile.placement();
        if (treePlacement != null) {
            treePlacement.paint(
                canvas,
                childTheme
            );
            OpenFileDialogView.place(
                OPEN_FILE_SCOPE_ID, layout, treePlacement, openFile.showHiddenFiles(), new Rect(0, 0, cols, rows)
            ).paintControls(canvas, childTheme, openFile.focus(), contentFocused);
        }
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout) {
        return openFileFrame(layout, false);
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout, boolean loading) {
        return openFileFrame(layout, loading, styles.dialogBorder());
    }

    private Modal openFileFrame(OpenFileDialogController.OpenDialogLayout layout, boolean loading, CellStyle border) {
        return new Modal(
            layout.top(),
            layout.left(),
            layout.width(),
            layout.height(),
            loading ? " Open File (Loading...) " : " Open File ",
            styles.dialog(),
            border,
            styles.dialogTitle()
        );
    }

    private HelpDialogLayout helpDialogLayout(int rows, int cols, int contentRows) {
        int preferredWidth = 68;
        // Frame borders and one footer row surround the help viewport.
        int preferredHeight = contentRows + 3;
        int width = Math.max(24, Math.min(preferredWidth, cols - 4));
        int height = Math.max(8, Math.min(preferredHeight, rows - 4));
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        return new HelpDialogLayout(top, left, width, height);
    }

    private Modal infoDialogFrame(HelpDialogLayout layout, String title) {
        return new Modal(layout.width(), layout.height(), title);
    }

    private Button helpCloseButton(HelpDialogLayout layout) {
        String closeText = " Close ";
        int col = layout.left() + Math.max(1, (layout.width() - (closeText.length() + 2)) / 2);
        int row = layout.top() + layout.height() - 1;
        return new Button(row, col, closeText);
    }

    HelpOverlayPlacement helpOverlayPlacement(int rows, int cols, HelpPage page) {
        HelpDialogLayout layout = helpDialogLayout(rows, cols, page.rows().size());
        int visibleRows = rows < 8 || cols < 24 ? 0 : Math.max(0, layout.height() - 3);
        if (helpScrollPage != page) {
            helpScrollPage = page;
            helpScrollState = ListView.State.initial();
            helpText = ListView.wrappedText(page.rows());
        }
        HelpOverlayPlacement cached = helpOverlayCache;
        Rect viewport = new Rect(0, 0, cols, rows);
        if (cached != null && !cached.viewport().equals(viewport) && helpScrollState.drag() != null) {
            helpScrollState = new ListView.State(-1, helpScrollState.firstVisibleIndex());
        }
        if (cached != null
            && cached.page() == page
            && cached.content().state().equals(helpScrollState)
            && cached.layout().equals(layout)
            && cached.viewport().equals(viewport)) {
            return cached;
        }
        ListView.Placement content = helpText.layout(
            new Rect(layout.left() + 2, layout.top() + 1, Math.max(0, layout.width() - 4), visibleRows),
            helpScrollState,
            cached != null && cached.page() == page ? cached.content() : null
        );
        helpScrollState = content.state();
        Modal frame = infoDialogFrame(layout, page.title());
        Button close = helpCloseButton(layout);
        RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> closeTarget = new RoutedTarget<>(
            HelpOverlayInputRouter.Target.CLOSE,
            close.layout(
                new Rect(close.col(), close.row(), close.width(), 1)
            ),
            FocusSpace.root(viewport),
            rows >= 8 && cols >= 24,
            rows >= 8 && cols >= 24
        );
        HelpOverlayPlacement prepared = new HelpOverlayPlacement(
            page,
            content,
            helpFooter(content, Math.max(0, layout.width() - 4)),
            layout,
            viewport,
            frame.layout(new Rect(layout.left(), layout.top(), layout.width(), layout.height())),
            close,
            FocusScope.single(
                HelpOverlayInputRouter.Scope.HELP,
                FocusResetPolicy.FIRST_FOCUSABLE,
                closeTarget
            )
        );
        helpOverlayCache = prepared;
        return prepared;
    }

    void resetHelpScroll() {
        helpScrollPage = null;
        helpText = null;
        helpScrollState = ListView.State.initial();
        helpOverlayCache = null;
    }

    private static String helpFooter(ListView.Placement content, int textWidth) {
        int visibleRows = content.contentBounds().height();
        if (content.rowCount() <= visibleRows) {
            return "Esc/Enter: close";
        }
        int firstRow = content.state().firstVisibleIndex();
        int end = Math.min(content.rowCount(), firstRow + visibleRows);
        String range = (firstRow + 1) + "-" + end + "/" + content.rowCount();
        return range + (textWidth >= 44 ? "  Up/Down PgUp/PgDn Home/End" : " PgUp/PgDn");
    }

    HelpOverlayInputRouter.Result routeHelpOverlayInput(
        int rows, int cols, HelpPage page, TuiInputEvent event
    ) {
        return routeHelpOverlayInput(rows, cols, page, event, null, null);
    }

    HelpOverlayInputRouter.Result routeHelpOverlayInput(
        int rows, int cols, HelpPage page, TuiInputEvent event,
        WindowPointerCapture capture, Object surfaceId
    ) {
        HelpOverlayPlacement overlay = helpOverlayPlacement(rows, cols, page);
        boolean visible = overlay.content().bounds().height() > 0;
        var scrolled = overlay.content().handle(
            event, new FocusContext(visible, visible, FocusSpace.root(overlay.viewport()))
        );
        if (scrolled.output().orElse(null) instanceof ListView.StateChanged changed) {
            helpScrollState = changed.state();
        }
        if (capture != null) {
            capture.observe(surfaceId, EditorWindowController.helpContentScope(overlay), "content", scrolled);
        }
        if (scrolled.consumed()) {
            return new HelpOverlayInputRouter.Result(true, false);
        }
        HelpOverlayInputRouter.Result result = HelpOverlayInputRouter.route(
            helpFocusManager, overlay.focusScope(), overlay.frame(), event
        );
        if (result.closeRequested()) {
            resetHelpScroll();
        }
        return result;
    }

    void paintHelpOverlay(Canvas canvas, int rows, int cols, HelpPage page, boolean colorEnabled) {
        paintHelpOverlay(canvas, rows, cols, page, colorEnabled, false);
    }

    void paintHelpOverlay(
        Canvas canvas, int rows, int cols, HelpPage page, boolean colorEnabled, boolean highlighted
    ) {
        if (page == null) {
            resetHelpScroll();
            return;
        }
        drawInfoOverlay(canvas, helpOverlayPlacement(rows, cols, page), colorEnabled, highlighted);
    }

    private void drawInfoOverlay(
        Canvas canvas,
        HelpOverlayPlacement overlay,
        boolean colorEnabled,
        boolean highlighted
    ) {
        if (overlay == null || overlay.viewport().height() < 8 || overlay.viewport().width() < 24) {
            return;
        }
        HelpDialogLayout layout = overlay.layout();
        Theme widgetTheme = colorEnabled ? colorOverlayWidgetTheme : monoOverlayWidgetTheme;
        if (highlighted) {
            widgetTheme = widgetTheme.withSurface(styles.dialog(), blockingAccent(colorEnabled));
        }
        overlay.frame().paint(canvas, widgetTheme);
        int textCol = layout.left() + 2;
        int textWidth = Math.max(0, layout.width() - 4);
        overlay.content().paint(canvas, widgetTheme);
        drawMenuLine(
            canvas,
            layout.top() + layout.height() - 2,
            textCol,
            clipText(overlay.footer(), textWidth),
            styles.dialog()
        );
        RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> closeTarget = overlay.focusScope()
            .target(HelpOverlayInputRouter.Target.CLOSE)
            .orElseThrow();
        closeTarget.placement().paint(
            canvas,
            widgetTheme,
            helpFocusManager.context(overlay.focusScope(), closeTarget.id())
                .orElseThrow()
                .widgetState()
        );
    }

    CellStyle resolveOverlayWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MODAL_BODY.equals(key)
            || WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.LIST_ITEM.equals(key)) {
            return styles.dialog();
        }
        if (WidgetThemeKeys.MODAL_BORDER.equals(key)) {
            return styles.dialogBorder();
        }
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return styles.dialogTitle();
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono();
        }
        if (WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.dialog();
        }
        return null;
    }

    CellStyle resolveConfirmWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return focusedButtonStyle(styles.promptAlert());
        }
        if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.promptAlert();
        }
        return null;
    }

    CellStyle resolveSaveAsWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key) || WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(key)) {
            return focusedButtonStyle(styles.promptAlert());
        }
        if (WidgetThemeKeys.TEXT_FIELD.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(key)
            || WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.promptAlert();
        }
        return null;
    }

    CellStyle resolveSearchWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key) || WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(key)) {
            return focusedButtonStyle(styles.searchBar());
        }
        if (WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(key)) {
            return styles.searchBar();
        }
        return null;
    }

    CellStyle resolveTabBarWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.TAB_ITEM_ACTIVE.equals(key)) {
            return colorEnabled ? styles.tabbarActive() : styles.tabbarActiveMono();
        }
        if (WidgetThemeKeys.TAB_BAR.equals(key)
            || WidgetThemeKeys.TAB_ITEM.equals(key)
            || WidgetThemeKeys.TAB_ITEM_DISABLED.equals(key)) {
            return colorEnabled ? styles.tabbar() : styles.tabbarMono();
        }
        return null;
    }

    CellStyle resolveTopMenuWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MENU_BAR_ACTIVE.equals(key)) {
            return colorEnabled ? styles.menuTopSelected() : styles.menuSelectedMono();
        }
        if (WidgetThemeKeys.MENU_BAR.equals(key)
            || WidgetThemeKeys.MENU_BAR_DISABLED.equals(key)) {
            return styles.renderTopbar();
        }
        if (WidgetThemeKeys.MENU_ITEM_SELECTED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.menuSelectedMono();
        }
        if (WidgetThemeKeys.MENU_ITEM_DISABLED.equals(key)) {
            return colorEnabled ? styles.contextMenuDisabledItem() : styles.menuPopup();
        }
        if (WidgetThemeKeys.MENU_POPUP.equals(key)
            || WidgetThemeKeys.MENU_ITEM.equals(key)) {
            return styles.menuPopup();
        }
        return null;
    }

    CellStyle resolveFileTreeWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return styles.dialogTitle();
        }
        if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(key)
            || WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono();
        }
        if (WidgetThemeKeys.LIST_ITEM.equals(key)
            || WidgetThemeKeys.LIST_ITEM_DISABLED.equals(key)) {
            return styles.dialog();
        }
        if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.dialog();
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return colorEnabled ? styles.menuSelected() : styles.dialogSelectedMono();
        }
        return null;
    }

    ConfirmBarInputRouter.Policy confirmPolicy(UiModalState modalState) {
        if (modalState.isRecoveryConfirm()) {
            return ConfirmBarInputRouter.Policy.recovery();
        }
        if (modalState.isSaveOverwriteConfirm()) {
            return ConfirmBarInputRouter.Policy.overwrite();
        }
        if (modalState.isQuitConfirm()) {
            return ConfirmBarInputRouter.Policy.quit();
        }
        if (modalState.isCloseTabConfirm()) {
            return ConfirmBarInputRouter.Policy.closeTab();
        }
        throw new IllegalArgumentException("Modal is not a confirmation prompt: " + modalState.mode());
    }

    void deactivateConfirmFocus() {
        confirmFocusManager.activeScopeId().ifPresent(confirmFocusManager::deactivate);
    }

    HelpPage activeHelpPage(boolean helpHintsMode, boolean helpLicensesMode, boolean helpAuthorMode) {
        if (helpHintsMode) {
            return HelpPages.KEYMAP;
        }
        if (helpLicensesMode) {
            return HelpPages.LICENSES;
        }
        if (helpAuthorMode) {
            return HelpPages.AUTHOR;
        }
        return null;
    }

    private String clipText(String text, int width) {
        if (text == null || width <= 0) {
            return "";
        }
        return CellText.clip(text, 0, width);
    }

    OpenFileApplyResult applyOpenFileIntent(
        OpenFileInputRouter.Intent intent,
        OpenFileTreeController tree,
        List<EditorTab> tabs,
        int activeTabIndex,
        NotificationState notification
    ) {
        if (intent == null) {
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.ToggleDirectory toggle) {
            tree.requestExpansion(toggle.id(), toggle.expand());
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.ChangeRoot root) {
            tree.setRoot(root.root());
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.MoveToParentRoot) {
            tree.moveToParentRoot();
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.ToggleHidden) {
            tree.toggleHiddenFiles();
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.CloseDialog) {
            return new OpenFileApplyResult(false, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.SelectionRejected rejected) {
            notification.warning(
                rejected.reason() == OpenFileInputRouter.Rejection.ERROR
                    ? "The selected tree entry cannot be opened"
                    : "Select a file"
            );
            return new OpenFileApplyResult(true, activeTabIndex);
        }
        if (intent instanceof OpenFileInputRouter.OpenFile open) {
            Path target = open.file();
            try {
                EditorTab tab = newEditorTab();
                tab.engine().openExisting(target);
                tab = tab.withRenderPlanBuilder(
                    new RenderPlanBuilder(resolveHighlighter(target), tab.textLayout())
                );
                tabs.add(tab);
                int newIndex = tabs.size() - 1;
                notification.success("Opened " + target);
                return new OpenFileApplyResult(false, newIndex);
            } catch (NoSuchFileException e) {
                notification.error("Open failed: file no longer exists: " + target);
                return new OpenFileApplyResult(true, activeTabIndex);
            } catch (IOException e) {
                notification.error("Open failed: " + e.getMessage());
                return new OpenFileApplyResult(true, activeTabIndex);
            }
        }
        throw new IllegalStateException("Unsupported open-file intent: " + intent);
    }

    String buildBottomBar(
        EditorState state,
        TextModel model,
        int cols,
        boolean readingMode,
        int activeTabIndex,
        int totalTabs,
        String searchSummary
    ) {
        final int leftMargin = 1;
        final int rightMargin = 1;
        Position p = state.getCursor();
        int totalLines = model.lineCount();
        int lineCols = model.getLine(p.line()).length();
        String indicators = buildStateIndicators(state, readingMode);
        String left = " ".repeat(Math.max(0, leftMargin))
            + "Ln " + (p.line() + 1) + "/" + totalLines
            + ", Col " + (p.col() + 1) + "/" + lineCols;
        if (totalTabs > 1) {
            left = left + "  Tab " + (activeTabIndex + 1) + "/" + totalTabs;
        }
        if (searchSummary != null && !searchSummary.isBlank()) {
            left = left + "  " + searchSummary;
        }
        if (cols <= 0) {
            return "";
        }
        int leftWidth = CellText.width(left);
        if (leftWidth >= cols) {
            return CellText.clip(left, 0, cols);
        }
        String rightBlock = indicators + " ".repeat(Math.max(0, rightMargin));
        int rightWidth = CellText.width(rightBlock);
        if (rightWidth >= cols) {
            return CellText.clip(rightBlock, 0, cols);
        }
        int free = cols - leftWidth;
        if (free <= rightWidth) {
            return left + CellText.clip(rightBlock, 0, Math.max(0, free));
        }
        return left + " ".repeat(free - rightWidth) + rightBlock;
    }

    String buildSearchSummary(
        boolean searchMode,
        String query,
        SearchController.SearchUiOptions searchUi,
        int matchCount,
        int totalTabs
    ) {
        if (!searchMode || query == null || query.isEmpty()) {
            return "";
        }
        if (searchUi != null && searchUi.allTabs() && totalTabs > 1) {
            return "Find " + matchCount + " in " + totalTabs + " tabs";
        }
        return "Find " + matchCount;
    }

    private String buildStateIndicators(EditorState state, boolean readingMode) {
        String ro = isReadOnlyFile(state.getCurrentFile()) ? "RO" : "RW";
        String enc = state.getFileEncoding() == null ? "UTF-8" : state.getFileEncoding().name();
        String eol = switch (state.getLineSeparator()) {
            case EditorState.LINE_SEPARATOR_CRLF -> "CRLF";
            case EditorState.LINE_SEPARATOR_CR -> "CR";
            default -> "LF";
        };
        return ro + " | ENC:" + enc + " | EOL:" + eol + " | " + (readingMode ? "READ" : "INS");
    }

    private RenderFrameResult renderFrame(
        FrameComposition composition,
        RenderFrameController services
    ) {
        FrameComposition.Document document = composition.document();
        FrameComposition.Surface surface = composition.surface();
        FrameComposition.Search search = composition.search();
        FrameComposition.Layers layers = composition.layers();
        FrameComposition.OpenFile openFile = layers.openFile();
        RenderPlanBuilder renderPlanBuilder = document.planBuilder();
        TextModel model = document.model();
        EditorState state = document.state();
        EditorLayout editorLayout = document.layout();
        RenderPlan previousPlan = document.previousPlan();
        int rows = editorLayout.rows();
        int cols = editorLayout.cols();
        int contentTopRow = editorLayout.contentTopRow();
        int topMenuPrefixWidth = surface.topBar().menuPrefixWidth();
        String topBar = surface.topBar().text();
        String bottomBar = surface.bottomBar();
        InlinePromptBar inlinePromptBar = surface.inlinePrompt();
        NotificationBar notificationBar = surface.notification();
        boolean searchMode = search.active();
        String searchQuery = search.query();
        boolean autoSelectionHighlightSetting = search.autoSelectionHighlight();
        SearchBarView.Placement searchBarPlacement = search.placement();
        TabBarView.Placement tabBarPlacement = layers.tabBar();
        TopMenuView.Placement topMenuPlacement = layers.topMenu();
        SaveAsBarView.Placement saveAsBarPlacement = layers.saveAs();
        boolean saveAsMode = layers.saveAsMode();
        ConfirmBarView.Placement confirmBarPlacement = layers.confirm();
        boolean openFileMode = openFile.visible();
        FileTreeView.Placement openFileTreePlacement = openFile.placement();
        HelpPage helpPage = layers.helpPage();
        boolean helpMode = helpPage != null;
        HelpOverlayPlacement helpOverlay = helpPage == null
            ? null
            : helpOverlayPlacement(rows, cols, helpPage);
        boolean colorEnabled = services.colorEnabled;
        ScreenPresenter screenPresenter = services.screenPresenter;
        RenderRasterizer renderer = services.renderer;
        EditorView editorView = services.editorView;
        RenderPlan plan;
        SearchOptions renderSearchOptions = searchMode
            ? search.options()
            : SearchOptions.DEFAULT;
        String renderSearchQuery = resolveRenderSearchQuery(
            searchMode,
            searchQuery,
            model,
            state,
            autoSelectionHighlightSetting,
            editorView.isMouseSelecting()
        );
        if (profileEnabled) {
            long buildStart = System.nanoTime();
            plan = renderPlanBuilder.build(
                model,
                state,
                rows,
                cols,
                topBar,
                bottomBar,
                renderSearchQuery,
                renderSearchOptions,
                inlinePromptBar.text(),
                inlinePromptBar.style(),
                notificationBar.text(),
                notificationBar.style(),
                composition.wrapLines(),
                composition.wrapWords(),
                editorLayout
            );
            frameProfiler.buildNanos = System.nanoTime() - buildStart;
        } else {
            plan = renderPlanBuilder.build(
                model,
                state,
                rows,
                cols,
                topBar,
                bottomBar,
                renderSearchQuery,
                renderSearchOptions,
                inlinePromptBar.text(),
                inlinePromptBar.style(),
                notificationBar.text(),
                notificationBar.style(),
                composition.wrapLines(),
                composition.wrapWords(),
                editorLayout
            );
        }
        CellBuffer current;
        if (profileEnabled) {
            long rasterStart = System.nanoTime();
            current = renderer.rasterize(plan, rows, cols, contentTopRow);
            frameProfiler.rasterNanos = System.nanoTime() - rasterStart;
        } else {
            current = renderer.rasterize(plan, rows, cols, contentTopRow);
        }
        Canvas canvas = frameCanvas(current, rows, cols);
        paintSaveAsField(canvas, saveAsBarPlacement);
        paintConfirmButtons(canvas, confirmBarPlacement);
        paintSearchBar(canvas, searchBarPlacement);
        if (tabBarPlacement != null) {
            tabBarPlacement.paint(
                canvas,
                colorEnabled ? colorTabBarWidgetTheme : monoTabBarWidgetTheme
            );
        }
        if (topMenuPlacement != null) {
            topMenuPlacement.paint(
                canvas,
                colorEnabled ? colorTopMenuWidgetTheme : monoTopMenuWidgetTheme
            );
        }
        drawTopFileNameBold(current, canvas, cols, state, topMenuPrefixWidth);
        drawOpenFileOverlay(
            canvas,
            rows,
            cols,
            openFile,
            colorEnabled,
            composition.layers().blockedInputHighlight(),
            composition.layers().contentFocused()
        );
        paintHelpOverlay(canvas, rows, cols, helpPage, colorEnabled, composition.layers().blockedInputHighlight());
        paintBlockingAttention(current, composition, colorEnabled);

        EditorView.CursorPosition editorCursor = editorView.cursorFromPlan(plan, contentTopRow);
        int desiredCursorRow = editorCursor.row();
        int desiredCursorCol = editorCursor.col();
        Point searchCursor = searchBarPlacement == null
            ? null
            : searchBarPlacement.cursorPosition(searchFocusManager).orElse(null);
        boolean searchOwnsCursor = searchBarPlacement != null && !search.editorFocused();
        boolean blockingContentFocused = composition.layers().contentFocused();
        boolean topMenuOpen = topMenuPlacement != null && topMenuPlacement.state().isOpen();
        boolean desiredCursorVisible = CursorPolicy.shouldShowCursor(
            saveAsMode && !blockingContentFocused,
            searchOwnsCursor,
            searchCursor != null,
            confirmBarPlacement != null && !blockingContentFocused,
            topMenuOpen,
            openFileMode && !blockingContentFocused,
            helpMode && !blockingContentFocused
        );
        boolean editorOwnsCursor = blockingContentFocused || !saveAsMode
            && !searchOwnsCursor
            && confirmBarPlacement == null
            && !topMenuOpen
            && !openFileMode
            && !helpMode;
        if (editorOwnsCursor && !plan.cursorInViewport()) {
            desiredCursorVisible = false;
        }
        if (blockingContentFocused && blockingSurface(composition).contains(desiredCursorCol - 1, desiredCursorRow - 1)) {
            desiredCursorVisible = false;
        }
        if (saveAsMode && !blockingContentFocused) {
            Point saveAsCursor = saveAsBarPlacement == null
                ? null
                : saveAsBarPlacement.cursorPosition(saveAsFocusManager).orElse(null);
            if (saveAsCursor == null) {
                desiredCursorVisible = false;
            } else {
                desiredCursorRow = saveAsCursor.y() + 1;
                desiredCursorCol = saveAsCursor.x() + 1;
            }
        } else if (searchOwnsCursor) {
            if (searchCursor == null) {
                desiredCursorVisible = false;
            } else {
                desiredCursorRow = searchCursor.y() + 1;
                desiredCursorCol = searchCursor.x() + 1;
            }
        } else if (topMenuOpen) {
            Point menuCursor = topMenuPlacement.cursorPosition().orElse(null);
            if (menuCursor == null) {
                desiredCursorVisible = false;
            } else {
                desiredCursorRow = menuCursor.y() + 1;
                desiredCursorCol = menuCursor.x() + 1;
            }
        } else if (helpOverlay != null && !blockingContentFocused) {
            Button close = helpOverlay.close();
            desiredCursorRow = Math.min(rows, close.row() + 1);
            desiredCursorCol = Math.min(cols, close.cursorCol() + 1);
        }

        boolean canUseVerticalScrollFastPath = composition.allowsScrollHints()
            && previousPlan != null
            && plan.wrapLines() == previousPlan.wrapLines()
            && plan.gutterWidth() == previousPlan.gutterWidth()
            && plan.scrollbarWidth() == previousPlan.scrollbarWidth()
            && plan.leftVisualColumn() == previousPlan.leftVisualColumn()
            && plan.topLine() != previousPlan.topLine();
        boolean canUseHorizontalScrollFastPath = composition.allowsScrollHints()
            && previousPlan != null
            && !plan.wrapLines()
            && !state.hasSelection()
            && plan.wrapLines() == previousPlan.wrapLines()
            && plan.gutterWidth() == previousPlan.gutterWidth()
            && plan.scrollbarWidth() == previousPlan.scrollbarWidth()
            && plan.topLine() == previousPlan.topLine()
            && plan.leftVisualColumn() != previousPlan.leftVisualColumn();
        int horizontalDeltaColumns = previousPlan == null ? 0 : plan.leftVisualColumn() - previousPlan.leftVisualColumn();
        int diffRegionTop = contentTopRow;
        int diffRegionHeight = plan.contentRows();
        int textStartCol = plan.gutterWidth();
        int textEndCol = cols - plan.scrollbarWidth() - 1;
        FrameComposition.OpenTreeDiffKey openTreeDiffKey = openFile.diffKey();
        if (openFileMode && openFileTreePlacement != null) {
            Rect treeContent = openFileTreePlacement.contentBounds();
            diffRegionTop = treeContent.y();
            diffRegionHeight = treeContent.height();
            textStartCol = treeContent.x();
            textEndCol = treeContent.rightExclusive() - 1;
            textStartCol = Math.max(0, Math.min(cols - 1, textStartCol));
            textEndCol = Math.max(textStartCol, Math.min(cols - 1, textEndCol));
            canUseVerticalScrollFastPath = composition.allowsScrollHints()
                && previousPlan != null
                && openTreeDiffKey != null
                && openTreeDiffKey.isVerticalScrollFrom(lastOpenTreeDiffKey);
            canUseHorizontalScrollFastPath = false;
            horizontalDeltaColumns = 0;
        }

        int regionLeft = Math.max(0, Math.min(cols, textStartCol));
        int regionRight = Math.max(regionLeft, Math.min(cols, textEndCol + 1));
        int regionTop = Math.max(0, Math.min(rows, diffRegionTop));
        int regionBottom = Math.max(regionTop, Math.min(rows, diffRegionTop + diffRegionHeight));
        Rect diffRegion = new Rect(
            regionLeft,
            regionTop,
            regionRight - regionLeft,
            regionBottom - regionTop
        );
        ScrollHint scrollHint = ScrollHint.none();
        if (!diffRegion.isEmpty()) {
            if (canUseHorizontalScrollFastPath && horizontalDeltaColumns != 0) {
                scrollHint = new ScrollHint.Horizontal(diffRegion, horizontalDeltaColumns);
            } else if (canUseVerticalScrollFastPath) {
                scrollHint = new ScrollHint.Vertical(diffRegion);
            }
        }

        Point cursorPosition = new Point(
            Math.max(0, Math.min(cols - 1, desiredCursorCol - 1)),
            Math.max(0, Math.min(rows - 1, desiredCursorRow - 1))
        );
        Frame frame = new Frame(
            current,
            new ScreenCursor(cursorPosition, desiredCursorVisible),
            scrollHint
        );
        ScreenPresenter.PresentResult presentResult = screenPresenter.present(frame, profileEnabled);
        if (profileEnabled) {
            frameProfiler.diffNanos = presentResult.patchNanos();
        }
        lastOpenTreeDiffKey = openTreeDiffKey;
        return new RenderFrameResult(plan, true);
    }

    private String resolveRenderSearchQuery(
        boolean searchMode,
        String searchQuery,
        TextModel model,
        EditorState state,
        boolean autoSelectionHighlightSetting,
        boolean mouseSelectionInProgress
    ) {
        if (searchMode) {
            return searchQuery;
        }
        if (!autoSelectionHighlightSetting) {
            return null;
        }
        if (mouseSelectionInProgress) {
            return null;
        }
        if (!state.hasSelection()) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a == null || b == null || a.line() != b.line()) {
            return null;
        }
        if (a.col() > b.col()) {
            Position tmp = a;
            a = b;
            b = tmp;
        }
        int length = b.col() - a.col();
        if (length < AUTO_SELECTION_HIGHLIGHT_MIN_CHARS || length > AUTO_SELECTION_HIGHLIGHT_MAX_CHARS) {
            return null;
        }
        String selected = model.getTextRange(a, b);
        if (selected.isBlank() || selected.indexOf('\n') >= 0) {
            return null;
        }
        return selected;
    }

    String selectionQueryForCtrlF(TextModel model, EditorState state) {
        if (!state.hasSelection()) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a == null || b == null || a.line() != b.line()) {
            return null;
        }
        if (a.col() > b.col()) {
            Position tmp = a;
            a = b;
            b = tmp;
        }
        int length = b.col() - a.col();
        if (length <= 0 || length > CTRL_F_SELECTION_MAX_CHARS) {
            return null;
        }
        String selected = model.getTextRange(a, b);
        return selected;
    }

    boolean focusCurrentMatchIfOffscreen(
        TextModel model,
        EditorState state,
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords,
        String query,
        SearchOptions options
    ) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        if (!searchSelectionMatchesQuery(model, state, query, options)) {
            return false;
        }
        int topBefore = state.getTopLine();
        int wrapBefore = state.getTopWrapSegment();
        int leftBefore = state.getLeftVisualColumn();
        EditorViewNavigation.ensureCursorVisible(engine, contentRows, contentCols, wrapLines, wrapByWords);
        return topBefore != state.getTopLine()
            || wrapBefore != state.getTopWrapSegment()
            || leftBefore != state.getLeftVisualColumn();
    }

    private boolean searchSelectionMatchesQuery(
        TextModel model,
        EditorState state,
        String query,
        SearchOptions options
    ) {
        if (!state.hasSelection()) {
            return false;
        }
        Position start = state.getSelectionAnchor();
        Position end = state.getCursor();
        if (start == null || end == null || start.line() != end.line()) {
            return false;
        }
        if (start.col() > end.col()) {
            Position temporary = start;
            start = end;
            end = temporary;
        }
        SearchMatcher matcher = SearchMatcher.compile(query, options);
        return matcher.matchesRange(model.getLine(start.line()), start.col(), end.col());
    }

    final class RenderFrameController {
        private final RenderRasterizer renderer;
        private final EditorView editorView;
        private final boolean colorEnabled;
        private final ScreenPresenter screenPresenter;

        RenderFrameController(
            RenderRasterizer renderer,
            EditorView editorView,
            boolean colorEnabled,
            ScreenPresenter screenPresenter
        ) {
            this.renderer = renderer;
            this.editorView = editorView;
            this.colorEnabled = colorEnabled;
            this.screenPresenter = screenPresenter;
        }

        RenderFrameResult renderFrameIfNeeded(FrameComposition composition) {
            return renderFrame(composition, this);
        }
    }

    String buildOverwritePrompt(Path path) {
        return "Overwrite existing file: " + dev.tuink.tui.ui.PathPresentation.name(path) + " ? (y/N)";
    }

    ConfirmBarView buildQuitConfirmBar(
        List<EditorTab> tabs,
        int cols
    ) {
        List<EditorTab> dirtyTabs = new ArrayList<>();
        List<Path> dirtyPaths = new ArrayList<>();
        for (EditorTab tab : tabs) {
            if (tab.state().isDirty()) {
                dirtyTabs.add(tab);
                dirtyPaths.add(tab.state().getCurrentFile());
            }
        }
        boolean multiple = dirtyTabs.size() > 1;
        String primaryLabel;
        if (multiple) {
            primaryLabel = "Save All";
        } else if (dirtyTabs.size() == 1 && requiresSaveAs(dirtyTabs.get(0))) {
            primaryLabel = "Save As";
        } else {
            primaryLabel = "Save";
        }
        String secondaryLabel = multiple ? "Discard All" : "Discard";
        int promptWidth = ConfirmBarView.promptWidth(
            cols,
            primaryLabel,
            secondaryLabel,
            true,
            "Cancel"
        );
        return ConfirmBarView.build(
            DirtyTabSummary.build(dirtyPaths, promptWidth),
            cols,
            primaryLabel,
            secondaryLabel,
            true,
            "Cancel"
        );
    }

    ConfirmBarView buildCloseTabConfirmBar(
        List<EditorTab> tabs,
        int closeTabIndex,
        int cols
    ) {
        EditorTab pendingTab = closeTabIndex >= 0 && closeTabIndex < tabs.size()
            ? tabs.get(closeTabIndex)
            : null;
        String primaryLabel = pendingTab != null && requiresSaveAs(pendingTab) ? "Save As" : "Save";
        return ConfirmBarView.build(
            buildCloseTabPrompt(tabs, closeTabIndex),
            cols,
            primaryLabel,
            "Discard",
            true,
            "Cancel"
        );
    }

    String buildRecoveryPrompt(int count) {
        return "Recovery snapshots found: " + count + ". Restore now? (Y/n)";
    }

    private String buildCloseTabPrompt(List<EditorTab> tabs, int closeTabIndex) {
        if (closeTabIndex < 0 || closeTabIndex >= tabs.size()) {
            return "Close modified tab? (y/N)";
        }
        Path path = tabs.get(closeTabIndex).state().getCurrentFile();
        String name = path == null ? "--no name--" : dev.tuink.tui.ui.PathPresentation.name(path);
        return "Close modified tab: " + name + " ? (y/N)";
    }

    boolean requiresSaveAs(EditorTab tab) {
        if (tab == null) {
            return false;
        }
        Path file = tab.state().getCurrentFile();
        return file == null || isReadOnlyFile(file);
    }

    void prepareSaveAs(
        EditorTab tab,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification
    ) {
        Path file = tab.state().getCurrentFile();
        String suggestion = file == null
            ? saveAsSuggestions.suggestionFor(tab.tabSession(), null, DEFAULT_FILE_NAME)
            : saveAsSuggestions.suggestionFor(
                tab.tabSession(),
                null,
                suggestWritableName(file),
                this::isSuitableSaveAsDraft
            );
        saveAsInput.set(suggestion);
        saveAsSuggestions.rememberDraft(tab.tabSession(), suggestion);
        notification.clear();
    }

    private boolean isSuitableSaveAsDraft(String draft) {
        try {
            Path path = Path.of(draft);
            return validateSaveTarget(path) == null && !isReadOnlyFile(path);
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    SaveFlowProgress continueQuitSaveFlow(
        SessionContext sessionContext,
        List<EditorTab> tabs,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification
    ) {
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            if (tab.state().isDirty() && requiresSaveAs(tab)) {
                sessionContext.activate(i);
                prepareSaveAs(tab, saveAsInput, saveAsSuggestions, notification);
                return new SaveFlowProgress(true, UiModalState.saveAsForQuit());
            }
        }
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            if (!tab.state().isDirty()) {
                continue;
            }
            if (!saveDirtyTab(tab, notification, saveAsSuggestions)) {
                sessionContext.activate(i);
                return new SaveFlowProgress(true, UiModalState.quitConfirm());
            }
        }
        return new SaveFlowProgress(false, UiModalState.none());
    }

    SaveFlowProgress continueAfterSuccessfulSave(
        UiModalState completedSaveState,
        SessionContext sessionContext,
        List<EditorTab> tabs,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        NotificationState notification
    ) {
        if (completedSaveState.saveContinuation() == SaveContinuation.QUIT) {
            return continueQuitSaveFlow(
                sessionContext,
                tabs,
                saveAsInput,
                saveAsSuggestions,
                notification
            );
        }
        if (completedSaveState.saveContinuation() == SaveContinuation.CLOSE_TAB) {
            int closeIndex = completedSaveState.pendingCloseTabIndex();
            if (closeIndex >= 0 && closeIndex < tabs.size()) {
                sessionContext.activate(
                    closeTab(
                        tabs,
                        sessionContext.activeTabIndex(),
                        closeIndex,
                        saveAsSuggestions
                    )
                );
            }
        }
        return new SaveFlowProgress(true, UiModalState.none());
    }

    boolean saveDirtyTab(
        EditorTab tab,
        NotificationState notification,
        SaveAsSuggestionStore saveAsSuggestions
    ) {
        if (tab == null || !tab.state().isDirty()) {
            return true;
        }
        Path file = tab.state().getCurrentFile();
        if (file == null) {
            notification.error("Save failed: tab has no file path. Use Save As.");
            return false;
        }
        if (isReadOnlyFile(file)) {
            notification.error(readOnlySaveAsMessage(file));
            return false;
        }
        try {
            tab.engine().save();
            saveAsSuggestions.rememberSavedPath(tab.tabSession(), file);
            return true;
        } catch (IOException e) {
            notification.error(saveErrorMessage(e));
            return false;
        }
    }

    int restoreRecoveredTabs(
        List<RecoveryStore.Entry> entries,
        List<EditorTab> tabs,
        SessionContext sessionContext,
        RecoveryAutosaveManager recoveryAutosave,
        long nowNanos
    ) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        List<EditorTab> restored = new ArrayList<>();
        for (RecoveryStore.Entry entry : entries) {
            if (entry == null || entry.snapshot() == null) {
                continue;
            }
            TuiDocumentSession documentSession = TuiDocumentSession.create(clipboard);
            TabSession tabSession = documentSession.view();
            tabSession.engine().restoreRecoverySnapshot(entry.snapshot());
            RenderPlanBuilder builder = new RenderPlanBuilder(
                resolveHighlighter(tabSession.state().getCurrentFile()),
                tabSession.textLayout()
            );
            // Keep the already committed startup snapshot as the durable backing
            // for this tab. The next autosave updates the same entry instead of
            // creating a gap in which neither the old nor a new snapshot exists.
            recoveryAutosave.adoptSnapshot(tabSession, entry.id(), nowNanos);
            EditorTab tab = new EditorTab(documentSession, builder);
            tab.viewportController().onRecoveredViewport();
            restored.add(tab);
        }
        if (restored.isEmpty()) {
            return 0;
        }
        tabs.clear();
        tabs.addAll(restored);
        sessionContext.activate(0);
        return restored.size();
    }

    String savedMessage(EditorState state) {
        return "Saved " + state.getCurrentFile() + " [" + state.getFileEncoding().displayName() + "]";
    }

    String saveErrorMessage(IOException e) {
        if (e instanceof DocumentEncodingException encoding) {
            return "Save failed: " + encoding.charsetName() + " cannot encode U+"
                + String.format(Locale.ROOT, "%04X", encoding.codePoint())
                + ". Copy to a new UTF-8 tab.";
        }
        String msg = e.getMessage() == null ? "Unknown I/O error" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return "Save failed: permission denied";
        }
        if (lower.contains("no space") || lower.contains("not enough space")) {
            return "Save failed: no space left on device";
        }
        if (lower.contains("is a directory")) {
            return "Save failed: target is a directory";
        }
        return "Save failed: " + msg;
    }

    private String readOnlySaveAsMessage(Path path) {
        return "File is read-only. Use Save As. " + path;
    }

    String validateSaveTarget(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            if (!Files.exists(parent)) {
                return "Save failed: directory does not exist: " + parent;
            }
            if (!Files.isDirectory(parent)) {
                return "Save failed: parent path is not a directory: " + parent;
            }
            if (!Files.isWritable(parent)) {
                return "Save failed: no write access to directory: " + parent;
            }
        }
        if (Files.isDirectory(normalized)) {
            return "Save failed: target is a directory: " + normalized;
        }
        return null;
    }

    boolean isReadOnlyFile(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.exists(path) && !Files.isWritable(path);
        } catch (SecurityException e) {
            return true;
        }
    }

    boolean hasDirtyTabs(List<EditorTab> tabs) {
        return TabSessions.hasDirtyTabs(toTabSessions(tabs));
    }

    String suggestWritableName(Path path) {
        String fileName = path.getFileName() == null ? DEFAULT_FILE_NAME : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        String candidate = base + "-copy" + ext;
        Path parent = path.getParent();
        Path preferred = parent == null ? Path.of(candidate) : parent.resolve(candidate);
        return SaveTargetSuggester.firstAvailable(preferred, Files::exists).toString();
    }

    boolean isSameFilePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    int countSearchMatches(TextModel model, List<EditorTab> tabs, String query, SearchOptions options) {
        if (query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        if (tabs != null) {
            return TabSearch.countMatches(toTabSessions(tabs), query, safe);
        }
        if (model == null) {
            return 0;
        }
        return TabSearch.countMatchesInModel(model, query, safe);
    }

    long searchSourceIdentity(TextModel model, List<EditorTab> tabs, boolean allTabs) {
        if (!allTabs) {
            return model == null ? 0L : model.cacheIdentity();
        }
        long hash = 1469598103934665603L;
        for (EditorTab tab : tabs) {
            hash ^= tab.model().cacheIdentity();
            hash *= 1099511628211L;
        }
        hash ^= tabs.size();
        return hash;
    }

    long searchSourceRevision(TextModel model, List<EditorTab> tabs, boolean allTabs) {
        if (!allTabs) {
            return model == null ? 0L : model.revision();
        }
        long hash = 1469598103934665603L;
        for (EditorTab tab : tabs) {
            hash ^= tab.model().cacheIdentity();
            hash *= 1099511628211L;
            hash ^= tab.model().revision();
            hash *= 1099511628211L;
        }
        hash ^= tabs.size();
        return hash;
    }

    private record HelpDialogLayout(int top, int left, int width, int height) {
    }

    record HelpOverlayPlacement(
        HelpPage page,
        ListView.Placement content,
        String footer,
        HelpDialogLayout layout,
        Rect viewport,
        PlacedModal frame,
        Button close,
        FocusScope<HelpOverlayInputRouter.Target, Button.Action> focusScope
    ) {
    }

    record RenderFrameResult(
        RenderPlan plan,
        boolean rendered
    ) {
    }

    EditorTab newEditorTab() {
        TuiDocumentSession session = TuiDocumentSession.create(clipboard);
        RenderPlanBuilder builder = new RenderPlanBuilder(RegexHighlighter.withDefaults(), session.view().textLayout());
        return new EditorTab(session, builder);
    }

    private int switchTab(List<EditorTab> tabs, int currentIndex, int delta) {
        return TabSessions.switchByDelta(tabs.size(), currentIndex, delta);
    }

    private int closeCurrentTab(List<EditorTab> tabs, int activeTabIndex, SaveAsSuggestionStore saveAsSuggestions) {
        int size = tabs.size();
        saveAsSuggestions.remove(tabs.get(activeTabIndex).tabSession());
        if (tabs.size() <= 1) {
            tabs.set(0, newEditorTab());
            return 0;
        }
        tabs.remove(activeTabIndex);
        return TabSessions.closeIndex(size, activeTabIndex, activeTabIndex);
    }

    int closeTab(
        List<EditorTab> tabs,
        int activeTabIndex,
        int closeTabIndex,
        SaveAsSuggestionStore saveAsSuggestions
    ) {
        int size = tabs.size();
        saveAsSuggestions.remove(tabs.get(closeTabIndex).tabSession());
        if (tabs.size() <= 1) {
            tabs.set(0, newEditorTab());
            return 0;
        }
        tabs.remove(closeTabIndex);
        return TabSessions.closeIndex(size, activeTabIndex, closeTabIndex);
    }

    List<TabSession> toTabSessions(List<EditorTab> tabs) {
        List<TabSession> sessions = new ArrayList<>(tabs.size());
        for (EditorTab tab : tabs) {
            sessions.add(tab.tabSession());
        }
        return sessions;
    }

    private long tabsRevisionFingerprint(List<EditorTab> tabs) {
        long hash = 1469598103934665603L;
        for (EditorTab tab : tabs) {
            hash ^= tab.model().revision();
            hash *= 1099511628211L;
        }
        return hash;
    }

    List<TabBarView.TabItem> toTabItems(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TabBarView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TabBarView.TabItem(
                tab.state().getCurrentFile(),
                tab.state().isDirty(),
                hasMatch
            ));
        }
        return out;
    }

    private boolean[] findTabsWithMatches(List<EditorTab> tabs, String query, SearchOptions options) {
        boolean[] flags = new boolean[tabs.size()];
        if (query == null || query.isEmpty()) {
            return flags;
        }
        int[] match = TabSearch.findMatchTabs(toTabSessions(tabs), query, options);
        for (int i = 0; i < flags.length && i < match.length; i++) {
            flags[i] = match[i] != 0;
        }
        return flags;
    }

    boolean[] resolveTabMatchFlags(
        List<EditorTab> tabs,
        boolean searchMode,
        String query,
        SearchOptions options,
        TabMatchCache cache
    ) {
        if (!searchMode || query == null || query.isEmpty()) {
            return new boolean[tabs.size()];
        }
        long revisionFingerprint = tabsRevisionFingerprint(tabs);
        return cache.resolve(tabs, query, options, revisionFingerprint, () -> findTabsWithMatches(tabs, query, options));
    }

    List<TopMenuView.TabItem> toTopMenuTabs(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TopMenuView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TopMenuView.TabItem(
                tab.state().getCurrentFile(),
                tab.state().isDirty(),
                hasMatch
            ));
        }
        return out;
    }

    record EditorTab(
        TuiDocumentSession documentSession,
        RenderPlanBuilder renderPlanBuilder,
        ViewportController viewportController,
        EditorViewNavigation navigation
    ) {
        EditorTab(TuiDocumentSession documentSession, RenderPlanBuilder renderPlanBuilder) {
            this(documentSession, renderPlanBuilder, new ViewportController(), new EditorViewNavigation(documentSession.view().engine()));
        }

        EditorTab {
            if (documentSession == null
                || renderPlanBuilder == null
                || viewportController == null
                || navigation == null || navigation.engine() != documentSession.view().engine()
                || renderPlanBuilder.textLayout() != documentSession.view().textLayout()) {
                throw new IllegalArgumentException("tab components must share one textLayout");
            }
        }

        TabSession tabSession() {
            return documentSession.view();
        }

        private TextModel model() {
            return tabSession().model();
        }

        EditorState state() {
            return tabSession().state();
        }

        private EditorEngine engine() {
            return tabSession().engine();
        }

        private EditorTextLayout textLayout() {
            return tabSession().textLayout();
        }

        private EditorTab withRenderPlanBuilder(RenderPlanBuilder nextBuilder) {
            return new EditorTab(documentSession, nextBuilder, viewportController, navigation);
        }
    }

    static final class TabMatchCache {
        private String query;
        private SearchOptions options;
        private long tabsFingerprint = Long.MIN_VALUE;
        private boolean[] flags = new boolean[0];

        private boolean[] resolve(
            List<EditorTab> tabs,
            String query,
            SearchOptions options,
            long tabsFingerprint,
            java.util.function.Supplier<boolean[]> compute
        ) {
            if (this.query != null
                && this.query.equals(query)
                && this.options != null
                && this.options.equals(options)
                && this.tabsFingerprint == tabsFingerprint
                && this.flags.length == tabs.size()) {
                return this.flags;
            }
            this.query = query;
            this.options = options;
            this.tabsFingerprint = tabsFingerprint;
            this.flags = compute.get();
            return this.flags;
        }
    }

    static final class SessionContext {
        private final List<EditorTab> tabs;
        private int activeTabIndex;
        private TextModel model;
        private EditorState state;
        private EditorEngine engine;
        private RenderPlanBuilder renderPlanBuilder;

        SessionContext(List<EditorTab> tabs, int activeTabIndex) {
            this.tabs = tabs;
            activate(activeTabIndex);
        }

        void activate(int nextActiveTabIndex) {
            this.activeTabIndex = Math.max(0, Math.min(nextActiveTabIndex, tabs.size() - 1));
            EditorTab active = tabs.get(this.activeTabIndex);
            this.model = active.model();
            this.state = active.state();
            this.engine = active.engine();
            this.renderPlanBuilder = active.renderPlanBuilder();
        }

        private void addNewTabAndActivate(EditorTab tab) {
            tabs.add(tab);
            activate(tabs.size() - 1);
        }

        void replaceActiveRenderPlanBuilder(RenderPlanBuilder nextRenderPlanBuilder) {
            tabs.set(activeTabIndex, tabs.get(activeTabIndex).withRenderPlanBuilder(nextRenderPlanBuilder));
            this.renderPlanBuilder = nextRenderPlanBuilder;
        }

        int activeTabIndex() {
            return activeTabIndex;
        }

        TextModel model() {
            return model;
        }

        EditorState state() {
            return state;
        }

        EditorEngine engine() {
            return engine;
        }

        RenderPlanBuilder renderPlanBuilder() {
            return renderPlanBuilder;
        }
    }

    final class NotificationState {
        private enum Kind {
            SUCCESS,
            INFO,
            WARNING,
            ERROR
        }

        String message;
        private Kind kind;
        private long expiresAtNanos;

        private boolean visible() {
            return message != null;
        }

        void success(String message) {
            this.message = message;
            this.kind = Kind.SUCCESS;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        void info(String message) {
            this.message = message;
            this.kind = Kind.INFO;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        void warning(String message) {
            this.message = message;
            this.kind = Kind.WARNING;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        void error(String message) {
            this.message = message;
            this.kind = Kind.ERROR;
            this.expiresAtNanos = 0L;
        }

        void clearErrorOnUserAction() {
            if (kind == Kind.ERROR) {
                clear();
            }
        }

        void clear() {
            this.message = null;
            this.kind = null;
            this.expiresAtNanos = 0L;
        }

        CellStyle style() {
            if (kind == null) {
                return null;
            }
            return switch (kind) {
                case SUCCESS -> styles.notifySuccess();
                case INFO -> styles.notifyInfo();
                case WARNING -> styles.notifyWarning();
                case ERROR -> styles.notifyError();
            };
        }

        boolean expireIfNeeded() {
            if (message != null && expiresAtNanos > 0L && System.nanoTime() >= expiresAtNanos) {
                clear();
                return true;
            }
            return false;
        }

        long nanosUntilExpire(long nowNanos) {
            if (message == null || expiresAtNanos <= 0L) {
                return -1L;
            }
            return Math.max(0L, expiresAtNanos - nowNanos);
        }
    }

    LineHighlighter resolveHighlighter(Path file) {
        return file == null ? RegexHighlighter.withDefaults() : RegexHighlighter.forFileName(file.getFileName().toString());
    }

    static void runWithRecovery(
        TuiEventLoop<TuiInputEvent> eventLoop,
        TuiApplicationRunner.Frontend<TuiInputEvent> frontend,
        RecoveryAutosaveManager recovery,
        Supplier<List<TabSession>> tabs,
        Consumer<String> reportWarning
    ) throws IOException {
        boolean completed = false;
        Throwable failure = null;
        try {
            new TuiApplicationRunner<>(eventLoop).run(frontend);
            completed = true;
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            // Only the frontend can authorize leaving unsaved edits behind.
            // EOF, external interrupts and failures stop the runtime independently.
            try {
                String warning = recovery.finishSession(tabs.get(), completed && !frontend.running());
                if (warning != null) {
                    reportWarning.accept(warning);
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    interface RecoveryPersistence {
        void save(String entryId, RecoverySnapshot snapshot) throws IOException;

        void delete(String entryId) throws IOException;
    }

    static final class RecoveryAutosaveManager {
        private final RecoveryPersistence store;
        private final LongSupplier nanoClock;
        private final Map<TabSession, RuntimeRecoveryState> runtime = new IdentityHashMap<>();
        private final Map<String, Long> pendingDeletes = new LinkedHashMap<>();
        private long lastErrorNotifyAtNanos = Long.MIN_VALUE;

        private RecoveryAutosaveManager(RecoveryStore store) {
            this(new RecoveryPersistence() {
                @Override
                public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
                    store.save(entryId, snapshot);
                }

                @Override
                public void delete(String entryId) throws IOException {
                    store.delete(entryId);
                }
            });
        }

        RecoveryAutosaveManager(RecoveryPersistence store) {
            this(store, System::nanoTime);
        }

        RecoveryAutosaveManager(RecoveryPersistence store, LongSupplier nanoClock) {
            if (store == null) {
                throw new IllegalArgumentException("store must not be null");
            }
            this.store = store;
            this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        }

        void syncTabs(List<TabSession> tabs) {
            Map<TabSession, Boolean> active = new IdentityHashMap<>();
            if (tabs != null) {
                for (TabSession session : tabs) {
                    if (session == null) {
                        continue;
                    }
                    active.put(session, Boolean.TRUE);
                    runtime.computeIfAbsent(
                        session,
                        key -> new RuntimeRecoveryState(UUID.randomUUID().toString())
                    );
                }
            }
            var iterator = runtime.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TabSession, RuntimeRecoveryState> entry = iterator.next();
                if (!active.containsKey(entry.getKey())) {
                    queueDelete(entry.getValue().entryId());
                    iterator.remove();
                }
            }
        }

        void adoptSnapshot(TabSession session, String entryId, long nowNanos) {
            if (session == null || entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("session and recovery entryId are required");
            }
            RuntimeRecoveryState adopted = new RuntimeRecoveryState(entryId);
            adopted.markSaved(session.model().revision(), nowNanos);
            RuntimeRecoveryState replaced = runtime.put(session, adopted);
            pendingDeletes.remove(entryId);
            if (replaced != null && !entryId.equals(replaced.entryId())) {
                queueDelete(replaced.entryId());
            }
        }

        String autosaveDirtyTabs(List<TabSession> tabs, long nowNanos) {
            return autosaveDirtyTabs(tabs, nowNanos, false);
        }

        private String autosaveDirtyTabs(List<TabSession> tabs, long nowNanos, boolean force) {
            String warning = retryPendingDeletes(nowNanos, force);
            if (tabs == null || tabs.isEmpty()) {
                return warning;
            }
            for (TabSession session : tabs) {
                if (session == null) {
                    continue;
                }
                RuntimeRecoveryState state = runtime.computeIfAbsent(
                    session,
                    key -> new RuntimeRecoveryState(UUID.randomUUID().toString())
                );
                if (!session.state().isDirty()) {
                    if (state.hasSnapshot()) {
                        if (!force && !state.cleanupDue(nowNanos)) {
                            continue;
                        }
                        state.markCleanupAttempt(nowNanos);
                        try {
                            store.delete(state.entryId());
                            pendingDeletes.remove(state.entryId());
                            state.clearSnapshotState();
                        } catch (IOException e) {
                            warning = firstNonNull(
                                warning,
                                failureWarning("Recovery cleanup failed", e, nowNanos, force)
                            );
                        }
                    }
                    continue;
                }

                long revision = session.model().revision();
                if (!force && state.hasSnapshot() && revision == state.lastRevision()) {
                    continue;
                }
                if (!force && !state.saveDue(nowNanos)) {
                    continue;
                }
                state.markSaveAttempt(nowNanos);
                try {
                    store.save(state.entryId(), session.engine().captureRecoverySnapshot());
                    state.markSaved(revision, nowNanos);
                } catch (IOException e) {
                    warning = firstNonNull(
                        warning,
                        failureWarning("Recovery autosave failed", e, nowNanos, force)
                    );
                }
            }
            return warning;
        }

        DeletionResult deleteEntries(List<RecoveryStore.Entry> entries) {
            if (entries == null || entries.isEmpty()) {
                return new DeletionResult(0, List.of(), List.of());
            }
            int deleted = 0;
            List<RecoveryStore.Entry> failed = new ArrayList<>();
            List<String> failureDetails = new ArrayList<>();
            for (RecoveryStore.Entry entry : entries) {
                if (entry == null) {
                    continue;
                }
                try {
                    store.delete(entry.id());
                    pendingDeletes.remove(entry.id());
                    deleted++;
                } catch (IOException e) {
                    failed.add(entry);
                    failureDetails.add(entry.id() + ": " + messageOf(e));
                }
            }
            return new DeletionResult(deleted, failed, failureDetails);
        }

        String finishSession(List<TabSession> tabs, boolean exitConfirmed) {
            syncTabs(tabs);
            if (exitConfirmed) {
                return clearRuntimeSnapshots();
            }
            // A final input event may have changed the document immediately
            // before EOF/interrupt. Flush it even inside the autosave debounce.
            return autosaveDirtyTabs(tabs, nanoClock.getAsLong(), true);
        }

        private String clearRuntimeSnapshots() {
            for (RuntimeRecoveryState state : runtime.values()) {
                queueDelete(state.entryId());
            }
            runtime.clear();
            return retryPendingDeletes(nanoClock.getAsLong(), true);
        }

        private void queueDelete(String entryId) {
            if (entryId == null || entryId.isBlank()) {
                return;
            }
            pendingDeletes.putIfAbsent(entryId, Long.MIN_VALUE);
        }

        private String retryPendingDeletes(long nowNanos, boolean force) {
            String warning = null;
            var iterator = pendingDeletes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> pending = iterator.next();
                if (!force && !delayElapsed(nowNanos, pending.getValue(), RECOVERY_AUTOSAVE_DEBOUNCE_NANOS)) {
                    continue;
                }
                pending.setValue(nowNanos);
                try {
                    store.delete(pending.getKey());
                    iterator.remove();
                } catch (IOException e) {
                    warning = firstNonNull(
                        warning,
                        failureWarning("Recovery cleanup failed", e, nowNanos, force)
                    );
                }
            }
            return warning;
        }

        private String failureWarning(String prefix, IOException error, long nowNanos, boolean force) {
            if (force) {
                return prefix + ": " + messageOf(error);
            }
            if (!delayElapsed(
                nowNanos,
                lastErrorNotifyAtNanos,
                RECOVERY_ERROR_NOTIFY_COOLDOWN_NANOS
            )) {
                return null;
            }
            lastErrorNotifyAtNanos = nowNanos;
            return prefix + ": " + messageOf(error);
        }

        private static boolean delayElapsed(long nowNanos, long previousNanos, long delayNanos) {
            return previousNanos == Long.MIN_VALUE || nowNanos - previousNanos >= delayNanos;
        }

        private static String firstNonNull(String current, String candidate) {
            return current != null ? current : candidate;
        }

        private static String messageOf(IOException error) {
            return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }

        record DeletionResult(
            int deletedCount,
            List<RecoveryStore.Entry> failedEntries,
            List<String> failureDetails
        ) {
            DeletionResult {
                failedEntries = List.copyOf(failedEntries);
                failureDetails = List.copyOf(failureDetails);
            }

            boolean successful() {
                return failedEntries.isEmpty();
            }

            String failureMessage() {
                if (successful()) {
                    return "";
                }
                return "Failed to discard " + failedEntries.size()
                    + " recovery snapshot(s); they were kept for retry or restore: "
                    + String.join("; ", failureDetails);
            }
        }
    }

    private static final class RuntimeRecoveryState {
        private final String entryId;
        private long lastRevision = Long.MIN_VALUE;
        private long lastSaveAttemptAtNanos = Long.MIN_VALUE;
        private long lastCleanupAttemptAtNanos = Long.MIN_VALUE;
        private boolean hasSnapshot;

        private RuntimeRecoveryState(String entryId) {
            this.entryId = entryId;
        }

        private String entryId() {
            return entryId;
        }

        private long lastRevision() {
            return lastRevision;
        }

        private boolean hasSnapshot() {
            return hasSnapshot;
        }

        private void markSaved(long revision, long nowNanos) {
            lastRevision = revision;
            lastSaveAttemptAtNanos = nowNanos;
            lastCleanupAttemptAtNanos = Long.MIN_VALUE;
            hasSnapshot = true;
        }

        private boolean saveDue(long nowNanos) {
            return RecoveryAutosaveManager.delayElapsed(
                nowNanos,
                lastSaveAttemptAtNanos,
                RECOVERY_AUTOSAVE_DEBOUNCE_NANOS
            );
        }

        private void markSaveAttempt(long nowNanos) {
            lastSaveAttemptAtNanos = nowNanos;
        }

        private boolean cleanupDue(long nowNanos) {
            return RecoveryAutosaveManager.delayElapsed(
                nowNanos,
                lastCleanupAttemptAtNanos,
                RECOVERY_AUTOSAVE_DEBOUNCE_NANOS
            );
        }

        private void markCleanupAttempt(long nowNanos) {
            lastCleanupAttemptAtNanos = nowNanos;
        }

        private void clearSnapshotState() {
            hasSnapshot = false;
            lastSaveAttemptAtNanos = Long.MIN_VALUE;
            lastCleanupAttemptAtNanos = Long.MIN_VALUE;
        }
    }

    boolean effectiveSyntaxHighlighting(boolean syntaxHighlightingSetting) {
        return syntaxHighlightingAvailable() && syntaxHighlightingSetting;
    }

    boolean syntaxHighlightingAvailable() {
        return theme != UiStyles.Theme.LIGHT;
    }

    private void applyTheme(UiStyles.Theme theme) {
        this.theme = theme == null ? UiStyles.Theme.CYAN : theme;
        this.styles = UiStyles.forTheme(this.theme);
    }

    private void printUsage(PrintStream output) {
        output.println("Usage: tuink [options] [file]");
        output.println("Development: ./gradlew --console=plain :app:run -PappArgs=\"[options] [file]\"");
        output.println("Options:");
        output.println("  --no-mouse       Disable mouse tracking and mouse interactions");
        output.println("  --no-alt-screen  Do not use alternate screen buffer");
        output.println("  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        output.println("  --theme=<name>   UI palette: blue, cyan (default), light");
        output.println("  --read           Start in reading mode (editing disabled)");
        output.println("  --profile        Log render timings to stderr every " + PROFILE_LOG_EVERY_FRAMES + " frames");
        output.println("  --profile-file   Create a new profile log file (also enables --profile)");
        output.println("  --help, -h       Show this help");
    }

    private boolean shouldForceExecTerminalProvider() {
        return "exec".equalsIgnoreCase(System.getProperty("dev.tuiframe.provider", ""));
    }

    final class FrameProfiler {
        private long frameStartNanos;
        long searchNanos;
        private long buildNanos;
        private long rasterNanos;
        private long diffNanos;
        private long totalNanos;
        private long frameIndex;
        private long stopwatchStartNanos;
        private PrintWriter out = new PrintWriter(System.err, true);
        private boolean closeOut;

        private void setOutput(PrintWriter out, boolean closeOut) {
            if (out == null) {
                return;
            }
            this.out = out;
            this.closeOut = closeOut;
        }

        void beginFrame() {
            frameStartNanos = System.nanoTime();
            stopwatchStartNanos = frameStartNanos;
            searchNanos = 0L;
            buildNanos = 0L;
            rasterNanos = 0L;
            diffNanos = 0L;
        }

        long stopwatchNanos() {
            long now = System.nanoTime();
            long delta = now - stopwatchStartNanos;
            stopwatchStartNanos = now;
            return delta;
        }

        void endFrame(boolean rendered) {
            if (!rendered) {
                return;
            }
            totalNanos = System.nanoTime() - frameStartNanos;
            frameIndex++;
            if (frameIndex % PROFILE_LOG_EVERY_FRAMES == 0) {
                log();
            }
        }

        private void log() {
            out.printf(
                Locale.ROOT,
                "frame=%d build=%.2fms raster=%.2fms diff=%.2fms search=%.2fms total=%.2fms%n",
                frameIndex,
                buildNanos / 1_000_000.0,
                rasterNanos / 1_000_000.0,
                diffNanos / 1_000_000.0,
                searchNanos / 1_000_000.0,
                totalNanos / 1_000_000.0
            );
            out.flush();
        }

        private void close() {
            if (closeOut) {
                out.close();
            }
        }
    }
}
