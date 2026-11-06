package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.render.RenderPlan;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.keymap.Keymap;
import dev.tuink.tui.keymap.TuinkKeymap;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.EditorView;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/** Real editor event/frame pipeline with an in-memory terminal and recovery port. */
final class EditorWindowFixture implements AutoCloseable {
    final TerminalEditorApp host = new TerminalEditorApp();
    final List<TerminalEditorApp.EditorTab> tabs;
    final TerminalEditorApp.SessionContext context;
    TerminalEditorApp.NotificationState notification = host.new NotificationState();
    TextField query = new TextField(TextField.PastePolicy.PRESERVE_TABS);
    TextField replacement = new TextField(TextField.PastePolicy.PRESERVE_TABS);
    TextField filename = new TextField(TextField.PastePolicy.PRESERVE_TABS);
    final EditorView editorView = new EditorView();
    final TopMenuController menu = new TopMenuController();
    final Keymap<KeyStroke> keymap = TuinkKeymap.defaults();
    final TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.idle());
    OpenFileTreeController tree;
    final UiSessionState ui;
    final AppSettingsSession settingsSession;
    final EditorWindowController controller;
    final Path document;
    final ObservedRasterizer renderer;
    final Map<String, RecoverySnapshot> recoverySnapshots = new HashMap<>();
    final TerminalEditorApp.RecoveryAutosaveManager recoveryManager;
    final DocumentIoController documentIo;
    boolean mouseAvailable = true;
    int width = 80;
    int height = 24;

    EditorWindowFixture(Path directory, boolean reading) throws IOException {
        this(directory, reading, IntStream.range(0, 120)
            .mapToObj(index -> "ROW%04d|".formatted(index) + "abcdefgh ".repeat(30)).toList());
    }

    EditorWindowFixture(Path directory, boolean reading, List<String> lines) throws IOException {
        this(directory, reading, lines, new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
    }

    EditorWindowFixture(Path directory, String documentName, boolean reading, List<String> lines) throws IOException {
        this(directory, documentName, reading, lines, Runnable::run);
    }

    EditorWindowFixture(Path directory, String documentName, boolean reading, List<String> lines,
                        Executor documentExecutor) throws IOException {
        this(directory, reading, lines, new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])),
            List.of(), null, documentExecutor, Runnable::run, documentName);
    }

    EditorWindowFixture(Path directory, boolean reading, List<String> lines, Executor documentExecutor) throws IOException {
        this(directory, reading, lines,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])),
            List.of(), null, documentExecutor, Runnable::run);
    }

    EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                        Executor documentExecutor, Executor recoveryExecutor) throws IOException {
        this(directory, reading, lines,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])),
            List.of(), null, documentExecutor, recoveryExecutor);
    }

    EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                        List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries) throws IOException {
        this(directory, reading, lines,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])), startupRecoveryEntries);
    }

    EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                        List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                        TerminalEditorApp.RecoveryPersistence recoveryPersistence) throws IOException {
        this(directory, reading, lines,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])),
            startupRecoveryEntries, recoveryPersistence);
    }

    EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                        List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                        TerminalEditorApp.RecoveryPersistence recoveryPersistence, Executor documentExecutor) throws IOException {
        this(directory, reading, lines,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])),
            startupRecoveryEntries, recoveryPersistence, documentExecutor, Runnable::run);
    }

    EditorWindowFixture(Path directory, List<String> lines, AppSettingsSession settingsSession) throws IOException {
        this(directory, settingsSession.initialSettings().readingMode(), lines, settingsSession);
    }

    EditorWindowFixture(Path directory, List<String> lines, AppSettingsSession settingsSession,
                        List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                        TerminalEditorApp.RecoveryPersistence recoveryPersistence) throws IOException {
        this(directory, "document.txt", lines, settingsSession, startupRecoveryEntries, recoveryPersistence);
    }

    EditorWindowFixture(Path directory, String documentName, List<String> lines, AppSettingsSession settingsSession,
                        List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                        TerminalEditorApp.RecoveryPersistence recoveryPersistence) throws IOException {
        this(directory, settingsSession.initialSettings().readingMode(), lines, settingsSession,
            startupRecoveryEntries, recoveryPersistence, Runnable::run, Runnable::run, documentName);
    }

    private EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                                AppSettingsSession settingsSession) throws IOException {
        this(directory, reading, lines, settingsSession, List.of());
    }

    private EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                                AppSettingsSession settingsSession,
                                List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries) throws IOException {
        this(directory, reading, lines, settingsSession, startupRecoveryEntries, null);
    }

    private EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                                AppSettingsSession settingsSession,
                                List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                                TerminalEditorApp.RecoveryPersistence recoveryPersistence) throws IOException {
        this(directory, reading, lines, settingsSession, startupRecoveryEntries, recoveryPersistence,
            Runnable::run, Runnable::run);
    }

    EditorWindowFixture(Path directory, String documentName, List<String> lines,
                        AppSettingsSession settingsSession) throws IOException {
        this(directory, settingsSession.initialSettings().readingMode(), lines, settingsSession,
            List.of(), null, Runnable::run, Runnable::run, documentName);
    }

    private EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                                AppSettingsSession settingsSession,
                                List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                                TerminalEditorApp.RecoveryPersistence recoveryPersistence,
                                Executor documentExecutor, Executor recoveryExecutor) throws IOException {
        this(directory, reading, lines, settingsSession, startupRecoveryEntries, recoveryPersistence,
            documentExecutor, recoveryExecutor, "document.txt");
    }

    private EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                                AppSettingsSession settingsSession,
                                List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                                TerminalEditorApp.RecoveryPersistence recoveryPersistence,
                                Executor documentExecutor, Executor recoveryExecutor, String documentName) throws IOException {
        this(directory, reading, lines, settingsSession, startupRecoveryEntries, recoveryPersistence,
            documentExecutor, recoveryExecutor, documentName, null);
    }

    EditorWindowFixture(Path directory, String documentName, List<String> lines,
                        dev.tuink.editor.clipboard.ClipboardPort clipboard) throws IOException {
        this(directory, documentName, lines, clipboard, Runnable::run);
    }

    EditorWindowFixture(Path directory, String documentName, List<String> lines,
                        dev.tuink.editor.clipboard.ClipboardPort clipboard, Executor documentExecutor) throws IOException {
        this(directory, false, lines, new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])),
            List.of(), null, documentExecutor, Runnable::run, documentName, clipboard);
    }

    private EditorWindowFixture(Path directory, boolean reading, List<String> lines,
                                AppSettingsSession settingsSession,
                                List<? extends RecoveryStore.PendingSnapshot> startupRecoveryEntries,
                                TerminalEditorApp.RecoveryPersistence recoveryPersistence,
                                Executor documentExecutor, Executor recoveryExecutor, String documentName,
                                dev.tuink.editor.clipboard.ClipboardPort clipboard) throws IOException {
        this.settingsSession = settingsSession;
        Files.createDirectories(directory);
        document = directory.resolve(documentName);
        Files.writeString(document, String.join("\n", lines));
        var initial = host.newEditorTab();
        initial.tabSession().engine().open(JvmDocumentStorage.resource(document));
        context = new TerminalEditorApp.SessionContext(List.of(initial), 0);
        tabs = context.tabs();
        tree = new OpenFileTreeController(directory, true, false);
        AppSettings settings = settingsSession.initialSettings();
        host.theme = settings.theme();
        host.styles = UiStyles.forTheme(host.theme);
        ui = UiSessionState.from(settings);
        ui.readingModeSetting = reading;
        renderer = new ObservedRasterizer(host.theme);
        ScreenPresenter presenter = new ScreenPresenter(new PrintWriter(new StringWriter()), TerminalCapabilities.DEFAULT);
        TerminalEditorApp.RecoveryPersistence persistence = recoveryPersistence != null ? recoveryPersistence
            : new TerminalEditorApp.RecoveryPersistence() {
                @Override
                public void save(String entryId, RecoverySnapshot snapshot) {
                    recoverySnapshots.put(entryId, snapshot);
                }

                @Override
                public void delete(String entryId) {
                    recoverySnapshots.remove(entryId);
                }

                @Override
                public void release(String entryId) {
                    // The in-memory port has no process ownership to release.
                }
            };
        recoveryManager = new TerminalEditorApp.RecoveryAutosaveManager(persistence, recoveryExecutor);
        documentIo = new DocumentIoController(documentExecutor);
        for (RecoveryStore.PendingSnapshot entry : startupRecoveryEntries) {
            if (entry instanceof RecoveryStore.Entry loaded) {
                recoverySnapshots.put(entry.id(), loaded.snapshot());
            }
        }
        controller = new EditorWindowController(host, new EditorWindowController.Resources(
            new CommandRouter(keymap), editorView, loop,
            startupRecoveryEntries.isEmpty() ? UiModalState.none() : UiModalState.recoveryConfirm(),
            SearchController.SearchUiOptions.DEFAULT,
            notification, tree, recoveryManager, host.new RenderFrameController(renderer, editorView, true, presenter),
            renderer, filename, new SaveAsSuggestionStore(), presenter, query, replacement, context,
            new ArrayList<>(startupRecoveryEntries),
            new EditorWindowController.TerminalAccess(() -> width, () -> height, enabled -> {}, () -> mouseAvailable),
            menu, ui, settingsSession, documentIo), clipboard);
        try {
            frame();
        } catch (RuntimeException | Error failure) {
            tree.close();
            throw failure;
        }
    }

    TerminalEditorApp.EditorTab active() {
        return context.active();
    }

    WindowTab selected() { return context.activePresentation(); }

    MarkdownPreviewState preview() {
        if (selected() instanceof PreviewTab preview) return preview.uiState().preview;
        var source = (TerminalEditorApp.EditorTab) selected();
        var preview = context.previewFor(source.documentSession().viewId());
        return preview == null ? null : preview.uiState().preview;
    }

    int restore(List<RecoveryStore.Entry> entries) {
        for (RecoveryStore.Entry entry : entries) {
            recoverySnapshots.put(entry.id(), entry.snapshot());
        }
        return host.restoreRecoveredTabs(entries, context, recoveryManager, 0L).restoredCount();
    }

    void event(TuiInputEvent event) {
        // The production runner synchronizes owner state before polling input.
        bindActiveUi();
        controller.prepareTurn();
        controller.handlePoll(new TuiEventLoop.Poll<>(event, event instanceof ResizeEvent, false));
        bindActiveUi();
        frame();
    }

    void pointer(PointerEvent.Kind kind, int row, int column) {
        event(new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of()));
    }

    void wheel(PointerEvent.Kind kind, int count) {
        for (int index = 0; index < count; index++) {
            event(new PointerEvent(10, 15, kind, PointerEvent.Button.NONE, Set.of()));
        }
    }

    void resize(int rows, int columns) {
        height = rows;
        width = columns;
        event(new ResizeEvent(rows, columns));
    }

    void frame() {
        bindActiveUi();
        controller.prepareTurn();
        bindActiveUi();
        loop.invalidate();
        TuiEventLoop.FrameTicket ticket = loop.beginFrame();
        assertNotNull(ticket);
        boolean rendered = false;
        boolean completed = false;
        try {
            rendered = controller.renderFrame(ticket);
            completed = true;
        } finally {
            loop.endFrame(ticket, completed && rendered);
        }
        controller.frameEnded(rendered);
        bindActiveUi();
    }

    private void bindActiveUi() {
        TabUiState state = selected().uiState();
        query = state.searchQueryInput;
        replacement = state.searchReplaceInput;
        filename = state.saveAsInput;
        if (state.openFileTree != null) tree = state.openFileTree;
        notification = controller.notificationState();
    }

    /** Exercise semantic commands through a user key binding and the real router. */
    void command(TuinkCommand command) {
        KeyStroke alias = KeyStroke.of(KeyStroke.Key.F12);
        keymap.rebind(alias, CommandInvocation.of(command.id()));
        event(alias);
    }

    /** Select through real keyboard routing, resolving the descriptor by stable command identity. */
    void selectMenuCommand(TuinkCommand command) {
        int target = menuCommandIndex(command);
        event(KeyStroke.of(KeyStroke.Key.HOME));
        for (int attempt = 0; attempt < TuinkCommand.values().length
                && menu.state().selectedEntryIndex() != target; attempt++) {
            event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
        }
        assertTrue(menu.state().selectedEntryIndex() == target, "Menu command must be keyboard-selectable: " + command);
    }

    int menuCommandIndex(TuinkCommand command) {
        assertTrue(menu.isOpen(), "A menu must be open before selecting its command");
        var view = TopMenuView.build(ui, List.of(), -1, host.theme.label(), new CommandRouter(keymap),
            menu.state(), controller.activeSurface());
        var entries = view.menus().get(menu.state().openMenuIndex()).entries();
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index) instanceof dev.tuiframe.widget.MenuBar.Item<?> item
                && ((CommandInvocation) item.value()).id().equals(command.id())) return index;
        }
        throw new AssertionError("Command is absent from the open menu: " + command);
    }

    void focusSearch(SearchFocus target) {
        if (target == null) {
            // Explicitly cleared focus is a public FocusManager state. Only the
            // stable scope identity is needed; no production geometry is copied.
            host.searchFocusManager().clear(new FocusScope<SearchFocus, Object>(
                TerminalEditorApp.SEARCH_SCOPE_ID, FocusResetPolicy.CLEARED, List.of(), List.of(), List.of()));
            frame();
            return;
        }
        for (int step = 0; step <= SearchFocus.values().length; step++) {
            if (!selected().uiState().searchEditorFocused && host.searchFocusManager().focused().orElse(null) == target) {
                return;
            }
            event(KeyStroke.of(KeyStroke.Key.TAB));
        }
        throw new AssertionError("Search focus not reachable: " + target);
    }

    void focusDocument() {
        for (int step = 0; step <= SearchFocus.values().length; step++) {
            if (selected().uiState().searchEditorFocused) {
                return;
            }
            event(KeyStroke.of(KeyStroke.Key.TAB));
        }
        throw new AssertionError("Document focus not reachable from search");
    }

    String screenRow(int row) {
        CellBuffer frame = screenCells();
        assertNotNull(frame);
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < frame.cols(); column++) {
            text.append(frame.get(row, column).grapheme());
        }
        return text.toString();
    }

    String promptText() {
        if (controller.previewFrame() != null) return java.util.Objects.requireNonNullElse(controller.previewPromptText(), "");
        return renderer.plan == null || renderer.plan.interactiveBarText() == null ? "" : renderer.plan.interactiveBarText();
    }

    CellStyle screenStyle(int row, int column) {
        CellBuffer frame = screenCells();
        assertNotNull(frame);
        return frame.get(row, column).style();
    }

    private CellBuffer screenCells() {
        var preview = controller.previewFrame();
        return preview == null ? renderer.frame : preview.cells();
    }

    int rowStartingWith(String label) {
        for (int row = 0; row < height; row++) {
            if (screenRow(row).startsWith(label)) {
                return row;
            }
        }
        throw new AssertionError("No visible row starts with " + label);
    }

    /** Locate a painted scrollbar rather than reproducing its layout math. */
    Point verticalThumb(boolean documentScrollbar) {
        CellBuffer frame = screenCells();
        assertNotNull(frame);
        int lastColumn = documentScrollbar ? width - 1 : width - 2;
        int firstColumn = documentScrollbar ? lastColumn : 1;
        for (int column = lastColumn; column >= firstColumn; column--) {
            int thumbRow = -1;
            boolean track = false;
            for (int row = 1; row < height - 1; row++) {
                String cell = frame.get(row, column).grapheme();
                if (cell.equals("█") && thumbRow < 0) {
                    thumbRow = row;
                }
                track |= cell.equals("│");
            }
            if (thumbRow >= 0 && track) {
                return new Point(column, thumbRow);
            }
        }
        throw new AssertionError("No visible vertical scrollbar thumb");
    }

    void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            frame();
            Thread.sleep(5L);
        }
        assertTrue(ready.getAsBoolean(), "asynchronous fixture did not become ready");
        frame();
    }

    @Override
    public void close() {
        try {
            controller.closeDocumentIo();
        } finally {
            try { renderer.close(); }
            finally { tree.close(); }
        }
    }

    private static final class ObservedRasterizer extends RenderRasterizer {
        private CellBuffer frame;
        private RenderPlan plan;

        private ObservedRasterizer(UiStyles.Theme theme) {
            super(1, theme);
        }

        @Override
        public CellBuffer rasterize(RenderPlan plan, int rows, int columns, int contentTopRow) {
            this.plan = plan;
            frame = super.rasterize(plan, rows, columns, contentTopRow);
            return frame;
        }
    }
}
