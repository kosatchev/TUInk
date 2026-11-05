package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.editor.session.recovery.RecoveryCoordinator;
import dev.tuink.editor.session.recovery.RecoveryParticipant;
import dev.tuink.editor.session.recovery.PreparedCheckpoint;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.document.PresentationGroupId;
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
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.clipboard.ProcessClipboard;
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
import dev.tuiframe.scroll.ViewportScrollPolicy;
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
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.ConfirmPromptText;
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
    private HelpPage helpTextPage;
    ListView.State helpScrollState = ListView.State.initial();
    private final Theme confirmWidgetTheme = this::resolveConfirmWidgetStyle;
    final FocusManager<ConfirmChoice> confirmFocusManager = new FocusManager<>();
    private final Theme saveAsWidgetTheme = this::resolveSaveAsWidgetStyle;
    final FocusManager<SaveAsBarView.Target> saveAsFocusManager = new FocusManager<>();
    private final Theme searchWidgetTheme = this::resolveSearchWidgetStyle;
    final FocusManager<SearchFocus> searchFocusManager = new FocusManager<>();
    private Supplier<TabUiState> tabUiSource;
    private final Theme colorTabBarWidgetTheme = key -> resolveTabBarWidgetStyle(key, true);
    private final Theme monoTabBarWidgetTheme = key -> resolveTabBarWidgetStyle(key, false);
    private final Theme colorTopMenuWidgetTheme = key -> resolveTopMenuWidgetStyle(key, true);
    private final Theme monoTopMenuWidgetTheme = key -> resolveTopMenuWidgetStyle(key, false);
    private final Theme colorFileTreeWidgetTheme = key -> resolveFileTreeWidgetStyle(key, true);
    private final Theme monoFileTreeWidgetTheme = key -> resolveFileTreeWidgetStyle(key, false);
    private HelpOverlayPlacement helpOverlayCache;
    boolean profileEnabled = false;
    final FrameProfiler frameProfiler = new FrameProfiler();
    private final FileAccessAdvisory fileAccess;

    public TerminalEditorApp() {
        this(new FileAccessAdvisory());
    }

    TerminalEditorApp(FileAccessAdvisory fileAccess) {
        this.fileAccess = Objects.requireNonNull(fileAccess, "fileAccess");
    }

    boolean refreshFileAccess(EditorState state) {
        return fileAccess.refresh(JvmDocumentStorage.pathOf(state.getCurrentResource()), System.nanoTime());
    }

    long nanosUntilFileAccessRefresh(long nowNanos) {
        return fileAccess.nanosUntilRefresh(nowNanos);
    }

    void closeFileAccess() {
        fileAccess.close();
    }

    private boolean suggestsSaveAs(EditorState state) {
        Path path = JvmDocumentStorage.pathOf(state.getCurrentResource());
        return path == null || fileAccess.isReadOnly(path);
    }

    void bindTabUi(Supplier<TabUiState> tabUiSource) {
        this.tabUiSource = Objects.requireNonNull(tabUiSource, "tabUiSource");
    }

    private TabUiState selectedTabUi() {
        return tabUiSource == null ? null : tabUiSource.get();
    }

    FocusManager<SearchFocus> searchFocusManager() {
        TabUiState ui = selectedTabUi();
        return ui == null ? searchFocusManager : ui.searchFocusManager;
    }

    FocusManager<ConfirmChoice> confirmFocusManager() {
        TabUiState ui = selectedTabUi();
        return ui == null ? confirmFocusManager : ui.confirmFocusManager;
    }

    FocusManager<SaveAsBarView.Target> saveAsFocusManager() {
        TabUiState ui = selectedTabUi();
        return ui == null ? saveAsFocusManager : ui.saveAsFocusManager;
    }

    FocusManager<HelpOverlayInputRouter.Target> helpFocusManager() {
        TabUiState ui = selectedTabUi();
        return ui == null ? helpFocusManager : ui.helpFocusManager;
    }

    ListView.State helpScrollState() {
        TabUiState ui = selectedTabUi();
        return ui == null ? helpScrollState : ui.helpScrollState;
    }

    void setHelpScrollState(ListView.State state) {
        Objects.requireNonNull(state, "state");
        TabUiState ui = selectedTabUi();
        if (ui == null) helpScrollState = state;
        else ui.helpScrollState = state;
    }

    private HelpPage helpScrollPage() {
        TabUiState ui = selectedTabUi();
        return ui == null ? helpScrollPage : ui.helpScrollPage;
    }

    private void setHelpScrollPage(HelpPage page) {
        TabUiState ui = selectedTabUi();
        if (ui == null) helpScrollPage = page;
        else ui.helpScrollPage = page;
    }

    private ListView.WrappedText helpText() {
        TabUiState ui = selectedTabUi();
        return ui == null ? helpText : ui.helpText;
    }

    private HelpPage helpTextPage() {
        TabUiState ui = selectedTabUi();
        return ui == null ? helpTextPage : ui.helpTextPage;
    }

    private void setHelpText(HelpPage page, ListView.WrappedText text) {
        TabUiState ui = selectedTabUi();
        if (ui == null) {
            helpText = text;
            helpTextPage = page;
        } else {
            ui.helpText = text;
            ui.helpTextPage = page;
        }
    }

    private HelpOverlayPlacement helpOverlayCache() {
        TabUiState ui = selectedTabUi();
        return ui == null ? helpOverlayCache : ui.helpOverlayCache;
    }

    private void setHelpOverlayCache(HelpOverlayPlacement placement) {
        TabUiState ui = selectedTabUi();
        if (ui == null) helpOverlayCache = placement;
        else ui.helpOverlayCache = placement;
    }

    DialogAttention modalAttention() {
        TabUiState ui = selectedTabUi();
        return ui == null ? modalAttention : ui.modalAttention;
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = launch(args);
        } catch (Throwable failure) {
            TerminalDiagnostics.failure(failure);
            exitCode = 1;
        }
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
            TerminalDiagnostics.error(e.getMessage());
            printUsage(System.err);
            return 2;
        }
        if (cli.showHelp()) {
            printUsage(System.out);
            return 0;
        }
        if (cli.showVersion()) {
            TerminalDiagnostics.line(System.out, "TUInk " + AppVersion.current());
            return 0;
        }
        RecoveryStore recoveryStore;
        try {
            recoveryStore = new RecoveryStore();
        } catch (IOException error) {
            TerminalDiagnostics.error("Cannot select settings/recovery profile: " + error.getMessage());
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
        String settingsLoadFailure = null;
        try {
            settingsSnapshot = AppSettingsStore.loadSnapshot();
            loadedSettings = settingsSnapshot.settings();
        } catch (IOException e) {
            settingsLoadFailure = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
            TerminalDiagnostics.error("Warning: failed to load settings: " + e.getMessage());
        }

        AppSettingsSession settingsSession = new AppSettingsSession(loadedSettings, cli, settingsLoadFailure);
        AppSettings initialSettings = settingsSession.initialSettings();
        applyTheme(initialSettings.theme());
        SessionContext sessionContext = new SessionContext(List.of(newEditorTab()), 0);
        List<EditorTab> tabs = sessionContext.tabs();
        RenderRasterizer renderer = new RenderRasterizer(1, theme);
        EditorView editorView = new EditorView();

        NotificationState notification = new NotificationState();
        if (!settingsSession.canSavePreferences()) {
            notification.persistentWarning(settingsSession.saveUnavailableWarning());
        }
        UiModalState modalState = UiModalState.none();
        RecoveryAutosaveManager recoveryAutosave = new RecoveryAutosaveManager(recoveryStore);
        List<RecoveryStore.PendingSnapshot> startupRecoveryEntries = new ArrayList<>();
        TextField saveAsInput = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        SaveAsSuggestionStore saveAsSuggestions = new SaveAsSuggestionStore();
        UiSessionState uiSessionState = UiSessionState.from(initialSettings);
        TextField searchQueryInput = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        TextField searchReplaceInput = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        SearchController.SearchUiOptions searchUi = SearchController.SearchUiOptions.DEFAULT;
        TopMenuController topMenu = new TopMenuController();
        Path initialOpenFileRoot = FilePickerOpenWorkflow.initialRoot(
            JvmDocumentStorage.pathOf(sessionContext.state().getCurrentResource()));
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
                TerminalDiagnostics.error(
                    "Warning: incompatible recovery snapshots were left unchanged in "
                        + recoveryStore.directory() + ": " + recoveryLoad.skipped()
                );
            }
            if (!startupRecoveryEntries.isEmpty()) {
                modalState = UiModalState.recoveryConfirm();
            }
        } catch (IOException e) {
            TerminalDiagnostics.error(
                "Warning: failed to load recovery snapshots from " + recoveryStore.directory() + ": " + e.getMessage()
            );
        }

        openInitialDocument(cli, sessionContext, notification);

        try (
            renderer;
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
                        new EditorWindowController.TerminalAccess(terminal::width, terminal::height, terminal::setMouseTracking, effectiveCapabilities::mouseTracking),
                        topMenu,
                        uiSessionState,
                        settingsSession,
                        new DocumentIoController()
                    ), ProcessClipboard.detect().orElse(null)),
                    recoveryAutosave,
                    () -> toDocumentSessions(tabs),
                    warning -> TerminalDiagnostics.error("Warning: " + warning)
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
                TerminalDiagnostics.error("Warning: " + conflict.getMessage());
            } catch (IOException e) {
                TerminalDiagnostics.error("Warning: failed to save settings: " + e.getMessage());
            }
        }
    }

    /** Keep bootstrap document references out of the long-lived window-loop stack frame. */
    private void openInitialDocument(TuiCliOptions cli, SessionContext sessionContext, NotificationState notification) {
        if (cli.file() == null) return;
        Path file = cli.file();
        try {
            var resource = JvmDocumentStorage.resource(file);
            var mode = cli.openMode();
            var open = new dev.tuink.editor.session.document.PreparedDocumentOpen(
                resource, mode, mode == dev.tuink.editor.storage.DocumentOpenMode.DETECT);
            sessionContext.active().documentSession().openPrepared(open.accept(open.read()));
            RenderPlanBuilder builder = sessionContext.active().documentSession()
                .newRenderPlanBuilder(resolveHighlighter(file));
            sessionContext.replaceActiveRenderPlanBuilder(builder);
        } catch (IOException e) {
            String reason = e.getMessage();
            if (e instanceof dev.tuink.editor.storage.AmbiguousDocumentEncodingException) {
                reason += " Use --open-as-utf16le to select UTF-16LE.";
            }
            notification.error("Open failed: " + reason);
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
        coordinates.scrollBy(state, Math.max(1, contentRows), contentCols, delta, 0, 0);
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
            canvas.put(col, 0, cell.grapheme(), styles.windows().topbarFilenameHighlight());
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
        placement.paint(canvas, confirmWidgetTheme, confirmFocusManager());
    }

    private void paintSaveAsField(Canvas canvas, SaveAsBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, saveAsWidgetTheme, saveAsFocusManager());
    }

    private void paintSearchBar(Canvas canvas, SearchBarView.Placement placement) {
        if (placement == null) {
            return;
        }
        placement.paint(canvas, searchWidgetTheme, searchFocusManager());
    }

    SearchFocus currentSearchFocus() {
        return searchFocusManager().focused().orElse(SearchFocus.FIND);
    }

    void focusSearch(SearchBarView.Placement placement, SearchFocus target) {
        if (placement != null && target != null) {
            searchFocusManager().focus(placement.focusScope(), target);
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

    static WindowInput resolveEditorInput(
        CommandRouter commandRouter,
        TuiInputEvent event,
        CommandInvocation pendingCommand,
        CommandRouter.RoutedCommand routedMenuResult,
        UiRecipient recipient
    ) {
        Objects.requireNonNull(commandRouter, "commandRouter");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(recipient, "recipient");
        if (routedMenuResult != null) {
            return new WindowInput.Command(Objects.requireNonNull(pendingCommand, "pendingCommand"),
                routedMenuResult, recipient);
        }
        if (pendingCommand != null) {
            var invocation = pendingCommand;
            return commandRouter.dispatch(invocation)
                .<WindowInput>map(route -> new WindowInput.Command(invocation, route, recipient))
                .orElse(WindowInput.Consumed.INSTANCE);
        }
        return WindowInput.physical(event, recipient);
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
        boolean hasPlacement, boolean pointerAvailable, boolean windowModal, boolean menuOpen
    ) {
        if (!hasPlacement || !pointerAvailable || menuOpen) return null;
        return windowModal ? TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM : TabBarInputRouter.Policy.NORMAL;
    }

    static ModalInputPolicy.Kind modalInputKind(UiModalState modal, boolean openFileMode) {
        if (modal.isNone()) {
            return openFileMode ? ModalInputPolicy.Kind.OPEN_FILE : ModalInputPolicy.Kind.NONE;
        }
        if (modal.isSaveAs()) {
            return ModalInputPolicy.Kind.SAVE_AS;
        }
        if (modal.isDocumentFormat()) return ModalInputPolicy.Kind.FORMAT;
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

    void paintBlockingAttention(CellBuffer buffer, FrameComposition.Layers layers, boolean colorEnabled) {
        if (!layers.blockedInputHighlight()) {
            return;
        }
        CellStyle accent = blockingAccent(colorEnabled);
        DialogAttentionPainter.paint(
            buffer,
            layers.blockingBounds(),
            layers.helpPage() != null || layers.openFile().visible() || layers.format() != null,
            accent
        );
    }

    private CellStyle blockingAccent(boolean colorEnabled) {
        return colorEnabled ? styles.windows().notifyWarning()
            : new CellStyle(CellColor.DEFAULT, CellColor.DEFAULT, true, true);
    }

    private CellStyle focusedButtonStyle(CellStyle parentStyle) {
        if (parentStyle == null) {
            return styles.windows().menuSelected();
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
        if (!openFile.visible()) {
            return;
        }
        OpenDialogLayout layout = openFile.layout();
        OpenFileDialogView.Placement dialog = openFile.dialog();
        Theme childTheme = (colorEnabled ? colorFileTreeWidgetTheme : monoFileTreeWidgetTheme)
            .withSurface(styles.windows().dialog(), highlighted ? blockingAccent(colorEnabled) : styles.windows().dialogBorder());
        if (dialog.compactDialog() != null) {
            dialog.paintCompact(canvas, childTheme, WidgetState.FOCUSED);
            return;
        }
        dialog.frame().paint(canvas, childTheme);
        String pathLine = " " + openFile.rootLabel();
        drawMenuLine(
            canvas,
            layout.top() + 1,
            layout.left() + 1,
            padRight(dev.tuink.tui.ui.PathPresentation.clip(pathLine, 0, Math.max(0, layout.width() - 2)),
                Math.max(0, layout.width() - 2)),
            styles.windows().dialog()
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

    private HelpDialogLayout helpDialogLayout(Rect viewport, int contentRows) {
        int preferredWidth = 68;
        // Frame borders and one footer row surround the help viewport.
        int preferredHeight = contentRows + 3;
        int width = Math.min(viewport.width(), Math.max(24, Math.min(preferredWidth, viewport.width() - 4)));
        int height = Math.min(viewport.height(), Math.max(8, Math.min(preferredHeight, viewport.height() - 4)));
        int left = viewport.x() + Math.max(0, (viewport.width() - width) / 2);
        int top = viewport.y() + Math.max(0, (viewport.height() - height) / 2);
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
        return helpOverlayPlacement(rows, cols, page, 0);
    }

    HelpOverlayPlacement helpOverlayPlacement(int rows, int cols, HelpPage page, int topInset) {
        if (page == null) {
            resetHelpScroll();
            return null;
        }
        int height = Math.max(0, rows);
        int inset = Math.clamp(topInset, 0, height);
        Rect viewport = new Rect(0, inset, Math.max(0, cols), height - inset);
        if (helpTextPage() != page) {
            ListView.WrappedText prepared = ListView.wrappedText(page.rows());
            setHelpText(page, prepared);
        }
        if (helpScrollPage() != page) {
            ListView.State initial = ListView.State.initial();
            setHelpScrollState(initial);
            setHelpScrollPage(page);
        }
        if (CompactDialogView.required(viewport.height(), viewport.width())) {
            CompactDialogView.Placement compact = CompactDialogView.place(viewport, page.title());
            Rect bounds = compact.bounds();
            Button close = compact.closeButton();
            RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> target = new RoutedTarget<>(
                HelpOverlayInputRouter.Target.CLOSE, close.layout(CompactDialogView.closeBounds(close)),
                FocusSpace.root(viewport), true, true);
            // Keep the last visible text placement and its source anchor while
            // the compact owner temporarily has no scrollable help content.
            return new HelpOverlayPlacement(page,
                ListView.text(0, index -> "", ListView.State.initial()).layout(
                    new Rect(bounds.x(), bounds.y(), 0, 0)),
                "", new HelpDialogLayout(bounds.y(), bounds.x(), bounds.width(), bounds.height()),
                viewport, compact.frame(), close,
                FocusScope.single(HelpOverlayInputRouter.Scope.HELP, FocusResetPolicy.FIRST_FOCUSABLE, target));
        }
        HelpDialogLayout layout = helpDialogLayout(viewport, page.rows().size());
        int visibleRows = Math.max(0, layout.height() - 3);
        HelpOverlayPlacement cached = helpOverlayCache();
        ListView.State scroll = helpScrollState();
        if (cached != null && !cached.viewport().equals(viewport) && scroll.drag() != null) {
            scroll = new ListView.State(-1, scroll.firstVisibleIndex());
            setHelpScrollState(scroll);
        }
        if (cached != null
            && cached.page() == page
            && cached.content().state().equals(scroll)
            && cached.layout().equals(layout)
            && cached.viewport().equals(viewport)) {
            return cached;
        }
        ListView.Placement content = helpText().layout(
            new Rect(layout.left() + 2, layout.top() + 1, Math.max(0, layout.width() - 4), visibleRows),
            scroll,
            cached != null && cached.page() == page ? cached.content() : null
        );
        setHelpScrollState(content.state());
        Modal frame = infoDialogFrame(layout, page.title());
        Button close = helpCloseButton(layout);
        RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> closeTarget = new RoutedTarget<>(
            HelpOverlayInputRouter.Target.CLOSE,
            close.layout(
                new Rect(close.col(), close.row(), close.width(), 1)
            ),
            FocusSpace.root(viewport),
            true,
            true
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
        setHelpOverlayCache(prepared);
        return prepared;
    }

    void resetHelpScroll() {
        setHelpScrollPage(null);
        setHelpText(null, null);
        setHelpScrollState(ListView.State.initial());
        setHelpOverlayCache(null);
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
            setHelpScrollState(changed.state());
        }
        if (capture != null) {
            capture.observe(surfaceId, EditorWindowController.helpContentScope(overlay), "content", scrolled);
        }
        if (scrolled.consumed()) {
            return new HelpOverlayInputRouter.Result(true, false);
        }
        HelpOverlayInputRouter.Result result = HelpOverlayInputRouter.route(
            helpFocusManager(), overlay.focusScope(), overlay.frame(), event
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
        if (overlay == null) {
            return;
        }
        HelpDialogLayout layout = overlay.layout();
        Theme widgetTheme = colorEnabled ? colorOverlayWidgetTheme : monoOverlayWidgetTheme;
        if (highlighted) {
            widgetTheme = widgetTheme.withSurface(styles.windows().dialog(), blockingAccent(colorEnabled));
        }
        if (CompactDialogView.required(overlay.viewport().height(), overlay.viewport().width())) {
            var target = overlay.focusScope().target(HelpOverlayInputRouter.Target.CLOSE).orElseThrow();
            CompactDialogView.paint(canvas, overlay.frame(), overlay.close(), widgetTheme,
                helpFocusManager().context(overlay.focusScope(), target.id()).orElseThrow().widgetState());
            return;
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
            styles.windows().dialog()
        );
        RoutedTarget<HelpOverlayInputRouter.Target, Button.Action> closeTarget = overlay.focusScope()
            .target(HelpOverlayInputRouter.Target.CLOSE)
            .orElseThrow();
        closeTarget.placement().paint(
            canvas,
            widgetTheme,
            helpFocusManager().context(overlay.focusScope(), closeTarget.id())
                .orElseThrow()
                .widgetState()
        );
    }

    CellStyle resolveOverlayWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MODAL_BODY.equals(key)
            || WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.LIST_ITEM.equals(key)) {
            return styles.windows().dialog();
        }
        if (WidgetThemeKeys.MODAL_BORDER.equals(key)) {
            return styles.windows().dialogBorder();
        }
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return styles.windows().dialogTitle();
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return colorEnabled ? styles.windows().menuSelected() : styles.windows().dialogSelectedMono();
        }
        if (WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.windows().dialog();
        }
        return null;
    }

    CellStyle resolveConfirmWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return focusedButtonStyle(styles.windows().promptAlert());
        }
        if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.windows().promptAlert();
        }
        return null;
    }

    CellStyle resolveSaveAsWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key) || WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(key)) {
            return focusedButtonStyle(styles.windows().promptAlert());
        }
        if (WidgetThemeKeys.TEXT_FIELD.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(key)
            || WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.windows().promptAlert();
        }
        return null;
    }

    CellStyle resolveSearchWidgetStyle(WidgetStyleRole key) {
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key) || WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(key)) {
            return focusedButtonStyle(styles.windows().searchBar());
        }
        if (WidgetThemeKeys.BUTTON.equals(key)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(key)
            || WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(key)) {
            return styles.windows().searchBar();
        }
        return null;
    }

    CellStyle resolveTabBarWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.TAB_ITEM_ACTIVE.equals(key)) {
            return colorEnabled ? styles.windows().tabbarActive() : styles.windows().tabbarActiveMono();
        }
        if (WidgetThemeKeys.TAB_BAR.equals(key)
            || WidgetThemeKeys.TAB_ITEM.equals(key)
            || WidgetThemeKeys.TAB_ITEM_DISABLED.equals(key)) {
            return colorEnabled ? styles.windows().tabbar() : styles.windows().tabbarMono();
        }
        return null;
    }

    CellStyle resolveTopMenuWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MENU_BAR_ACTIVE.equals(key)) {
            return colorEnabled ? styles.windows().menuTopSelected() : styles.windows().menuSelectedMono();
        }
        if (WidgetThemeKeys.MENU_BAR.equals(key)
            || WidgetThemeKeys.MENU_BAR_DISABLED.equals(key)) {
            return styles.windows().renderTopbar();
        }
        if (WidgetThemeKeys.MENU_ITEM_SELECTED.equals(key)) {
            return colorEnabled ? styles.windows().menuSelected() : styles.windows().menuSelectedMono();
        }
        if (WidgetThemeKeys.MENU_ITEM_DISABLED.equals(key)) {
            return colorEnabled ? styles.windows().contextMenuDisabledItem() : styles.windows().menuPopup();
        }
        if (WidgetThemeKeys.MENU_POPUP.equals(key)
            || WidgetThemeKeys.MENU_ITEM.equals(key)) {
            return styles.windows().menuPopup();
        }
        return null;
    }

    CellStyle resolveFileTreeWidgetStyle(WidgetStyleRole key, boolean colorEnabled) {
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return styles.windows().dialogTitle();
        }
        if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(key)
            || WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(key)) {
            return colorEnabled ? styles.windows().menuSelected() : styles.windows().dialogSelectedMono();
        }
        if (WidgetThemeKeys.LIST_ITEM.equals(key)
            || WidgetThemeKeys.LIST_ITEM_DISABLED.equals(key)) {
            return styles.windows().dialog();
        }
        if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
            return styles.windows().dialog();
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return colorEnabled ? styles.windows().menuSelected() : styles.windows().dialogSelectedMono();
        }
        return null;
    }

    ConfirmBarInputRouter.Policy confirmPolicy(UiModalState modalState) {
        if (modalState.isOpenUtf16Confirm()) return ConfirmBarInputRouter.Policy.open();
        if (modalState.isSaveUtf8Confirm()) {
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
        FocusManager<ConfirmChoice> focus = confirmFocusManager();
        focus.activeScopeId().ifPresent(focus::deactivate);
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
        return buildStatusBar(state, model, cols, readingMode, activeTabIndex, totalTabs, searchSummary, null, 0).text();
    }

    DocumentStatusBarView.Placement buildStatusBar(EditorState state, TextModel model, int cols,
        boolean readingMode, int activeTabIndex, int totalTabs, String searchSummary,
        dev.tuink.editor.format.DocumentFormatSummary summary, int row) {
        Position p = state.getCursor();
        int totalLines = model.lineCount();
        int lineCols = model.lineLength(p.line());
        String position = "Ln " + (p.line() + 1) + "/" + totalLines
            + ", Col " + (p.col() + 1) + "/" + lineCols;
        String compactPosition = "Ln " + (p.line() + 1) + "/" + totalLines + ", Col " + (p.col() + 1);
        boolean markdown = MarkdownOpenPolicy.isMarkdown(JvmDocumentStorage.pathOf(state.getCurrentResource()));
        String tabs = totalTabs > 1 || markdown ? "Tab " + (activeTabIndex + 1) + "/" + totalTabs : "";
        var left = new DocumentStatusBarView.Reading(position, compactPosition, tabs,
            markdown ? "Ctrl+P: Preview" : "", searchSummary);
        String ro = fileAccess.indicator(JvmDocumentStorage.pathOf(state.getCurrentResource()));
        String enc = summary == null ? state.getFileEncoding() == null ? "UTF-8" : state.getFileEncoding().name()
            : summary.charsetName() + (summary.bomKind() == dev.tuink.editor.storage.BomKind.NONE ? "" : "+BOM");
        String defaultEol = switch (summary == null ? state.getLineSeparator() : summary.defaultLineSeparator()) {
            case EditorState.LINE_SEPARATOR_CRLF -> "CRLF";
            case EditorState.LINE_SEPARATOR_CR -> "CR";
            default -> "LF";
        };
        String eol = summary == null ? defaultEol : switch (summary.lineEnding()) {
            case MIXED -> "Mixed";
            case NONE -> defaultEol;
            case LF -> "LF";
            case CRLF -> "CRLF";
            case CR -> "CR";
        };
        return DocumentStatusBarView.place(left, ro, enc, eol, readingMode, cols, row);
    }

    private void paintFrameOverlays(CellBuffer current, Canvas canvas, int rows, int cols,
        FrameComposition.Surface surface, FrameComposition.Layers layers, SearchBarView.Placement search,
        boolean colorEnabled) {
        paintSaveAsField(canvas, layers.saveAs());
        paintConfirmButtons(canvas, layers.confirm());
        paintSearchBar(canvas, search);
        if (layers.tabBar() != null) layers.tabBar().paint(canvas,
            colorEnabled ? colorTabBarWidgetTheme : monoTabBarWidgetTheme);
        if (layers.topMenu() != null) layers.topMenu().paint(canvas,
            colorEnabled ? colorTopMenuWidgetTheme : monoTopMenuWidgetTheme);
        drawTopFileNameBold(current, canvas, cols, surface.topBar());
        drawOpenFileOverlay(canvas, rows, cols, layers.openFile(), colorEnabled,
            layers.blockedInputHighlight(), layers.contentFocused());
        paintHelpOverlay(canvas, layers.help(), colorEnabled, layers.blockedInputHighlight());
        if (layers.format() != null) layers.format().paint(canvas,
            (colorEnabled ? colorFileTreeWidgetTheme : monoFileTreeWidgetTheme).withSurface(styles.windows().dialog(), styles.windows().dialog()));
        paintBlockingAttention(current, layers, colorEnabled);
    }

    /** Shared overlay ownership; the underlying content supplies only a completed cursor intent. */
    private ScreenCursor frameCursor(int rows, int cols,
        FrameComposition.Layers layers, SearchBarView.Placement search, boolean searchEditorFocused,
        ScreenCursor contentCursor) {
        Point point = contentCursor.position();
        Point searchCursor = search == null ? null : search.cursorPosition(searchFocusManager()).orElse(null);
        boolean searchOwnsCursor = search != null && !searchEditorFocused;
        boolean contentFocused = layers.contentFocused();
        boolean menuOpen = layers.topMenu() != null && layers.topMenu().state().isOpen();
        boolean openFile = layers.openFile().visible();
        boolean help = layers.help() != null;
        boolean visible = CursorPolicy.shouldShowCursor(layers.saveAsMode() && !contentFocused,
            searchOwnsCursor, searchCursor != null, layers.confirm() != null && !contentFocused,
            menuOpen, openFile && !contentFocused, help && !contentFocused);
        boolean contentOwnsCursor = contentFocused || !layers.saveAsMode() && !searchOwnsCursor
            && layers.confirm() == null && !menuOpen && !openFile && !help && layers.format() == null;
        if (contentOwnsCursor) visible = contentCursor.visible();
        if (layers.format() != null) visible = false;
        if (contentFocused && layers.blockingBounds().contains(point.x(), point.y())) visible = false;
        if (layers.saveAsMode() && !contentFocused) {
            Point field = layers.saveAs() == null ? null : layers.saveAs().cursorPosition(saveAsFocusManager()).orElse(null);
            if (field == null) visible = false;
            else point = field;
        } else if (openFile && !contentFocused) {
            Point location = layers.openFile().dialog().cursorPosition(layers.openFile().focus()).orElse(null);
            visible = location != null;
            if (location != null) point = location;
        } else if (searchOwnsCursor) {
            if (searchCursor == null) visible = false;
            else point = searchCursor;
        } else if (menuOpen) {
            Point menu = layers.topMenu().cursorPosition().orElse(null);
            if (menu == null) visible = false;
            else point = menu;
        } else if (help && !contentFocused) {
            Button close = layers.help().close();
            point = new Point(Math.min(cols - 1, close.cursorCol()), Math.min(rows - 1, close.row()));
        }
        return new ScreenCursor(new Point(Math.max(0, Math.min(cols - 1, point.x())),
            Math.max(0, Math.min(rows - 1, point.y()))), visible);
    }

    private Frame renderPreviewFrame(PreviewFrameComposition composition, RenderFrameController services) {
        int rows = composition.rows(), cols = composition.cols();
        var surface = composition.surface();
        var layers = composition.layers();
        var geometry = composition.preview().geometry();
        if (profileEnabled && geometry != null) frameProfiler.beginRasterCache(
            geometry.visualMetricsBuilds() + geometry.semanticMetricsBuilds(),
            geometry.preparedRowsMisses(), geometry.preparedRowsHits());
        long rasterStart = profileEnabled ? System.nanoTime() : 0L;
        CellBuffer current = new CellBuffer(rows, cols);
        Canvas canvas = frameCanvas(current, rows, cols);
        canvas.fill(new Rect(0, 0, cols, rows), services.colorEnabled
            ? styles.windows().renderBody() : CellStyle.DEFAULT);
        Rect content = new Rect(0, composition.contentTopRow(), cols, Math.max(0,
            rows - 1 - composition.contentTopRow() - surface.inlinePrompt().height() - surface.notification().height()));
        int mark = canvas.save();
        try {
            canvas.clip(content);
            MarkdownPreviewPainter.paint(canvas, composition.preview(), styles,
                PresentationSyntaxPolicy.preview(services.colorEnabled, theme).color());
        } finally { canvas.restore(mark); }
        dev.tuink.tui.render.WindowTextPainter.paint(canvas, rows, cols,
            new dev.tuink.tui.render.WindowTextPainter.Text(surface.topBar().text(), surface.bottomBar(),
                surface.inlinePrompt().text(), surface.inlinePrompt().style(),
                surface.notification().text(), surface.notification().style()), styles);
        paintFrameOverlays(current, canvas, rows, cols, surface, layers, null, services.colorEnabled);
        Point caret = MarkdownPreviewPainter.cursor(composition.preview());
        ScreenCursor contentCursor = caret == null ? ScreenCursor.hidden()
            : new ScreenCursor(caret, content.contains(caret.x(), caret.y()));
        Rect body = composition.preview().contentBounds().intersection(content);
        ViewportScrollPolicy.Viewport viewport = geometry == null || composition.preview().result() == null
            || !composition.preview().error().isEmpty() ? null
            : new ViewportScrollPolicy.Viewport("preview", geometry.identity(), body,
                composition.preview().top(), composition.preview().left(), !composition.preview().selection().selected());
        if (profileEnabled) {
            frameProfiler.buildNanos = 0L;
            frameProfiler.rasterNanos = System.nanoTime() - rasterStart;
            if (geometry != null) frameProfiler.endRasterCache(
                geometry.visualMetricsBuilds() + geometry.semanticMetricsBuilds(),
                geometry.preparedRowsMisses(), geometry.preparedRowsHits(), geometry.retainedBytes());
        }
        return services.present(current, frameCursor(rows, cols, layers,
            null, false, contentCursor), viewport, layers, composition.fullRedraw());
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
        renderer.setColorEnabled(colorEnabled);
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
            frameProfiler.beginRasterCache(renderer.metricsBuilds(), renderer.preparedRowsMisses(), renderer.preparedRowsReused());
            long rasterStart = System.nanoTime();
            current = renderer.rasterize(plan, rows, cols, contentTopRow);
            frameProfiler.rasterNanos = System.nanoTime() - rasterStart;
            frameProfiler.endRasterCache(renderer.metricsBuilds(), renderer.preparedRowsMisses(),
                renderer.preparedRowsReused(), renderer.retainedRenderBytes());
        } else {
            current = renderer.rasterize(plan, rows, cols, contentTopRow);
        }
        Canvas canvas = frameCanvas(current, rows, cols);
        paintFrameOverlays(current, canvas, rows, cols, surface, layers, searchBarPlacement, colorEnabled);

        EditorView.CursorPosition editorCursor = editorView.cursorFromPlan(plan, contentTopRow);
        ScreenCursor contentCursor = new ScreenCursor(new Point(editorCursor.col() - 1, editorCursor.row() - 1), plan.cursorInViewport());
        ScreenCursor cursor = frameCursor(rows, cols, layers, searchBarPlacement,
            search.editorFocused(), contentCursor);

        Rect body = new Rect(plan.gutterWidth(), contentTopRow,
            Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth()), plan.contentRows())
            .intersection(new Rect(0, 0, cols, rows));
        long generation = (plan.wrapLines() ? 1L : 0L) | (plan.wrapByWords() ? 2L : 0L);
        var viewport = new ViewportScrollPolicy.Viewport("source:" + renderPlanBuilder.renderIdentity(), generation,
            body, plan.topLine(), plan.leftVisualColumn(), !plan.wrapLines() && !state.hasSelection());
        services.present(current, cursor, viewport, layers, composition.fullRedraw());
        return new RenderFrameResult(plan, true);
    }

    final class RenderFrameController {
        private final RenderRasterizer renderer;
        private final EditorView editorView;
        private final boolean colorEnabled;
        private final ScreenPresenter screenPresenter;
        private final ViewportScrollPolicy.Tracker scrollTracker = new ViewportScrollPolicy.Tracker();
        private FrameComposition.OpenTreeDiffKey previousTree;

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

        /** A preview-only host needs neither a source rasterizer nor an editor view. */
        RenderFrameController(boolean colorEnabled, ScreenPresenter screenPresenter) {
            this(null, null, colorEnabled, screenPresenter);
        }

        RenderFrameResult renderFrameIfNeeded(FrameComposition composition) {
            return renderFrame(composition, this);
        }

        Frame renderPreviewFrameIfNeeded(PreviewFrameComposition composition) {
            return renderPreviewFrame(composition, this);
        }

        /** Both surfaces publish through the same terminal session and diagnostics. */
        private Frame present(CellBuffer cells, ScreenCursor cursor, ViewportScrollPolicy.Viewport contentViewport,
            FrameComposition.Layers layers, boolean fullRedraw) {
            ViewportScrollPolicy.Viewport viewport = null;
            FrameComposition.OpenTreeDiffKey tree = null;
            if (layers.allowsScrollHints()) {
                if (layers.openFile().visible()) {
                    tree = layers.openFile().diffKey();
                    if (tree != null) viewport = new ViewportScrollPolicy.Viewport("picker", 0,
                        tree.surface().contentBounds().intersection(new Rect(0, 0, cells.cols(), cells.rows())),
                        tree.firstVisibleIndex(), 0, false);
                } else viewport = contentViewport;
            }
            if (fullRedraw) {
                screenPresenter.invalidate();
                scrollTracker.invalidate();
            }
            if (tree != null && (previousTree == null || !tree.surface().equals(previousTree.surface()))) scrollTracker.invalidate();
            Frame frame = new Frame(cells, cursor, scrollTracker.next(viewport));
            try {
                var presented = screenPresenter.present(frame, profileEnabled);
                if (profileEnabled) frameProfiler.presented(presented);
                previousTree = tree;
                return frame;
            } catch (RuntimeException | Error failure) {
                screenPresenter.invalidate();
                scrollTracker.invalidate(); previousTree = null;
                throw failure;
            }
        }
    }

    ConfirmBarView buildOverwriteConfirmBar(Path path, int cols, int availableRows) {
        String question = ConfirmPromptText.wrappingFileQuestion("Overwrite: ", path, " ?",
            responsiveQuestionWidth(cols, availableRows));
        return ConfirmBarView.responsive(question, cols, availableRows,
            "Replace", "Choose another name", true, "Cancel", ConfirmBarInputRouter.Policy.overwrite().shortcuts());
    }

    private static int responsiveQuestionWidth(int cols, int availableRows) {
        return Math.max(0, cols) * Math.max(1, Math.min(8, availableRows - 4));
    }

    ConfirmBarView buildQuitConfirmBar(List<EditorTab> tabs, int cols, int availableRows) {
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
        } else if (dirtyTabs.size() == 1 && suggestsSaveAs(dirtyTabs.get(0).state())) {
            primaryLabel = "Save As";
        } else {
            primaryLabel = "Save";
        }
        String secondaryLabel = multiple ? "Discard All" : "Discard";
        int promptWidth = responsiveQuestionWidth(cols, availableRows);
        String minimumContext = "Unsaved (" + dirtyPaths.size() + ")";
        String prompt = CellText.width(minimumContext) + 3 <= promptWidth
            ? DirtyTabSummary.build(dirtyPaths, promptWidth)
            : minimumContext;
        return ConfirmBarView.responsive(prompt, cols, availableRows,
            primaryLabel, secondaryLabel, true, "Cancel", ConfirmBarInputRouter.Policy.quit().shortcuts());
    }

    ConfirmBarView buildCloseTabConfirmBar(List<EditorTab> tabs, int closeTabIndex, int cols, int availableRows) {
        EditorTab pendingTab = closeTabIndex >= 0 && closeTabIndex < tabs.size()
            ? tabs.get(closeTabIndex)
            : null;
        String primaryLabel = pendingTab != null && suggestsSaveAs(pendingTab.state()) ? "Save As" : "Save";
        return ConfirmBarView.responsive(
            ConfirmPromptText.wrappingFileQuestion("Close: ", pendingTab == null ? null
                : JvmDocumentStorage.pathOf(pendingTab.state().getCurrentResource()), " ?",
                responsiveQuestionWidth(cols, availableRows)),
            cols, availableRows, primaryLabel, "Discard", true, "Cancel", ConfirmBarInputRouter.Policy.closeTab().shortcuts());
    }

    String buildRecoveryPrompt(int count) {
        return "Recovery snapshots: " + count + ". Restore now? (y/N)";
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
        boolean replacePlaceholder = sessionContext.presentations().size() == 1 && tabs.size() == 1
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
        EditorTab replacement = context.presentations().size() == 1 ? newEditorTab() : null;
        if (replacement != null) context.addAll(List.of(replacement));
        context.remove(viewId);
        entry.attachment().uiState().close();
        saveAsSuggestions.remove(((EditorTab) entry.attachment()).tabSession());
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
                hasMatch,
                tab.uiState().hasSearch(),
                tab.uiState().awaitingAnswer()
            ));
        }
        return out;
    }

    List<TabBarView.TabItem> toPresentationTabItems(SessionContext context, boolean[] sourceMatchFlags) {
        List<TabBarView.TabItem> result = new ArrayList<>();
        for (WindowTab tab : context.presentations()) {
            EditorTab source = tab instanceof EditorTab editor ? editor
                : ((PreviewTab) tab).source() instanceof PreviewTab.LiveSource live ? context.source(live.viewId()) : null;
            var resource = source == null ? ((PreviewTab.ClosedSnapshot) ((PreviewTab) tab).source()).source().binding().resource()
                : source.state().getCurrentResource();
            int index = source == null ? -1 : context.indexOf(source.documentSession().viewId());
            boolean match = tab instanceof EditorTab && sourceMatchFlags != null && index >= 0
                && index < sourceMatchFlags.length && sourceMatchFlags[index];
            String qualifier = tab instanceof PreviewTab ? "Preview"
                : context.previewFor(source.documentSession().viewId()) == null ? "" : "Source";
            result.add(new TabBarView.TabItem(JvmDocumentStorage.pathOf(resource), source != null && source.state().isDirty(),
                match, tab.uiState().hasSearch(), tab.uiState().awaitingAnswer(), qualifier));
        }
        return List.copyOf(result);
    }

    List<TopMenuView.TabItem> toPresentationMenuTabs(SessionContext context, boolean[] matchFlags) {
        return toPresentationTabItems(context, matchFlags).stream().map(tab -> new TopMenuView.TabItem(tab.file(),
            tab.dirty(), tab.hasMatch(), tab.searching(), tab.awaitingAnswer(), tab.qualifier())).toList();
    }

    List<TopMenuView.TabItem> toTopMenuTabs(List<EditorTab> tabs, boolean[] matchFlags) {
        List<TopMenuView.TabItem> out = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            boolean hasMatch = matchFlags != null && i < matchFlags.length && matchFlags[i];
            out.add(new TopMenuView.TabItem(
                JvmDocumentStorage.pathOf(tab.state().getCurrentResource()),
                tab.state().isDirty(),
                hasMatch,
                tab.uiState().hasSearch(),
                tab.uiState().awaitingAnswer()
            ));
        }
        return out;
    }

    record EditorTab(
        TuiDocumentSession documentSession,
        RenderPlanBuilder renderPlanBuilder,
        ViewportController viewportController,
        EditorViewNavigation navigation,
        TabUiState uiState,
        PresentationId presentationId,
        PresentationGroupId groupId
    ) implements WindowTab {
        EditorTab(TuiDocumentSession documentSession, RenderPlanBuilder renderPlanBuilder) {
            this(documentSession, renderPlanBuilder, new ViewportController(), new EditorViewNavigation(documentSession.view().engine(), documentSession::navigate));
        }

        EditorTab(TuiDocumentSession documentSession, RenderPlanBuilder renderPlanBuilder,
                  ViewportController viewportController, EditorViewNavigation navigation) {
            this(documentSession, renderPlanBuilder, viewportController, navigation, new TabUiState());
        }

        EditorTab(TuiDocumentSession documentSession, RenderPlanBuilder renderPlanBuilder,
                  ViewportController viewportController, EditorViewNavigation navigation, TabUiState uiState) {
            this(documentSession, renderPlanBuilder, viewportController, navigation, uiState,
                PresentationId.create(), PresentationGroupId.create());
        }

        EditorTab {
            Objects.requireNonNull(presentationId, "presentationId");
            Objects.requireNonNull(groupId, "groupId");
            if (documentSession == null
                || renderPlanBuilder == null
                || viewportController == null
                || uiState == null
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

        EditorTab withGroup(PresentationGroupId nextGroup) {
            return new EditorTab(documentSession, renderPlanBuilder, viewportController, navigation, uiState,
                presentationId, nextGroup);
        }

        EditorTab withRenderPlanBuilder(RenderPlanBuilder nextBuilder) {
            return new EditorTab(documentSession, nextBuilder, viewportController, navigation, uiState, presentationId, groupId);
        }
    }

    static final class SessionContext {
        private final EditorWorkspace<WindowTab> workspace = new EditorWorkspace<>();
        SessionContext(List<EditorTab> tabs, int activeTabIndex) {
            addAll(tabs);
            activate(activeTabIndex);
        }

        void activate(int nextActiveTabIndex) {
            workspace.selectIndex(Math.max(0, Math.min(nextActiveTabIndex, tabs().size() - 1)));
        }

        void addNewTabAndActivate(EditorTab tab) {
            var next = new ArrayList<>(workspace.presentations());
            next.add(sourcePresentation(tab));
            workspace.replacePresentations(next, tab.presentationId());
        }

        void addAll(List<EditorTab> tabs) {
            var next = new ArrayList<>(workspace.presentations());
            for (var tab : tabs) next.add(sourcePresentation(tab));
            var active = workspace.activePresentation();
            workspace.replacePresentations(next, active == null ? null : active.id());
        }

        private static EditorWorkspace.Presentation<WindowTab> sourcePresentation(EditorTab tab) {
            return new EditorWorkspace.Presentation<>(tab.presentationId(), tab.groupId(),
                new EditorWorkspace.Editing(tab.documentSession().document(), tab.documentSession().viewId()), tab);
        }

        List<EditorTab> tabs() {
            return java.util.Collections.unmodifiableList(new java.util.AbstractList<>() {
                @Override public EditorTab get(int index) { return (EditorTab) workspace.entries().get(index).attachment(); }
                @Override public int size() { return workspace.entries().size(); }
            });
        }
        List<WindowTab> presentations() { return workspace.presentationAttachments(); }
        WindowTab activePresentation() { return workspace.activePresentation().attachment(); }
        int activePresentationIndex() { return workspace.activePresentationIndex(); }
        void activatePresentation(int index) { workspace.selectPresentationIndex(index); }
        boolean activatePresentation(PresentationId id) {
            if (workspace.find(id) == null) return false;
            workspace.select(id); return true;
        }
        EditorTab source(ViewId viewId) {
            var entry = workspace.find(viewId);
            return entry == null ? null : (EditorTab) entry.attachment();
        }
        EditorTab active() {
            var tab = activePresentation();
            if (tab instanceof EditorTab editor) return editor;
            var source = ((PreviewTab) tab).source();
            return source instanceof PreviewTab.LiveSource live ? source(live.viewId()) : null;
        }
        ViewId activeViewId() { return active() == null ? null : active().documentSession().viewId(); }
        PreviewTab previewFor(ViewId viewId) {
            EditorTab source = source(viewId);
            if (source == null) return null;
            for (var tab : presentations()) {
                if (tab instanceof PreviewTab preview && preview.source() instanceof PreviewTab.LiveSource live
                    && live.documentId().equals(source.documentSession().document().documentId())) return preview;
            }
            return null;
        }
        private PreviewTab linkedPreview(ViewId viewId) {
            PreviewTab preview = previewFor(viewId);
            return preview != null && ((PreviewTab.LiveSource) preview.source()).viewId().equals(viewId) ? preview : null;
        }
        void addPreview(PreviewTab preview, ViewId sourceView) {
            EditorTab editor = Objects.requireNonNull(source(sourceView));
            if (previewFor(sourceView) != null) throw new IllegalStateException("Preview already exists");
            workspace.insert(workspace.indexOf(editor.presentationId()) + 1,
                new EditorWorkspace.Presentation<>(preview.presentationId(), preview.groupId(),
                    EditorWorkspace.Projection.INSTANCE, preview), false);
        }
        void updatePreview(PreviewTab preview) {
            workspace.updatePresentationAttachment(preview.presentationId(), preview);
        }
        void removePreview(PresentationId id) { workspace.removePresentation(id); }
        WindowTab presentation(PresentationId id) {
            var entry = workspace.find(id); return entry == null ? null : entry.attachment();
        }

        int indexOf(ViewId viewId) { return workspace.indexOf(viewId); }

        void remove(ViewId viewId) {
            EditorTab editor = source(viewId);
            if (editor == null) return;
            PreviewTab preview = linkedPreview(viewId);
            if (preview == null) { workspace.remove(viewId); return; }
            var captured = preview.uiState().preview.sourceForFreeze();
            var current = editor.documentSession().document().freezeSource();
            var frozen = preview.freeze(captured.withBinding(current.binding(), current.reopenMode()));
            var next = new ArrayList<EditorWorkspace.Presentation<WindowTab>>();
            for (var item : workspace.presentations()) {
                if (item.id().equals(editor.presentationId())) continue;
                next.add(item.id().equals(preview.presentationId())
                    ? new EditorWorkspace.Presentation<>(frozen.presentationId(), frozen.groupId(),
                        EditorWorkspace.Projection.INSTANCE, frozen) : item);
            }
            var active = workspace.activePresentation().id();
            workspace.replacePresentations(next, active.equals(editor.presentationId()) ? frozen.presentationId() : active);
        }

        EditorTab linkPreview(PreviewTab preview, EditorTab editor) { return linkPreview(preview, editor, true); }

        EditorTab linkPreview(PreviewTab preview, EditorTab editor, boolean activate) {
            PresentationId selected = workspace.activePresentation().id();
            PreviewTab existing = previewFor(editor.documentSession().viewId());
            if (existing != null && !existing.presentationId().equals(preview.presentationId())) {
                EditorTab paired = Objects.requireNonNull(source(((PreviewTab.LiveSource) existing.source()).viewId()));
                workspace.removePresentation(preview.presentationId());
                if (activate) workspace.select(paired.presentationId());
                preview.close();
                return paired;
            }
            EditorTab grouped = editor.withGroup(preview.groupId());
            PreviewTab linked = preview.relink(grouped);
            var next = new ArrayList<EditorWorkspace.Presentation<WindowTab>>();
            for (var item : workspace.presentations()) {
                if (item.id().equals(editor.presentationId())) continue;
                if (item.id().equals(preview.presentationId())) {
                    next.add(sourcePresentation(grouped));
                    next.add(new EditorWorkspace.Presentation<>(linked.presentationId(), linked.groupId(),
                        EditorWorkspace.Projection.INSTANCE, linked));
                } else next.add(item);
            }
            workspace.replacePresentations(next, activate ? grouped.presentationId() : selected);
            return grouped;
        }

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
            EditorTab old = tabs().get(index);
            PreviewTab preview = linkedPreview(old.documentSession().viewId());
            EditorTab grouped = preview == null ? replacement : replacement.withGroup(old.groupId());
            var next = new ArrayList<EditorWorkspace.Presentation<WindowTab>>();
            for (var item : workspace.presentations()) {
                if (item.id().equals(old.presentationId())) next.add(sourcePresentation(grouped));
                else if (preview != null && item.id().equals(preview.presentationId())) {
                    PreviewTab linked = preview.relink(grouped);
                    next.add(new EditorWorkspace.Presentation<>(linked.presentationId(), linked.groupId(),
                        EditorWorkspace.Projection.INSTANCE, linked));
                } else next.add(item);
            }
            var selected = workspace.activePresentation().id();
            workspace.replacePresentations(next, selected.equals(old.presentationId()) ? grouped.presentationId() : selected);
        }

        void replaceActiveRenderPlanBuilder(RenderPlanBuilder nextRenderPlanBuilder) {
            update(active().withRenderPlanBuilder(nextRenderPlanBuilder));
        }

        int activeTabIndex() {
            ViewId view = activeViewId();
            return view == null ? -1 : workspace.indexOf(view);
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
        private long revision;

        long revision() { return revision; }

        private boolean visible() {
            return message != null;
        }

        void success(String message) {
            revision++;
            this.message = message;
            this.kind = Kind.SUCCESS;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        void info(String message) {
            revision++;
            this.message = message;
            this.kind = Kind.INFO;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        void warning(String message) {
            revision++;
            this.message = message;
            this.kind = Kind.WARNING;
            this.expiresAtNanos = System.nanoTime() + NOTIFY_DURATION_NANOS;
        }

        void persistentWarning(String message) {
            revision++;
            this.message = message;
            this.kind = Kind.WARNING;
            this.expiresAtNanos = 0L;
        }

        void error(String message) {
            revision++;
            this.message = message;
            this.kind = Kind.ERROR;
            this.expiresAtNanos = 0L;
        }

        boolean requiresAttention() {
            return kind == Kind.ERROR || kind == Kind.WARNING;
        }

        boolean isError() { return kind == Kind.ERROR; }

        void clearErrorOnUserAction() {
            if (kind == Kind.ERROR) {
                clear();
            }
        }

        void clear() {
            revision++;
            this.message = null;
            this.kind = null;
            this.expiresAtNanos = 0L;
        }

        CellStyle style() {
            if (kind == null) {
                return null;
            }
            return switch (kind) {
                case SUCCESS -> styles.windows().notifySuccess();
                case INFO -> styles.windows().notifyInfo();
                case WARNING -> styles.windows().notifyWarning();
                case ERROR -> styles.windows().notifyError();
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
        return resolveHighlighter(file, PresentationSyntaxPolicy.source(true, false, true, theme));
    }

    LineHighlighter resolveHighlighter(Path file, PresentationSyntaxPolicy.Source syntax) {
        return new SemanticHighlighterAdapter(MarkdownOpenPolicy.isMarkdown(file)
            ? RegexHighlighter.forMarkdownDocument(syntax.codeLanguages())
            : file == null ? RegexHighlighter.withDefaults()
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
            // A restored stop request must not pre-cancel the final NIO checkpoint.
            boolean interrupted = Thread.interrupted();
            try {
                String warning = recovery.finishSession(tabs.get(), exitConfirmed && drainFailure == null);
                if (warning != null) reportWarning.accept(warning);
            } catch (RuntimeException | Error recoveryFailure) {
                if (drainFailure == null) throw recoveryFailure;
                drainFailure.addSuppressed(recoveryFailure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    interface RecoveryPersistence extends dev.tuink.editor.session.recovery.RecoveryPersistence<RecoverySnapshot> {
        default RecoveryStore.DiscoveryResult discoverAvailable() throws IOException {
            return new RecoveryStore.DiscoveryResult(List.of(), List.of());
        }
    }

    /** Terminal checkpoint envelope and lifecycle/executor adapter. Coordination lives in editor-session. */
    static final class RecoveryAutosaveManager {
        private final RecoveryCoordinator<RecoverySnapshot> coordinator;
        private final RecoveryPersistence persistence;
        private final java.util.concurrent.atomic.AtomicReference<String> discoveryCleanupWarning =
            new java.util.concurrent.atomic.AtomicReference<>();
        private final Map<EditorDocumentSession, TuiRecoveryParticipant> participants =
            new IdentityHashMap<>();
        private final ExecutorService ownedWorker;

        private RecoveryAutosaveManager(RecoveryStore store) {
            this(new RecoveryPersistence() {
                @Override public void save(String id, RecoverySnapshot snapshot) throws IOException { store.save(id, snapshot); }
                @Override public void delete(String id) throws IOException { store.delete(id); }
                @Override public void release(String id) throws IOException { store.release(id); }
                @Override public RecoveryStore.DiscoveryResult discoverAvailable() throws IOException {
                    return store.discoverAvailable();
                }
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
            persistence = Objects.requireNonNull(store, "store");
            coordinator = new RecoveryCoordinator<>(store, executor, clock);
            ownedWorker = ownsWorker ? (ExecutorService) executor : null;
        }

        RecoveryStore.DiscoveryResult discoverAvailable() throws IOException {
            return persistence.discoverAvailable();
        }

        /** Worker-only disposal of newly claimed drafts whose prompt was never published. */
        void releaseDiscovered(RecoveryStore.DiscoveryResult discovered) {
            if (discovered == null) return;
            for (var entry : discovered.entries()) {
                try {
                    persistence.release(entry.id());
                } catch (IOException | RuntimeException failure) {
                    discoveryCleanupWarning.compareAndSet(null,
                        "Could not release recovery draft " + entry.id() + "; its data was kept: " + failure.getMessage());
                }
            }
        }

        String takeDiscoveryCleanupWarning() { return discoveryCleanupWarning.getAndSet(null); }

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

    private void applyTheme(UiStyles.Theme theme) {
        this.theme = theme == null ? UiStyles.Theme.CYAN : theme;
        this.styles = UiStyles.forTheme(this.theme);
    }

    private void printUsage(PrintStream output) {
        TerminalDiagnostics.line(output, "Usage: tuink [options] [--] [file]");
        TerminalDiagnostics.line(output, "Development: ./gradlew --console=plain :app:run -PappArgs=\"[options] [--] [file]\"");
        TerminalDiagnostics.line(output, "Options:");
        TerminalDiagnostics.line(output, "  --no-mouse       Disable mouse tracking and mouse interactions");
        TerminalDiagnostics.line(output, "  --no-alt-screen  Do not use alternate screen buffer");
        TerminalDiagnostics.line(output, "  --no-color       Render in monochrome (no ANSI colors, keep text attributes)");
        TerminalDiagnostics.line(output, "  --theme=<name>   UI palette: blue, cyan (default), monochrome");
        TerminalDiagnostics.line(output, "  --read           Start in reading mode (editing disabled)");
        TerminalDiagnostics.line(output, "  --open-as-utf16le Interpret the selected BOM-marked file as UTF-16LE");
        TerminalDiagnostics.line(output, "  --open-as=<name> Open an existing file as auto, utf-8, utf-16le, utf-16be,");
        TerminalDiagnostics.line(output, "                   windows-1251, cp866 or koi8-r (also --open-as <name>)");
        TerminalDiagnostics.line(output, "  --profile        Log render timings to stderr every " + PROFILE_LOG_EVERY_FRAMES + " frames");
        TerminalDiagnostics.line(output, "  --profile-file   Create a new profile log file (also enables --profile)");
        TerminalDiagnostics.line(output, "  --file-uri=<uri> Open an ASCII percent-encoded absolute file URI");
        TerminalDiagnostics.line(output, "  --help, -h       Show this help");
        TerminalDiagnostics.line(output, "  --version        Show the application version");
        TerminalDiagnostics.line(output, "  --              End options; the following argument is a literal file path");
    }

    private boolean shouldForceExecTerminalProvider() {
        return "exec".equalsIgnoreCase(System.getProperty("dev.tuiframe.provider", ""));
    }

    final class FrameProfiler {
        private final LongSupplier clock;
        private long frameStartNanos;
        long searchNanos;
        private long buildNanos;
        private long compositionNanos;
        private long rasterNanos;
        private long diffNanos;
        private long outputNanos;
        private long totalNanos;
        private long inputNanos;
        private long responseNanos;
        private long pendingInputNanos;
        private long firstPendingInputNanos;
        private long pendingInputEvents;
        private long inputEvents;
        private int emittedCharacters;
        private boolean rasterCacheAvailable;
        private long rasterMetricsBefore, rasterMissesBefore, rasterReusedBefore;
        private long metricsBuilds, cacheMisses, rowsReused, retainedCacheBytes;
        private String surface = "source";
        private boolean ready;
        private long frameIndex;
        private long stopwatchStartNanos;
        private PrintWriter out = new PrintWriter(System.err, true);
        private boolean closeOut;

        FrameProfiler() {
            this(System::nanoTime);
        }

        FrameProfiler(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        void setOutput(PrintWriter out, boolean closeOut) {
            if (out == null) {
                return;
            }
            this.out = out;
            this.closeOut = closeOut;
        }

        long inputStarted() {
            return clock.getAsLong();
        }

        void inputHandled(long started) {
            if (pendingInputEvents == 0L) firstPendingInputNanos = started;
            pendingInputNanos += Math.max(0L, clock.getAsLong() - started);
            pendingInputEvents++;
        }

        void beginFrame(String surface) {
            this.surface = Objects.requireNonNull(surface, "surface");
            frameStartNanos = clock.getAsLong();
            stopwatchStartNanos = frameStartNanos;
            searchNanos = 0L;
            buildNanos = 0L;
            compositionNanos = 0L;
            rasterNanos = 0L;
            diffNanos = 0L;
            outputNanos = 0L;
            emittedCharacters = 0;
            rasterCacheAvailable = false;
            ready = false;
        }

        void compositionFinished(boolean ready) {
            compositionNanos = Math.max(0L, clock.getAsLong() - frameStartNanos);
            this.ready = ready;
        }

        /** Samples only the admitted geometry/prepared-row work inside this raster pass. */
        void beginRasterCache(long builds, long misses, long reused) {
            rasterMetricsBefore = builds;
            rasterMissesBefore = misses;
            rasterReusedBefore = reused;
        }

        void endRasterCache(long builds, long misses, long reused, long retainedBytes) {
            metricsBuilds = builds - rasterMetricsBefore;
            cacheMisses = misses - rasterMissesBefore;
            rowsReused = reused - rasterReusedBefore;
            retainedCacheBytes = retainedBytes;
            rasterCacheAvailable = true;
        }

        void presented(ScreenPresenter.PresentResult result) {
            diffNanos = result.patchNanos();
            outputNanos = result.outputNanos();
            emittedCharacters = result.emittedCharacters();
        }

        long stopwatchNanos() {
            long now = clock.getAsLong();
            long delta = now - stopwatchStartNanos;
            stopwatchStartNanos = now;
            return delta;
        }

        void endFrame(boolean rendered) {
            long finished = clock.getAsLong();
            inputNanos = pendingInputNanos;
            inputEvents = pendingInputEvents;
            responseNanos = inputEvents == 0L ? 0L : Math.max(0L, finished - firstPendingInputNanos);
            // An input with no visible effect must not inflate a later frame's response.
            pendingInputNanos = 0L;
            pendingInputEvents = 0L;
            if (!rendered) {
                return;
            }
            totalNanos = Math.max(0L, finished - frameStartNanos);
            frameIndex++;
            if (frameIndex % PROFILE_LOG_EVERY_FRAMES == 0) {
                log();
            }
        }

        private void log() {
            out.printf(
                Locale.ROOT,
                "frame=%d build=%.2fms raster=%.2fms diff=%.2fms search=%.2fms total=%.2fms"
                    + " compose=%.2fms input=%.2fms output=%.2fms response=%.2fms chars=%d events=%d heap=%d surface=%s ready=%d",
                frameIndex,
                buildNanos / 1_000_000.0,
                rasterNanos / 1_000_000.0,
                diffNanos / 1_000_000.0,
                searchNanos / 1_000_000.0,
                totalNanos / 1_000_000.0,
                compositionNanos / 1_000_000.0,
                inputNanos / 1_000_000.0,
                outputNanos / 1_000_000.0,
                responseNanos / 1_000_000.0,
                emittedCharacters,
                inputEvents,
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                surface,
                ready ? 1 : 0
            );
            if (rasterCacheAvailable) out.printf(Locale.ROOT,
                " metrics_builds=%d cache_misses=%d rows_reused=%d retained_cache_bytes=%d",
                metricsBuilds, cacheMisses, rowsReused, retainedCacheBytes);
            out.println();
            out.flush();
        }

        private void close() {
            if (closeOut) {
                out.close();
            }
        }
    }
}
