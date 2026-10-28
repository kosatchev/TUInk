package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.editor.session.recovery.RecoveryCoordinator;
import dev.tuink.editor.session.recovery.RecoveryParticipant;
import dev.tuink.editor.session.recovery.PreparedCheckpoint;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.EditorWorkspace;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.core.ViewportState;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.core.TabSessions;
import dev.tuink.core.TextModel;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.AppSettingsStore;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
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
import dev.tuink.render.SemanticHighlighterAdapter;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * JLine terminal application wiring core + render + highlighter.
 */
public class TerminalEditorApp {
    static final Object SAVE_AS_SCOPE_ID = new Object();
    static final Object SEARCH_SCOPE_ID = new Object();
    static final Object OPEN_FILE_SCOPE_ID = new Object();
    private static final long NOTIFY_DURATION_NANOS = 3_000_000_000L;
    static final long OPEN_DIALOG_DOUBLE_CLICK_NANOS = 450_000_000L;
    private static final int PROFILE_LOG_EVERY_FRAMES = 30;
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
        RecoveryStore recoveryStore;
        try {
            recoveryStore = new RecoveryStore();
        } catch (IOException error) {
            System.err.println("Cannot select settings/recovery profile: " + error.getMessage());
            return 1;
        }
        try (recoveryStore) {
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
        AppSettingsStore.LoadedSettings settingsSnapshot = null;
        try {
            settingsSnapshot = AppSettingsStore.loadSnapshot();
            loadedSettings = settingsSnapshot.settings();
        } catch (IOException e) {
            System.err.println("Warning: failed to load settings: " + e.getMessage());
        }

        AppSettingsSession settingsSession = new AppSettingsSession(loadedSettings, cli);
        AppSettings initialSettings = settingsSession.initialSettings();
        applyTheme(initialSettings.theme());
        SessionContext sessionContext = new SessionContext(List.of(newEditorTab()), 0);
        List<EditorTab> tabs = sessionContext.tabs();
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
        List<RecoveryStore.PendingSnapshot> startupRecoveryEntries = new ArrayList<>();
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
        Path initialOpenFileRoot = FilePickerOpenWorkflow.initialRoot(JvmDocumentStorage.pathOf(state.getCurrentResource()));
        try {
            RecoveryStore.DiscoveryResult recoveryLoad = recoveryStore.discover();
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
                var resource = JvmDocumentStorage.resource(file);
                var mode = cli.openAsUtf16Le() ? dev.tuink.editor.storage.DocumentOpenMode.UTF_16LE
                    : dev.tuink.editor.storage.DocumentOpenMode.DETECT;
                var open = new dev.tuink.editor.session.document.PreparedDocumentOpen(
                    resource, mode, !cli.openAsUtf16Le());
                tabs.get(activeTabIndex).documentSession().openPrepared(open.accept(open.read()));
                renderPlanBuilder = tabs.get(activeTabIndex).documentSession()
                    .newRenderPlanBuilder(resolveHighlighter(file));
                sessionContext.replaceActiveRenderPlanBuilder(renderPlanBuilder);
            } catch (IOException e) {
                String reason = e.getMessage();
                if (e instanceof dev.tuink.editor.storage.AmbiguousDocumentEncodingException) {
                    reason += " Use --open-as-utf16le to select UTF-16LE.";
                }
                notification.error("Open failed: " + reason);
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
                        new EditorWindowController.TerminalAccess(terminal::width, terminal::height, terminal::setMouseTracking),
                        topMenu,
                        uiSessionState,
                        settingsSession,
                        new DocumentIoController()
                    )),
                    recoveryAutosave,
                    () -> toDocumentSessions(tabs),
                    warning -> System.err.println("Warning: " + warning)
                );
            } finally {
                frameProfiler.close();
            }
        }

        AppSettings preferences = settingsSession.preferences(uiSessionState.settingsSnapshot(theme));
        if (settingsSnapshot != null && !preferences.equals(loadedSettings)) {
            try {
                AppSettingsStore.saveIfUnchanged(settingsSnapshot, preferences);
            } catch (AppSettingsStore.SettingsConflictException conflict) {
                System.err.println("Warning: " + conflict.getMessage());
            } catch (IOException e) {
                System.err.println("Warning: failed to save settings: " + e.getMessage());
            }
        }
    }

    void applyEditorActionResult(
        EditorActionController.Result editorAction,
        SessionContext sessionContext,
        List<EditorTab> tabs,
        int activeTabIndex,
        NotificationState notification,
        SaveAsSuggestionStore saveAsSuggestions,
        RenderRasterizer renderer
    ) {
        if (editorAction.clearNotification()) {
            notification.clear();
        }
        if (editorAction.notificationError() != null) {
            notification.error(editorAction.notificationError());
        }
        if (editorAction.requestToggleTheme()) {
            applyTheme(theme.next());
            renderer.setTheme(theme);
            notification.info("Theme: " + theme.label());
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
            case CLOSE_TAB -> closeTab(sessionContext, tabs.get(activeTabIndex).documentSession().viewId(), saveAsSuggestions);
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

    private void drawTopFileNameBold(
        CellBuffer buffer,
        Canvas canvas,
        int cols,
        TopMenuView.TopBar topBar
    ) {
        int startCol = topBar.filenameStart();
        if (buffer.rows() < 1 || cols <= startCol || startCol < 0) {
            return;
        }
        int endCol = Math.min(cols, topBar.filenameEnd());
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
        if (layers.openFile().visible()) {
            return rows < 8 || cols < 24
                ? new Rect(0, 0, 0, 0) : layers.openFile().dialog().frame().bounds();
        }
        if (layers.help() != null) {
            HelpOverlayPlacement overlay = layers.help();
            return overlay.viewport().height() < 8 || overlay.viewport().width() < 24
                ? new Rect(0, 0, 0, 0) : overlay.frame().bounds();
        }
        return blockingSurface(rows, cols, composition.document().layout().contentTopRow(),
            composition.surface().inlinePrompt().height(), layers.saveAsMode() || layers.confirm() != null);
    }

    Rect blockingSurface(int rows, int cols, int contentTop, int promptHeight, boolean inline) {
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
        OpenFileDialogView.Placement dialog = openFile.dialog();
        Theme childTheme = (colorEnabled ? colorFileTreeWidgetTheme : monoFileTreeWidgetTheme)
            .withSurface(styles.dialog(), highlighted ? blockingAccent(colorEnabled) : styles.dialogBorder());
        dialog.frame().paint(canvas, childTheme);
        String pathLine = " " + openFile.rootLabel();
        drawMenuLine(
            canvas,
            layout.top() + 1,
            layout.left() + 1,
            padRight(dev.tuink.tui.ui.PathPresentation.clip(pathLine, 0, Math.max(0, layout.width() - 2)),
                Math.max(0, layout.width() - 2)),
            styles.dialog()
        );
        FileTreeView.Placement treePlacement = openFile.placement();
        if (treePlacement != null) {
            treePlacement.paint(
                canvas,
                childTheme
            );
            dialog.paintControls(canvas, childTheme, openFile.focus(), contentFocused);
        }
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
        if (page == null) {
            resetHelpScroll();
            return null;
        }
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
        HelpOverlayPlacement overlay, TuiInputEvent event, WindowPointerCapture capture, Object surfaceId
    ) {
        if (overlay == null) {
            return new HelpOverlayInputRouter.Result(true, false);
        }
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

    void paintHelpOverlay(Canvas canvas, HelpOverlayPlacement overlay, boolean colorEnabled) {
        paintHelpOverlay(canvas, overlay, colorEnabled, false);
    }

    void paintHelpOverlay(
        Canvas canvas, HelpOverlayPlacement overlay, boolean colorEnabled, boolean highlighted
    ) {
        drawInfoOverlay(canvas, overlay, colorEnabled, highlighted);
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
        if (modalState.isSaveUtf8Confirm() || modalState.isOpenUtf16Confirm()) {
            return ConfirmBarInputRouter.Policy.overwrite();
        }
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
        int lineCols = model.lineLength(p.line());
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

    private String buildStateIndicators(EditorState state, boolean readingMode) {
        String ro = SaveTargetPolicy.isReadOnlyFile(JvmDocumentStorage.pathOf(state.getCurrentResource())) ? "RO" : "RW";
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
        HelpOverlayPlacement helpOverlay = layers.help();
        boolean colorEnabled = services.colorEnabled;
        ScreenPresenter screenPresenter = services.screenPresenter;
        RenderRasterizer renderer = services.renderer;
        EditorView editorView = services.editorView;
        RenderPlan plan;
        SearchOptions renderSearchOptions = searchMode
            ? search.options()
            : SearchOptions.DEFAULT;
        String renderSearchQuery = SearchSelection.highlightQuery(
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
        drawTopFileNameBold(current, canvas, cols, surface.topBar());
        drawOpenFileOverlay(
            canvas,
            rows,
            cols,
            openFile,
            colorEnabled,
            composition.layers().blockedInputHighlight(),
            composition.layers().contentFocused()
        );
        paintHelpOverlay(canvas, helpOverlay, colorEnabled, composition.layers().blockedInputHighlight());
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
                dirtyPaths.add(JvmDocumentStorage.pathOf(tab.state().getCurrentResource()));
            }
        }
        boolean multiple = dirtyTabs.size() > 1;
        String primaryLabel;
        if (multiple) {
            primaryLabel = "Save All";
        } else if (dirtyTabs.size() == 1 && SaveTargetPolicy.requiresSaveAs(JvmDocumentStorage.pathOf(dirtyTabs.get(0).state().getCurrentResource()))) {
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
        String primaryLabel = pendingTab != null && SaveTargetPolicy.requiresSaveAs(JvmDocumentStorage.pathOf(pendingTab.state().getCurrentResource())) ? "Save As" : "Save";
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
        return "Recovery snapshots: " + count + ". Restore now? (y/N)";
    }

    private String buildCloseTabPrompt(List<EditorTab> tabs, int closeTabIndex) {
        if (closeTabIndex < 0 || closeTabIndex >= tabs.size()) {
            return "Close modified tab? (y/N)";
        }
        Path path = JvmDocumentStorage.pathOf(tabs.get(closeTabIndex).state().getCurrentResource());
        String name = path == null ? "--no name--" : dev.tuink.tui.ui.PathPresentation.name(path);
        return "Close modified tab: " + name + " ? (y/N)";
    }

    record RecoveryRestoreResult(int restoredCount, List<RecoveryStore.PendingSnapshot> failedEntries,
                                 List<String> failureDetails) {}

    private record PreparedRecoveredTab(EditorTab tab, RecoveryAutosaveManager.RestoredSnapshot ownership) {}

    RecoveryRestoreResult restoreRecoveredTabs(
        List<? extends RecoveryStore.PendingSnapshot> entries,
        SessionContext sessionContext,
        RecoveryAutosaveManager recoveryAutosave,
        long nowNanos
    ) {
        List<EditorTab> tabs = sessionContext.tabs();
        if (entries == null || entries.isEmpty()) {
            return new RecoveryRestoreResult(0, List.of(), List.of());
        }
        List<PreparedRecoveredTab> prepared = new ArrayList<>();
        List<RecoveryStore.PendingSnapshot> failed = new ArrayList<>();
        List<String> failureDetails = new ArrayList<>();
        for (RecoveryStore.PendingSnapshot entry : entries) {
            if (entry == null) {
                continue;
            }
            try {
                RecoverySnapshot snapshot = entry.snapshot();
                if (snapshot == null) {
                    continue;
                }
                TuiDocumentSession documentSession = TuiDocumentSession.create(clipboard);
                TabSession tabSession = documentSession.view();
                documentSession.document().restoreRecovery(documentSession.viewId(), snapshot);
                tabSession.state().setViewport(snapshot.viewport());
                RenderPlanBuilder builder = documentSession.newRenderPlanBuilder(
                    resolveHighlighter(JvmDocumentStorage.pathOf(tabSession.state().getCurrentResource())));
                EditorTab tab = new EditorTab(documentSession, builder);
                tab.viewportController().onRecoveredViewport();
                prepared.add(new PreparedRecoveredTab(tab,
                    new RecoveryAutosaveManager.RestoredSnapshot(documentSession, entry.id())));
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                failed.add(entry);
                failureDetails.add(entry.id() + ": " + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }
        // Prepare result and ownership allocations before exposing any recovered
        // tab. A failed preparation cannot make a startup entry eligible for
        // deletion merely because its staged tab is absent from the live list.
        RecoveryRestoreResult result = new RecoveryRestoreResult(prepared.size(),
            List.copyOf(failed), List.copyOf(failureDetails));
        if (prepared.isEmpty()) {
            return result;
        }
        List<EditorTab> restored = prepared.stream().map(PreparedRecoveredTab::tab).toList();
        var ownership = recoveryAutosave.prepareRestoration(
            prepared.stream().map(PreparedRecoveredTab::ownership).toList(), nowNanos);
        boolean replacePlaceholder = tabs.size() == 1
            && WorkspaceDocuments.isPristinePlaceholder(tabs.getFirst().tabSession());
        sessionContext.addAll(restored);
        // The registry commit allocates nothing; activation can fail without
        // turning a published draft into an abandoned startup entry.
        recoveryAutosave.publishRestoration(ownership);
        if (replacePlaceholder) {
            sessionContext.workspace.remove(tabs.getFirst().documentSession().viewId());
        }
        sessionContext.workspace.select(restored.getFirst().documentSession().viewId());
        return result;
    }

    boolean hasDirtyTabs(List<EditorTab> tabs) {
        return TabSessions.hasDirtyTabs(toTabSessions(tabs));
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
        RenderPlanBuilder builder = session.newRenderPlanBuilder(resolveHighlighter(null));
        return new EditorTab(session, builder);
    }

    private int switchTab(List<EditorTab> tabs, int currentIndex, int delta) {
        return TabSessions.switchByDelta(tabs.size(), currentIndex, delta);
    }

    void closeTab(SessionContext context, ViewId viewId,
                  SaveAsSuggestionStore saveAsSuggestions) {
        var entry = context.workspace.find(viewId);
        if (entry == null) return;
        if (!context.canClose(viewId)) throw new IllegalStateException("Document is busy saving");
        // Allocate the replacement before retiring the last visible editor.
        EditorTab replacement = context.tabs().size() == 1 ? newEditorTab() : null;
        if (replacement != null) context.addAll(List.of(replacement));
        context.workspace.remove(viewId);
        saveAsSuggestions.remove(entry.attachment().tabSession());
        if (replacement != null) context.activate(replacement.documentSession().viewId());
    }

    List<TuiDocumentSession> toDocumentSessions(List<EditorTab> tabs) {
        return tabs.stream().map(EditorTab::documentSession).toList();
    }

    List<TabSession> toTabSessions(List<EditorTab> tabs) {
        List<TabSession> sessions = new ArrayList<>(tabs.size());
        for (EditorTab tab : tabs) {
            sessions.add(tab.tabSession());
        }
        return sessions;
    }

    List<TabBarView.TabItem> toTabItems(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TabBarView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TabBarView.TabItem(
                JvmDocumentStorage.pathOf(tab.state().getCurrentResource()),
                tab.state().isDirty(),
                hasMatch
            ));
        }
        return out;
    }

    List<TopMenuView.TabItem> toTopMenuTabs(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TopMenuView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TopMenuView.TabItem(
                JvmDocumentStorage.pathOf(tab.state().getCurrentResource()),
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
            this(documentSession, renderPlanBuilder, new ViewportController(), new EditorViewNavigation(documentSession.view().engine(), documentSession::navigate));
        }

        EditorTab {
            if (documentSession == null
                || renderPlanBuilder == null
                || viewportController == null
                || navigation == null || navigation.engine() != documentSession.view().engine()
                || renderPlanBuilder.textLayout() != documentSession.view().textLayout()
                || !renderPlanBuilder.documentId().equals(documentSession.document().documentId())) {
                throw new IllegalArgumentException("tab components must share one document identity and textLayout");
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

    static final class SessionContext {
        private final EditorWorkspace<EditorTab> workspace = new EditorWorkspace<>();

        SessionContext(List<EditorTab> tabs, int activeTabIndex) {
            addAll(tabs);
            activate(activeTabIndex);
        }

        void activate(int nextActiveTabIndex) {
            workspace.selectIndex(Math.max(0, Math.min(nextActiveTabIndex, tabs().size() - 1)));
        }

        void addNewTabAndActivate(EditorTab tab) {
            workspace.add(tab.documentSession().document(), tab.documentSession().viewId(), tab);
        }

        void addAll(List<EditorTab> tabs) {
            workspace.addAll(tabs.stream().map(tab ->
                new EditorWorkspace.Entry<>(
                    tab.documentSession().document(), tab.documentSession().viewId(), tab)).toList());
        }

        List<EditorTab> tabs() { return workspace.attachments(); }

        EditorTab active() { return workspace.active().attachment(); }

        ViewId activeViewId() { return workspace.active().viewId(); }

        int indexOf(ViewId viewId) { return workspace.indexOf(viewId); }

        void remove(ViewId viewId) { workspace.remove(viewId); }

        boolean hasOtherViews(ViewId viewId) {
            var entry = workspace.find(viewId);
            return entry != null && workspace.entries().stream()
                .anyMatch(other -> other.document() == entry.document() && !other.viewId().equals(viewId));
        }

        boolean canClose(ViewId viewId) {
            var entry = workspace.find(viewId);
            return entry != null && (!entry.document().io().busy() || hasOtherViews(viewId));
        }

        boolean activate(ViewId viewId) {
            if (workspace.find(viewId) == null) return false;
            workspace.select(viewId);
            return true;
        }

        void update(EditorTab tab) { workspace.updateAttachment(tab.documentSession().viewId(), tab); }

        void replace(int index, EditorTab replacement) {
            var old = tabs().get(index).documentSession().viewId();
            boolean selected = old.equals(activeViewId());
            addAll(List.of(replacement));
            workspace.remove(old);
            if (selected) workspace.select(replacement.documentSession().viewId());
        }

        void replaceActiveRenderPlanBuilder(RenderPlanBuilder nextRenderPlanBuilder) {
            update(active().withRenderPlanBuilder(nextRenderPlanBuilder));
        }

        int activeTabIndex() {
            return workspace.activeIndex();
        }

        TextModel model() {
            return active().model();
        }

        EditorState state() {
            return active().state();
        }

        EditorEngine engine() {
            return active().engine();
        }

        RenderPlanBuilder renderPlanBuilder() {
            return active().renderPlanBuilder();
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
        return new SemanticHighlighterAdapter(file == null ? RegexHighlighter.withDefaults()
            : RegexHighlighter.forFileName(file.getFileName().toString()));
    }

    static void runWithRecovery(
        TuiEventLoop<TuiInputEvent> eventLoop,
        TuiApplicationRunner.Frontend<TuiInputEvent> frontend,
        RecoveryAutosaveManager recovery,
        Supplier<List<TuiDocumentSession>> tabs,
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
                Runnable drain = frontend instanceof EditorWindowController controller
                    ? controller::closeDocumentIo : () -> {};
                finishRecoveryAfterIo(drain, recovery, tabs, completed && !frontend.running(), reportWarning);
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void finishRecoveryAfterIo(Runnable drain, RecoveryAutosaveManager recovery,
        Supplier<List<TuiDocumentSession>> tabs, boolean exitConfirmed, Consumer<String> reportWarning) {
        Throwable drainFailure = null;
        try {
            drain.run();
        } catch (RuntimeException | Error failure) {
            drainFailure = failure;
            throw failure;
        } finally {
            try {
                String warning = recovery.finishSession(tabs.get(), exitConfirmed && drainFailure == null);
                if (warning != null) reportWarning.accept(warning);
            } catch (RuntimeException | Error recoveryFailure) {
                if (drainFailure == null) throw recoveryFailure;
                drainFailure.addSuppressed(recoveryFailure);
            }
        }
    }

    interface RecoveryPersistence extends dev.tuink.editor.session.recovery.RecoveryPersistence<RecoverySnapshot> {}

    /** Terminal checkpoint envelope and lifecycle/executor adapter. Coordination lives in editor-session. */
    static final class RecoveryAutosaveManager {
        private final RecoveryCoordinator<RecoverySnapshot> coordinator;
        private final Map<EditorDocumentSession, TuiRecoveryParticipant> participants =
            new IdentityHashMap<>();
        private final ExecutorService ownedWorker;

        private RecoveryAutosaveManager(RecoveryStore store) {
            this(new RecoveryPersistence() {
                @Override public void save(String id, RecoverySnapshot snapshot) throws IOException { store.save(id, snapshot); }
                @Override public void delete(String id) throws IOException { store.delete(id); }
                @Override public void release(String id) throws IOException { store.release(id); }
            });
        }

        RecoveryAutosaveManager(RecoveryPersistence store) { this(store, System::nanoTime); }
        RecoveryAutosaveManager(RecoveryPersistence store, LongSupplier clock) {
            this(store, clock, newRecoveryWorker(), true);
        }
        RecoveryAutosaveManager(RecoveryPersistence store, Executor executor) { this(store, System::nanoTime, executor); }
        RecoveryAutosaveManager(RecoveryPersistence store, LongSupplier clock, Executor executor) {
            this(store, clock, executor, false);
        }
        private RecoveryAutosaveManager(RecoveryPersistence store, LongSupplier clock, Executor executor, boolean ownsWorker) {
            coordinator = new RecoveryCoordinator<>(store, executor, clock);
            ownedWorker = ownsWorker ? (ExecutorService) executor : null;
        }

        private static ExecutorService newRecoveryWorker() {
            return Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "tuink-recovery-writer");
                thread.setDaemon(true);
                return thread;
            });
        }

        private RecoveryParticipant<RecoverySnapshot> participant(TuiDocumentSession document) {
            TuiRecoveryParticipant participant = participants.computeIfAbsent(document.document(),
                ignored -> new TuiRecoveryParticipant(document));
            participant.source = document;
            return participant;
        }

        /** Retained document identity survives detaching or replacing its terminal projection. */
        private static final class TuiRecoveryParticipant implements RecoveryParticipant<RecoverySnapshot> {
            private TuiDocumentSession source;
            private TuiRecoveryParticipant(TuiDocumentSession source) { this.source = source; }
            @Override public DocumentId documentId() { return source.document().documentId(); }
            @Override public Status status() {
                var status = source.document().status();
                return new Status(status.dirty(), status.recoveryRevision());
            }
            @Override public PreparedCheckpoint<RecoverySnapshot> prepareCheckpoint() {
                var capture = source.document().prepareRecovery(source.viewId());
                var liveViewport = source.view().state().viewport();
                int topLine = Math.min(liveViewport.topLine(), source.view().model().lineCount() - 1);
                var viewport = topLine == liveViewport.topLine() ? liveViewport
                    : new ViewportState(topLine, 0, liveViewport.leftColumn(), liveViewport.leftVisualColumn());
                return () -> {
                    var draft = capture.materialize();
                    return new RecoverySnapshot(draft.lines(), position(draft.caret()), viewport,
                        position(draft.anchor()), draft.dirty(), draft.currentResource(), draft.saveDisposition(),
                        draft.fileMetadata(), draft.fileBaseline(), draft.createdAt());
                };
            }
        }

        private static Position position(TextPosition position) {
            return position == null ? null : new Position(position.line(), position.utf16Offset());
        }

        private List<RecoveryParticipant<RecoverySnapshot>> participantList(List<TuiDocumentSession> documents) {
            var seen = java.util.Collections.newSetFromMap(
                new IdentityHashMap<EditorDocumentSession, Boolean>());
            return documents.stream().filter(view -> seen.add(view.document())).map(this::participant).toList();
        }

        private void retainParticipants(List<TuiDocumentSession> documents) {
            participants.keySet().retainAll(documents.stream().map(TuiDocumentSession::document).toList());
        }

        void syncTabs(List<TuiDocumentSession> documents) {
            coordinator.synchronize(participantList(documents),
                RecoveryCoordinator.RemovalDisposition.DISCARD);
            retainParticipants(documents);
        }

        record RestoredSnapshot(TuiDocumentSession session, String entryId) {
            RestoredSnapshot {
                Objects.requireNonNull(session, "session");
                if (entryId == null || entryId.isBlank()) throw new IllegalArgumentException("recovery entryId is required");
            }
        }

        static final class PreparedRestoration {
            private final RecoveryCoordinator.PreparedRestoration<RecoverySnapshot> prepared;
            private PreparedRestoration(RecoveryCoordinator.PreparedRestoration<RecoverySnapshot> prepared) {
                this.prepared = prepared;
            }
        }

        PreparedRestoration prepareRestoration(List<RestoredSnapshot> snapshots, long nowNanos) {
            return new PreparedRestoration(coordinator.prepareRestoration(snapshots.stream()
                .map(item -> new RecoveryCoordinator.RestoredParticipant<>(
                    participant(item.session()), item.entryId())).toList(), nowNanos));
        }

        void publishRestoration(PreparedRestoration prepared) { coordinator.publishRestoration(prepared.prepared); }

        String autosaveDirtyTabs(List<TuiDocumentSession> documents, long nowNanos) {
            // Publish the current document membership before scheduling a checkpoint.
            syncTabs(documents);
            return coordinator.tick(nowNanos);
        }

        String finishSession(List<TuiDocumentSession> documents, boolean exitConfirmed) {
            try { return coordinator.finish(participantList(documents), exitConfirmed); }
            finally {
                retainParticipants(documents);
                if (ownedWorker != null) ownedWorker.shutdown();
            }
        }

        DeletionResult deleteEntries(List<? extends RecoveryStore.PendingSnapshot> entries) {
            var result = coordinator.deleteEntries(entries.stream().map(RecoveryStore.PendingSnapshot::id).toList());
            var failedIds = result.failures().stream().map(RecoveryCoordinator.EntryFailure::entryId).toList();
            return new DeletionResult(result.completedCount(), entries.stream().filter(entry -> failedIds.contains(entry.id()))
                .map(RecoveryStore.PendingSnapshot.class::cast).toList(), details(result));
        }

        ReleaseResult releaseEntries(List<? extends RecoveryStore.PendingSnapshot> entries) {
            var result = coordinator.releaseEntries(entries.stream().map(RecoveryStore.PendingSnapshot::id).toList());
            var failedIds = result.failures().stream().map(RecoveryCoordinator.EntryFailure::entryId).toList();
            return new ReleaseResult(entries.stream().filter(entry -> failedIds.contains(entry.id()))
                .map(RecoveryStore.PendingSnapshot.class::cast).toList(), details(result));
        }

        private static List<String> details(RecoveryCoordinator.BatchResult result) {
            return result.failures().stream().map(item -> item.entryId() + ": "
                + (item.error().getMessage() == null ? item.error().getClass().getSimpleName() : item.error().getMessage())).toList();
        }

        record ReleaseResult(List<RecoveryStore.PendingSnapshot> failedEntries, List<String> failureDetails) {
            ReleaseResult { failedEntries = List.copyOf(failedEntries); failureDetails = List.copyOf(failureDetails); }
            boolean successful() { return failedEntries.isEmpty(); }
            String failureMessage() {
                return successful() ? "" : "Failed to postpone " + failedEntries.size()
                    + " recovery snapshot(s); their data was kept for retry or restore: " + String.join("; ", failureDetails);
            }
        }

        record DeletionResult(int deletedCount, List<RecoveryStore.PendingSnapshot> failedEntries, List<String> failureDetails) {
            DeletionResult { failedEntries = List.copyOf(failedEntries); failureDetails = List.copyOf(failureDetails); }
            boolean successful() { return failedEntries.isEmpty(); }
            String failureMessage() {
                return successful() ? "" : "Failed to discard " + failedEntries.size()
                    + " recovery snapshot(s); they were kept for retry or restore: " + String.join("; ", failureDetails);
            }
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
        output.println("Usage: tuink [options] [--] [file]");
        output.println("Development: ./gradlew --console=plain :app:run -PappArgs=\"[options] [--] [file]\"");
        output.println("Options:");
        output.println("  --no-mouse       Disable mouse tracking and mouse interactions");
        output.println("  --no-alt-screen  Do not use alternate screen buffer");
        output.println("  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        output.println("  --theme=<name>   UI palette: blue, cyan (default), light");
        output.println("  --read           Start in reading mode (editing disabled)");
        output.println("  --open-as-utf16le Interpret the selected BOM-marked file as UTF-16LE");
        output.println("  --profile        Log render timings to stderr every " + PROFILE_LOG_EVERY_FRAMES + " frames");
        output.println("  --profile-file   Create a new profile log file (also enables --profile)");
        output.println("  --file-uri=<uri> Open an ASCII percent-encoded absolute file URI");
        output.println("  --help, -h       Show this help");
        output.println("  --              End options; the following argument is a literal file path");
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
