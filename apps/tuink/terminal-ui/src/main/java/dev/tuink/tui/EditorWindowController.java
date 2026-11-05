package dev.tuink.tui;

import dev.tuink.editor.language.LanguageId;
import dev.tuink.editor.clipboard.ClipboardPort;
import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.core.EditorEngine;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.core.EditorState;
import dev.tuink.core.LanguageProfiles;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.cell.Canvas;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuiframe.runtime.TuiApplicationRunner;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.DialogInputPolicy;
import dev.tuiframe.widget.DialogMode;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.ViewportController;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Owns frame preparation and event handling for one terminal editor window. */
final class EditorWindowController implements TuiApplicationRunner.Frontend<TuiInputEvent> {

    private static final long SYNTAX_RETRY_DELAY_NANOS = 16_000_000L;
    private static final Object DOCUMENT_POINTER_SCOPE = new Object();
    private static final Object PREVIEW_POINTER_SCOPE = new Object();
    private static final Object PREVIEW_SEARCH_SCOPE = new Object();
    private static final Object HELP_CONTENT_POINTER_SCOPE = new Object();
    private final WindowPointerCapture pointerCapture = new WindowPointerCapture();
    private final TerminalEditorApp host;
    private final CommandRouter commandRouter;
    private final EditorView editorView;
    private final TuiEventLoop<TuiInputEvent> eventLoop;
    private final TerminalEditorApp.RecoveryAutosaveManager recoveryAutosave;
    private final TerminalEditorApp.RenderFrameController renderFrameController;
    private final RenderRasterizer renderer;
    private final SaveAsSuggestionStore saveAsSuggestions;
    private final ScreenPresenter screenPresenter;
    private final TerminalEditorApp.SessionContext sessionContext;
    private final List<RecoveryStore.PendingSnapshot> startupRecoveryEntries;
    private final List<TerminalEditorApp.EditorTab> tabs;
    private final TerminalAccess terminal;
    private final TopMenuController topMenu;
    private final UiSessionState uiSessionState;
    private final AppSettingsSession settingsSession;
    private final DocumentIoController documentIo;
    private final SystemClipboardController systemClipboard;
    private final DocumentFormatWorkflow formatWorkflow;
    private final MarkdownPreviewService markdownPreview;
    private final SnapshotSaveController snapshotSaves;
    private final java.util.Map<ViewId, PresentationId> savePresentationOwners = new java.util.HashMap<>();
    private Frame lastPreviewFrame;
    private String lastPreviewPrompt;
    private ApplicationCloseWorkflow.State<UiRecipient, DocumentIoController.Operation> closeState = ApplicationCloseWorkflow.State.open();
    private SaveWorkflow.SaveRequest pendingSave;
    private FilePickerOpenWorkflow.OpenRequest pendingOpen;
    private boolean disposed;
    private Object pendingRecoveryDiscovery;

    EditorWindowController(TerminalEditorApp host, Resources resources) {
        this(host, resources, null);
    }

    EditorWindowController(TerminalEditorApp host, Resources resources,
        ClipboardPort clipboardPort) {
        this.host = Objects.requireNonNull(host, "host");
        this.commandRouter = resources.commandRouter();
        this.editorView = resources.editorView();
        this.eventLoop = resources.eventLoop();
        this.systemClipboard = new SystemClipboardController(host.clipboard, clipboardPort,
            eventLoop::invalidate, message -> {
                if (!notice().isError()) notice().warning(message);
            });
        this.markdownPreview = new MarkdownPreviewService(eventLoop::invalidate);
        this.recoveryAutosave = resources.recoveryAutosave();
        this.renderFrameController = resources.renderFrameController();
        this.renderer = resources.renderer();
        this.saveAsSuggestions = resources.saveAsSuggestions();
        this.screenPresenter = resources.screenPresenter();
        this.sessionContext = resources.sessionContext();
        this.startupRecoveryEntries = resources.startupRecoveryEntries();
        this.tabs = sessionContext.tabs();
        this.editorView.bindCommands(command -> activeDocument().execute(command));
        this.terminal = resources.terminal();
        this.topMenu = resources.topMenu();
        this.uiSessionState = resources.uiSessionState();
        this.settingsSession = resources.settingsSession();
        this.documentIo = Objects.requireNonNull(resources.documentIo(), "documentIo");
        this.snapshotSaves = new SnapshotSaveController(host, sessionContext, documentIo,
            eventLoop::invalidate, this::snapshotSaveCompleted);
        this.formatWorkflow = new DocumentFormatWorkflow(documentIo);
        TabUiState initial = sessionContext.active().uiState();
        initial.searchQueryInput = resources.searchQueryInput();
        initial.searchReplaceInput = resources.searchReplaceInput();
        initial.saveAsInput = resources.saveAsInput();
        initial.searchUi = resources.initialSearchUi();
        initial.notification = resources.notification();
        initial.openFileTree = resources.openFileTree();
        setModal(resources.initialModalState());
        host.bindTabUi(this::focusUi);
        refreshMarkdownHighlighters();
        applyMarkdownOpenPolicy(sessionContext.active(), true);
        if (!settingsSession.canSavePreferences() && notice().message == null
                && !resources.notification().isError()) {
            notice().persistentWarning(settingsSession.saveUnavailableWarning());
        }
    }

    record Resources(
        CommandRouter commandRouter,
        EditorView editorView,
        TuiEventLoop<TuiInputEvent> eventLoop,
        UiModalState initialModalState,
        SearchController.SearchUiOptions initialSearchUi,
        TerminalEditorApp.NotificationState notification,
        OpenFileTreeController openFileTree,
        TerminalEditorApp.RecoveryAutosaveManager recoveryAutosave,
        TerminalEditorApp.RenderFrameController renderFrameController,
        RenderRasterizer renderer,
        TextField saveAsInput,
        SaveAsSuggestionStore saveAsSuggestions,
        ScreenPresenter screenPresenter,
        TextField searchQueryInput,
        TextField searchReplaceInput,
        TerminalEditorApp.SessionContext sessionContext,
        List<RecoveryStore.PendingSnapshot> startupRecoveryEntries,
        TerminalAccess terminal,
        TopMenuController topMenu,
        UiSessionState uiSessionState,
        AppSettingsSession settingsSession,
        DocumentIoController documentIo
    ) {}

    record TerminalAccess(IntSupplier widthSource, IntSupplier heightSource,
                          Consumer<Boolean> mouseTracking, java.util.function.BooleanSupplier mouseAvailableSource) {
        int width() { return widthSource.getAsInt(); }
        int height() { return heightSource.getAsInt(); }
        void setMouseTracking(boolean enabled) { mouseTracking.accept(enabled); }
        boolean mouseAvailable() { return mouseAvailableSource.getAsBoolean(); }
    }

    private int activeTabIndex;
    private TextModel model;
    private EditorState state;
    private EditorEngine engine;
    private RenderPlanBuilder renderPlanBuilder;
    private ViewportController viewportController;
    private EditorViewNavigation navigation;

    private RenderPlan plan;
    private SearchBarView.Placement searchBarPlacement;
    private ConfirmBarView.Placement confirmBarPlacement;
    private SaveAsBarView.Placement saveAsBarPlacement;
    private FrameComposition.Layers windowLayers;
    private static final Rect NO_BLOCKING_BOUNDS = new Rect(0, 0, 0, 0);
    private int contentTopRow = 1;
    private int contentRows;
    private int contentCols;
    private int contentLeft;
    private int editorPointerRows;
    private final WindowChrome.TabStrip tabStrip = new WindowChrome.TabStrip();
    private TabBarView.Placement tabBarPlacement;
    private TopMenuView.Placement topMenuPlacement;
    private FileTreeView.Placement openFileTreePlacement;
    private OpenFileDialogView.Placement openFileDialogPlacement;
    private TerminalEditorApp.HelpOverlayPlacement helpOverlayPlacement;
    private DocumentFormatDialogView.Placement formatPlacement;
    private DocumentStatusBarView.Placement statusBarPlacement;

    private int rows;
    private int cols;
    private boolean saveAsMode;
    private boolean saveOverwriteConfirmMode;
    private boolean recoveryConfirmMode;
    private boolean quitConfirmMode;
    private boolean closeTabConfirmMode;
    private boolean helpHintsMode;
    private boolean helpLicensesMode;
    private boolean helpAuthorMode;
    private Path pendingOverwritePath;
    private int pendingCloseTabIndex;
    private boolean confirmMode;
    private long syntaxRetryAtNanos = Long.MIN_VALUE;

    private final TabUiState globalUi = new TabUiState();
    private UiModalState windowModal = UiModalState.none();
    private record SaveUiAttempt(SaveWorkflow.SaveRequest request, UiRecipient recipient, long noticeRevision) {}
    private SaveUiAttempt pendingSaveUi;
    private final java.util.Set<TabUiState> knownUiStates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private TabUiState ui() { return sessionContext.activePresentation().uiState(); }

    private TabUiState focusUi() {
        return !windowModal.isNone() ? globalUi : ui();
    }

    private UiModalState modalState() {
        return !windowModal.isNone() ? windowModal : ui().modalState;
    }

    private static boolean globalModal(UiModalState modal) {
        return modal.isQuitConfirm() || modal.isRecoveryConfirm();
    }

    private void setModal(UiModalState modal) {
        Objects.requireNonNull(modal, "modal");
        if (!modalState().equals(modal) && pointerCapture.active())
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
        if (modal.isNone() && !windowModal.isNone()) {
            var message = globalUi.notification;
            if (message != null && message.message != null) {
                var replacement = host.new NotificationState();
                sessionContext.activePresentation().uiState().notification = message;
                globalUi.notification = replacement;
            }
            clearWindowModal();
            return;
        }
        if (globalModal(modal)) {
            windowModal = modal;
        } else {
            clearWindowModal();
            ui().modalState = modal;
        }
    }

    private void clearWindowModal() {
        if (windowModal.isNone()) return;
        globalUi.confirmFocusManager.activeScopeId().ifPresent(globalUi.confirmFocusManager::deactivate);
        globalUi.confirmPromptState = null;
        globalUi.confirmScopeId = null;
        globalUi.blockingFocusOwner = null;
        globalUi.blockingContentFocused = false;
        windowModal = UiModalState.none();
    }

    private TerminalEditorApp.NotificationState notice() {
        TabUiState owner = focusUi();
        if (owner.notification == null) owner.notification = host.new NotificationState();
        return owner.notification;
    }

    TerminalEditorApp.NotificationState notificationState() { return notice(); }

    private OpenFileTreeController openFileTree() {
        if (ui().openFileTree == null) {
            Path file = currentPresentationPath();
            ui().openFileTree = new OpenFileTreeController(FilePickerOpenWorkflow.initialRoot(file), true, true);
        }
        return ui().openFileTree;
    }

    private UiRecipient currentRecipient() {
        return windowModal.isNone() ? UiRecipient.presentation(sessionContext.activePresentation(),
            ui().modalState.saveContinuation() == SaveContinuation.QUIT ? UiRecipient.Flow.WINDOW : UiRecipient.Flow.LOCAL)
            : UiRecipient.window(globalUi, windowModal.targetViewId() == null ? activeEditingView() : windowModal.targetViewId());
    }

    private UiRecipient saveRecipient(ViewId view, UiRecipient.Flow flow) {
        if (view == null) return null;
        if (flow == UiRecipient.Flow.LOCAL) {
            PresentationId id = savePresentationOwners.get(view);
            if (id != null && sessionContext.presentation(id) instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.LiveSource live && live.viewId().equals(view))
                return UiRecipient.presentation(preview, flow);
        }
        var source = sessionContext.source(view);
        return source == null ? null : UiRecipient.presentation(source, flow);
    }

    private boolean current(UiRecipient recipient) {
        return recipient != null && recipient.current(sessionContext, globalUi);
    }

    private boolean background(UiRecipient recipient) {
        return recipient.flow() == UiRecipient.Flow.LOCAL && recipient.scope() == UiRecipient.Scope.PRESENTATION
            && !recipient.presentationId().equals(sessionContext.activePresentation().presentationId());
    }

    private UiModalState modalState(UiRecipient recipient) {
        return recipient.scope() == UiRecipient.Scope.WINDOW ? windowModal : recipient.state().modalState;
    }

    private TerminalEditorApp.NotificationState notice(UiRecipient recipient) {
        if (recipient.state().notification == null) recipient.state().notification = host.new NotificationState();
        return recipient.state().notification;
    }

    private void setModal(UiRecipient recipient, UiModalState modal) {
        if (!current(recipient)) return;
        if ((recipient.flow() == UiRecipient.Flow.WINDOW || !background(recipient))
            && !modalState().equals(modal) && pointerCapture.active())
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
        if (globalModal(modal)) {
            if (recipient.flow() == UiRecipient.Flow.WINDOW || !background(recipient)) windowModal = modal;
        } else {
            if (recipient.flow() == UiRecipient.Flow.WINDOW) {
                if (modal.isNone() && !windowModal.isNone()) handOffWindowNotice(recipient);
                clearWindowModal();
            }
            recipient.state().modalState = modal;
        }
    }

    /** Dismissing a global question may reveal its subject's retained notice. */
    private void handOffWindowNotice(UiRecipient recipient) {
        UiRecipient destination = recipient.scope() == UiRecipient.Scope.PRESENTATION ? recipient
            : saveRecipient(recipient.viewId(), UiRecipient.Flow.WINDOW);
        if (!current(destination) || globalUi.notification == null || globalUi.notification.message == null) return;
        destination.state().notification = globalUi.notification;
        globalUi.notification = host.new NotificationState();
    }

    private int viewIndex(UiRecipient recipient) {
        return recipient.viewId() == null ? -1 : sessionContext.indexOf(recipient.viewId());
    }

    private OpenFileTreeController openFileTree(UiRecipient recipient) {
        TabUiState owner = recipient.state();
        if (owner.openFileTree == null) {
            WindowTab tab = sessionContext.presentation(recipient.presentationId());
            Path path = tab instanceof TerminalEditorApp.EditorTab source ? JvmDocumentStorage.pathOf(source.state().getCurrentResource())
                : tab instanceof PreviewTab preview ? JvmDocumentStorage.pathOf(previewSource(preview).binding().resource()) : null;
            owner.openFileTree = new OpenFileTreeController(FilePickerOpenWorkflow.initialRoot(path), true, true);
        }
        return owner.openFileTree;
    }

    private void synchronizeUiLifetime() {
        var live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TabUiState, Boolean>());
        for (var tab : sessionContext.presentations()) live.add(tab.uiState());
        var removed = knownUiStates.iterator();
        while (removed.hasNext()) {
            TabUiState previous = removed.next();
            if (!live.contains(previous)) { previous.close(); removed.remove(); }
        }
        knownUiStates.addAll(live);
        savePresentationOwners.entrySet().removeIf(entry -> !(sessionContext.presentation(entry.getValue()) instanceof PreviewTab preview)
            || !(preview.source() instanceof PreviewTab.LiveSource source) || !source.viewId().equals(entry.getKey()));
    }

    private void activateTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activatePresentation(sessionContext.presentations().indexOf(tabs.get(index)));
    }

    private void activatePresentation(int index) {
        if (index < 0 || index >= sessionContext.presentations().size()
            || index == sessionContext.activePresentationIndex()) return;
        cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
        cancelDocumentPointerInteraction();
        WindowTab previous = sessionContext.activePresentation();
        if (previous instanceof PreviewTab) previous.uiState().preview.suspend();
        sessionContext.activatePresentation(index);
        searchBarPlacement = null;
        saveAsBarPlacement = null;
        confirmBarPlacement = null;
        openFileDialogPlacement = null;
        openFileTreePlacement = null;
        helpOverlayPlacement = null;
        formatPlacement = null;
        statusBarPlacement = null;
        topMenu.close();
        eventLoop.invalidate();
    }

    private Path currentPresentationPath() {
        WindowTab current = sessionContext.activePresentation();
        if (current instanceof TerminalEditorApp.EditorTab editor)
            return JvmDocumentStorage.pathOf(editor.state().getCurrentResource());
        return JvmDocumentStorage.pathOf(previewSource((PreviewTab) current).binding().resource());
    }

    private FrozenDocumentSource previewSource(PreviewTab preview) {
        if (preview.source() instanceof PreviewTab.ClosedSnapshot frozen) return frozen.source();
        PreviewTab.LiveSource live = (PreviewTab.LiveSource) preview.source();
        var source = sessionContext.source(live.viewId());
        if (source == null) throw new IllegalStateException("Live preview has no registered source");
        return source.documentSession().document().freezeSource();
    }

    private void switchMarkdownPresentation() {
        WindowTab current = sessionContext.activePresentation();
        if (current instanceof PreviewTab preview) { openPreviewSource(preview, null); return; }
        var editor = (TerminalEditorApp.EditorTab) current;
        PreviewTab preview = ensureMarkdownPreview(editor);
        if (preview != null) activatePresentation(sessionContext.presentations().indexOf(preview));
        eventLoop.invalidate();
    }

    private PreviewTab ensureMarkdownPreview(TerminalEditorApp.EditorTab editor) {
        // Revalidate the committed binding before either reactivating an old
        // pair or capturing text and allocating a new preview presentation.
        if (presentationLanguage(editor) != LanguageId.MARKDOWN) return null;
        PreviewTab preview = sessionContext.previewFor(editor.documentSession().viewId());
        if (preview != null) return preview;
        var initial = editor.documentSession().document().freezeSource();
        preview = new PreviewTab(editor);
        if (!preview.uiState().preview.initialize(initial, preview.generation(), markdownPreview)) {
            notice(UiRecipient.presentation(editor, UiRecipient.Flow.LOCAL))
                .warning("Not enough memory to open Markdown preview");
            preview.close();
            return null;
        }
        preview.uiState().preview.anchorSourceLine = editor.state().getCursor().line();
        sessionContext.addPreview(preview, editor.documentSession().viewId());
        return preview;
    }

    /** Called only for new filesystem admissions; recovery and existing views keep their editing state. */
    private void applyMarkdownOpenPolicy(TerminalEditorApp.EditorTab editor, boolean mayActivate) {
        if (editor == null) return;
        var policy = MarkdownOpenPolicy.forNewFile(JvmDocumentStorage.pathOf(editor.state().getCurrentResource()),
            editor.state().isDirty(), uiSessionState.markdownOpenBothTabsSetting,
            uiSessionState.markdownOpenInPreviewSetting);
        if (!policy.createPreview()) return;
        PreviewTab preview = ensureMarkdownPreview(editor);
        if (preview == null) return;
        var previewId = preview.presentationId();
        // Admission precedes retirement, so refusal preserves the newly opened editing view.
        if (!policy.keepSource()) host.closeTab(sessionContext, editor.documentSession().viewId(), saveAsSuggestions);
        if (mayActivate && policy.focusPreview()) {
            var selected = sessionContext.presentation(previewId);
            activatePresentation(sessionContext.presentations().indexOf(selected));
        }
        eventLoop.invalidate();
    }

    private record PreviewReopen(UiRecipient recipient, FilePickerOpenWorkflow.OpenRequest request, TextPosition reveal) {
        PresentationId owner() { return recipient.presentationId(); }
    }
    private PreviewReopen pendingPreviewReopen;

    private void openPreviewSource(PreviewTab preview, TextPosition reveal) {
        if (preview.source() instanceof PreviewTab.LiveSource live) {
            var editor = sessionContext.source(live.viewId());
            if (editor == null) throw new IllegalStateException("Preview source was retired without a snapshot");
            activatePresentation(sessionContext.presentations().indexOf(editor));
            revealSource(editor, reveal);
            return;
        }
        if (documentIo.busy()) { notice().info("A file operation is already in progress"); return; }
        FrozenDocumentSource captured = previewSource(preview);
        var resource = captured.binding().resource();
        Path path = JvmDocumentStorage.pathOf(resource);
        if (resource == null || path == null) { notice().error("This snapshot has no file. Use Save As first."); return; }
        int existing = WorkspaceDocuments.findOpenFile(host.toTabSessions(tabs), -1, path);
        if (existing >= 0) {
            var editor = tabs.get(existing);
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            var linkedEditor = sessionContext.linkPreview(preview, editor);
            ((PreviewTab.ClosedSnapshot) preview.source()).subject().close();
            revealSource(linkedEditor, reveal);
            eventLoop.invalidate(); return;
        }
        var request = new FilePickerOpenWorkflow.OpenRequest(path, captured.reopenMode(),
            new dev.tuink.editor.session.document.PreparedDocumentOpen(resource,
                captured.reopenMode(), false));
        var token = new PreviewReopen(UiRecipient.presentation(preview, UiRecipient.Flow.LOCAL), request, reveal);
        pendingPreviewReopen = token;
        notice().info("Reading source from disk…");
        documentIo.submit(request::read, completion -> {
            boolean currentAttempt = pendingPreviewReopen == token;
            if (currentAttempt) pendingPreviewReopen = null;
            if (!currentAttempt || !current(token.recipient()) || disposed || request.operation().cancelled()) {
                request.cancel(); afterDocumentIo(); return;
            }
            PreviewTab owner = (PreviewTab) sessionContext.presentation(token.owner());
            var result = FilePickerOpenWorkflow.complete(request, completion.value(), completion.failure(),
                host.toTabSessions(tabs), -1, () -> TuiDocumentSession.create(host.clipboard));
            TerminalEditorApp.EditorTab editor = result.openedDocument() == null ? null
                : new TerminalEditorApp.EditorTab(result.openedDocument(), result.openedDocument().newRenderPlanBuilder(
                    host.resolveHighlighter(JvmDocumentStorage.pathOf(result.openedDocument().view().state().getCurrentResource()),
                        sourceSyntax())));
            if (result.activateViewId() != null) editor = sessionContext.source(result.activateViewId());
            if (editor != null) {
                boolean activate = sessionContext.activePresentation().presentationId().equals(owner.presentationId());
                if (activate) cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
                var linkedEditor = sessionContext.linkPreview(owner, editor, activate);
                ((PreviewTab.ClosedSnapshot) owner.source()).subject().close();
                revealSource(linkedEditor, token.reveal());
            } else {
                String message = result.message() == null ? "Cannot read this source automatically; use Open with encoding or Save As."
                    : result.message();
                notice(token.recipient()).error(message);
            }
            afterDocumentIo();
        });
        eventLoop.invalidate();
    }

    private void revealSource(TerminalEditorApp.EditorTab editor, TextPosition position) {
        if (position == null) return;
        var snapshot = editor.documentSession().document().snapshot();
        int line = Math.min(position.line(), snapshot.lineCount() - 1);
        int column = Math.min(position.utf16Offset(), snapshot.lineText(line).length());
        editor.documentSession().navigate(new Position(line, column), false);
        editor.viewportController().onEditorTextInteraction();
    }

    private void cancelPreviewReopen() {
        if (pendingPreviewReopen == null) return;
        var request = pendingPreviewReopen;
        request.request().cancel(); pendingPreviewReopen = null;
        documentIo.cancelRead();
        if (current(request.recipient())) notice(request.recipient()).info("Source opening cancelled; snapshot kept");
    }

    private void closeView(int index) {
        WindowTab selected = sessionContext.presentations().get(index);
        if (selected instanceof PreviewTab preview) {
            if (preview.source() instanceof PreviewTab.ClosedSnapshot
                && snapshotSaves.cancel(preview) == DocumentIoCoordinator.CancelResult.DRAINING_WRITE) {
                notice().info("Finishing snapshot save; wait before closing"); return;
            }
            if (pendingPreviewReopen != null && pendingPreviewReopen.owner().equals(preview.presentationId())) cancelPreviewReopen();
            if (preview.uiState().pendingOpen != null && preview.uiState().pendingOpen == pendingOpen) cancelPendingOpen();
            if (preview.source() instanceof PreviewTab.ClosedSnapshot frozen && frozen.subject().io().busy()) {
                notice().info("A snapshot operation is still in progress; wait before closing"); return;
            }
            if (sessionContext.presentations().size() == 1) sessionContext.addAll(List.of(host.newEditorTab()));
            if (sessionContext.activePresentation() == preview)
                cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            sessionContext.removePreview(preview.presentationId());
            preview.close();
            eventLoop.invalidate();
            return;
        }
        var tab = (TerminalEditorApp.EditorTab) selected;
        int sourceIndex = sessionContext.indexOf(tab.documentSession().viewId());
        ViewId viewId = tab.documentSession().viewId();
        if (formatWorkflow.affects(tab.documentSession())) {
            notice(UiRecipient.presentation(tab, UiRecipient.Flow.LOCAL)).info("A format operation is in progress; cancel it before closing");
            return;
        }
        cancelPendingSave(viewId);
        if (!sessionContext.canClose(viewId)) {
            notice(UiRecipient.presentation(tab, UiRecipient.Flow.LOCAL)).info("Saving this document; wait before closing its tab");
        } else if (tab.uiState().awaitingAnswer()) {
            activateTab(sourceIndex);
            tab.uiState().modalAttention.request(TerminalEditorApp.modalInputOwner(tab.uiState().modalState,
                tab.uiState().openFileMode), System.nanoTime());
        } else if (tab.state().isDirty() && uiSessionState.confirmOnCloseSetting && !sessionContext.hasOtherViews(viewId)) {
            tab.uiState().modalState = UiModalState.closeTabConfirm(sourceIndex).bindTo(viewId);
        } else {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
            host.closeTab(sessionContext, viewId, saveAsSuggestions);
            searchBarPlacement = null;
            saveAsBarPlacement = null;
            confirmBarPlacement = null;
        }
        eventLoop.invalidate();
    }

    /** Window navigation remains reachable while a view owns an input dialog. */
    private boolean executeTabCommand(TuinkCommand command, CommandInvocation invocation) {
        if (!windowModal.isNone()) return false;
        if (command == TuinkCommand.TAB_NEXT || command == TuinkCommand.TAB_PREVIOUS) {
            activatePresentation(Math.floorMod(sessionContext.activePresentationIndex()
                + (command == TuinkCommand.TAB_NEXT ? 1 : -1), sessionContext.presentations().size()));
            return true;
        }
        if (command == TuinkCommand.TAB_NEW) {
            sessionContext.addAll(List.of(host.newEditorTab()));
            activatePresentation(sessionContext.presentations().size() - 1);
            return true;
        }
        if (command == TuinkCommand.TAB_CLOSE) {
            int index = invocation.argument("index").map(Integer::parseInt).orElse(sessionContext.activePresentationIndex());
            if (index < sessionContext.presentations().size()) closeView(index);
            return true;
        }
        return false;
    }

    private boolean windowTabCommand(TuinkCommand command) {
        return command == TuinkCommand.TAB_NEXT || command == TuinkCommand.TAB_PREVIOUS
            || command == TuinkCommand.TAB_NEW || command == TuinkCommand.TAB_CLOSE
            || command == TuinkCommand.TAB_ACTIVATE;
    }

    /** Local dialogs keep their presentation while window-scoped dialogs own all chrome. */
    private boolean routeTabChrome(TuiInputEvent event) {
        if (!(event instanceof PointerEvent pointer) || tabBarPlacement == null
            || !uiSessionState.mouseModeSetting || !terminal.mouseAvailable() || topMenu.isOpen()
            || !modalState().isNone() && blockingBounds().contains(pointer.column(), pointer.row())) return false;
        var policy = TerminalEditorApp.tabBarInputPolicy(true, true, !windowModal.isNone(), false);
        var result = TabBarInputRouter.route(tabBarPlacement, policy, pointer);
        tabStrip.adopt(result.state());
        if (result.blocked()) rejectDialogInput();
        if (result.activateIndex() != null && result.activateIndex() < sessionContext.presentations().size())
            invokeCommand(commandRouter.tabActivationInvocation(result.activateIndex()), currentRecipient());
        if (result.closeIndex() != null && result.closeIndex() < sessionContext.presentations().size())
            invokeCommand(new CommandInvocation(TuinkCommand.TAB_CLOSE.id(),
                java.util.Map.of("index", Integer.toString(result.closeIndex()))), currentRecipient());
        if (result.consumed()) eventLoop.invalidate();
        return result.consumed();
    }

    private boolean finishPendingDialogsBeforeQuit() {
        for (int index = 0; index < tabs.size(); index++) {
            if (!tabs.get(index).uiState().awaitingAnswer()) continue;
            closeState = ApplicationCloseWorkflow.cancel(closeState);
            setModal(UiModalState.none());
            activateTab(index);
            notice().info("Finish this document dialog before saving all documents and quitting");
            return true;
        }
        return false;
    }

    @Override
    public boolean running() {
        return closeState.running();
    }

    @Override
    public void prepareTurn() {
        systemClipboard.collect();
        synchronizeUiLifetime();
        synchronizeModalTarget();
        while (documentIo.collect()) {
            eventLoop.invalidate();
        }
        String discoveryCleanupWarning = recoveryAutosave.takeDiscoveryCleanupWarning();
        if (discoveryCleanupWarning != null) notice().warning(discoveryCleanupWarning);
        snapshotSaves.updateProgress();
        checkWindowCloseDeadline(System.nanoTime());
        updateSaveProgress();
        if (ui().deferredOpenResult != null && modalState().isNone() && !documentIo.busy() && !disposed) {
            FilePickerOpenWorkflow.Result ready = ui().deferredOpenResult;
            ui().deferredOpenResult = null;
            applyOpenFileResult(ready);
            eventLoop.invalidate();
        }
        if (sessionContext.activePresentation() instanceof PreviewTab) {
            model = null; state = null; engine = null; renderPlanBuilder = null;
            viewportController = null; navigation = null; plan = null;
            syntaxRetryAtNanos = Long.MIN_VALUE;
            prepareWindowChrome();
            return;
        }
        if (state != sessionContext.state()) {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        activeTabIndex = sessionContext.activeTabIndex();
        model = sessionContext.model();
        state = sessionContext.state();
        if (host.refreshFileAccess(state)) eventLoop.invalidate();
        engine = sessionContext.engine();
        renderPlanBuilder = sessionContext.renderPlanBuilder();
        if (syntaxRetryAtNanos != Long.MIN_VALUE) {
            if (!renderPlanBuilder.hasPendingSyntaxWork()) {
                syntaxRetryAtNanos = Long.MIN_VALUE;
            } else if (System.nanoTime() - syntaxRetryAtNanos >= 0L) {
                syntaxRetryAtNanos = Long.MIN_VALUE;
                eventLoop.invalidate();
            }
        }
        viewportController = tabs.get(activeTabIndex).viewportController();
        navigation = tabs.get(activeTabIndex).navigation();
        engine.setGutterDiffMode(uiSessionState.gutterDiffModeSetting);
        if (engine.refreshGutterDiffIfDue()) {
            eventLoop.invalidate();
        }
        prepareWindowChrome();
        if (plan != null) runDocumentPointerAction(() -> {
            if (editorView.scrollSelectionIfDue(
                model, state, plan, contentRows, engine, contentTopRow, System.nanoTime()
            )) {
                viewportController.onEditorTextInteraction();
                eventLoop.invalidate();
            }
        });
    }

    /** Shared dialog/recovery/chrome preparation does not require an editing session. */
    private void prepareWindowChrome() {
        Object nextBlockingOwner = TerminalEditorApp.modalInputOwner(modalState(), ui().openFileMode);
        if (!Objects.equals(focusUi().blockingFocusOwner, nextBlockingOwner)) {
            focusUi().blockingFocusOwner = nextBlockingOwner;
            focusUi().blockingContentFocused = false;
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        if (host.modalAttention().synchronize(TerminalEditorApp.modalInputOwner(modalState(), ui().openFileMode), System.nanoTime())) {
            eventLoop.invalidate();
        }
        List<TuiDocumentSession> recoveryTabSessions = host.toDocumentSessions(tabs);
        recoveryAutosave.syncTabs(recoveryTabSessions);
        String recoveryWarning = modalState().isRecoveryConfirm() ? null
            : recoveryAutosave.autosaveDirtyTabs(recoveryTabSessions, System.nanoTime());
        if (recoveryWarning != null) {
            notice().warning(recoveryWarning);
            eventLoop.invalidate();
        }
        if (notice().expireIfNeeded()) {
            eventLoop.invalidate();
        }
        if (ui().openFileMode && openFileTree().consumeMetadataChanged()) {
            var failure = openFileTree().directoryFailure();
            if (failure != null && !failure.equals(ui().lastPickerDirectoryFailure)) {
                ui().pickerNotice = new FilePickerOpenWorkflow.Notice(failure.reason(), failure.directory());
            }
            ui().lastPickerDirectoryFailure = failure;
            eventLoop.invalidate();
        }
        int nextRows = Math.max(2, terminal.height());
        int nextCols = Math.max(1, terminal.width());
        if (rows != nextRows || cols != nextCols || !uiSessionState.mouseModeSetting || !terminal.mouseAvailable()) {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            cancelDocumentPointerInteraction();
        }
        rows = nextRows;
        cols = nextCols;
        saveAsMode = modalState().isSaveAs();
        saveOverwriteConfirmMode = modalState().isSaveOverwriteConfirm();
        recoveryConfirmMode = modalState().isRecoveryConfirm();
        quitConfirmMode = modalState().isQuitConfirm();
        closeTabConfirmMode = modalState().isCloseTabConfirm();
        helpHintsMode = modalState().isHelpHints();
        helpLicensesMode = modalState().isHelpLicenses();
        helpAuthorMode = modalState().isHelpAuthor();
        if (!helpHintsMode && !helpLicensesMode && !helpAuthorMode) {
            host.resetHelpScroll();
        }
        pendingOverwritePath = modalState().pendingOverwritePath();
        pendingCloseTabIndex = modalState().pendingCloseTabIndex();
        confirmMode = recoveryConfirmMode
            || modalState().isSaveUtf8Confirm()
            || modalState().isOpenUtf16Confirm()
            || saveOverwriteConfirmMode
            || quitConfirmMode
            || closeTabConfirmMode;
        if (!ui().searchMode) {
            if (!ui().preview.active || !ui().preview.searchVisible) ui().searchEditorFocused = false;
            host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            searchBarPlacement = null;
        }
    }

    @Override
    public boolean renderFrame(TuiEventLoop.FrameTicket frameTicket) {
        if (host.profileEnabled) {
            host.frameProfiler.beginFrame(sessionContext.activePresentation() instanceof PreviewTab ? "preview" : "source");
        }
        if (sessionContext.activePresentation() instanceof PreviewTab preview) {
            var composition = preparePreviewComposition(preview, frameTicket.fullRedraw());
            if (host.profileEnabled) host.frameProfiler.compositionFinished(preview.uiState().preview.ready());
            lastPreviewFrame = renderFrameController.renderPreviewFrameIfNeeded(composition);
            lastPreviewPrompt = composition.surface().inlinePrompt().text();
            plan = null;
            return true;
        }
        lastPreviewFrame = null; lastPreviewPrompt = null;
        FrameComposition composition = prepareComposition(frameTicket.fullRedraw());
        if (host.profileEnabled) host.frameProfiler.compositionFinished(true);
        TerminalEditorApp.RenderFrameResult frameResult = renderFrameController.renderFrameIfNeeded(composition);
        plan = frameResult.plan();
        syntaxRetryAtNanos = renderPlanBuilder.hasPendingSyntaxWork()
            ? System.nanoTime() + SYNTAX_RETRY_DELAY_NANOS : Long.MIN_VALUE;
        return frameResult.rendered();
    }

    Frame previewFrame() { return lastPreviewFrame; }
    String previewPromptText() { return lastPreviewPrompt; }

    private PreviewFrameComposition preparePreviewComposition(PreviewTab preview, boolean fullRedraw) {
        var frame = PreviewPresentationView.prepare(new PreviewPresentationView.Input(chromeInput(), preview,
            previewSource(preview), markdownPreview, fullRedraw));
        if (reportSearchError(preview.uiState().preview.searchFailure.message())) {
            // A newly published persistent notice changes the reserved chrome
            // rows. Prepare the final placement with those same rows this turn.
            frame = PreviewPresentationView.prepare(new PreviewPresentationView.Input(chromeInput(), preview,
                previewSource(preview), markdownPreview, fullRedraw));
        }
        adoptChrome(frame.layers());
        searchBarPlacement = null; statusBarPlacement = null;
        contentTopRow = frame.contentTopRow();
        contentRows = frame.preview().bounds().height();
        contentCols = frame.preview().bounds().width();
        contentLeft = frame.preview().bounds().x();
        editorPointerRows = contentRows;
        return frame;
    }

    /** Filename metadata is sufficient: capabilities never capture text or access storage. */
    private LanguageId presentationLanguage(WindowTab presentation) {
        DocumentResource resource;
        if (presentation instanceof TerminalEditorApp.EditorTab editor) resource = editor.state().getCurrentResource();
        else if (((PreviewTab) presentation).source() instanceof PreviewTab.ClosedSnapshot frozen)
            resource = frozen.source().binding().resource();
        else {
            var source = sessionContext.source(((PreviewTab.LiveSource) ((PreviewTab) presentation).source()).viewId());
            resource = source == null ? null : source.state().getCurrentResource();
        }
        return LanguageRegistry.forFileName(resource == null ? null : resource.nameHint());
    }

    ActiveSurface activeSurface() {
        WindowTab presentation = sessionContext.activePresentation();
        ActiveSurface.Kind kind = presentation instanceof TerminalEditorApp.EditorTab ? ActiveSurface.Kind.SOURCE
            : ((PreviewTab) presentation).source() instanceof PreviewTab.ClosedSnapshot
                ? ActiveSurface.Kind.FROZEN_PREVIEW : ActiveSurface.Kind.LIVE_PREVIEW;
        UiModalState modal = modalState();
        ActiveSurface.ModalScope scope = globalModal(modal) ? ActiveSurface.ModalScope.WINDOW
            : modal.isNone() && !ui().openFileMode ? ActiveSurface.ModalScope.NONE : ActiveSurface.ModalScope.PRESENTATION;
        ActiveSurface.Focus focus = ActiveSurface.Focus.CONTENT;
        if (!modal.isNone() && !focusUi().blockingContentFocused)
            focus = modal.isSaveAs() && host.saveAsFocusManager().focused().orElse(null) == SaveAsBarView.Target.FIELD
                ? ActiveSurface.Focus.EDITABLE_TEXT : ActiveSurface.Focus.CONTROL;
        else if (ui().openFileMode && !focusUi().blockingContentFocused)
            focus = ui().openFileFocusState.focus() == OpenFileInputRouter.Focus.LOCATION
                ? ActiveSurface.Focus.EDITABLE_TEXT : ActiveSurface.Focus.CONTROL;
        else if (modal.isNone() && kind != ActiveSurface.Kind.SOURCE && ui().preview.searchVisible && !ui().searchEditorFocused)
            focus = previewQueryFocused() ? ActiveSurface.Focus.EDITABLE_TEXT : ActiveSurface.Focus.CONTROL;
        else if (modal.isNone() && kind == ActiveSurface.Kind.SOURCE && ui().searchMode && !ui().searchEditorFocused)
            focus = SearchController.isSearchFieldFocus(host.currentSearchFocus())
                ? ActiveSurface.Focus.EDITABLE_TEXT : ActiveSurface.Focus.CONTROL;
        boolean locked = presentation instanceof TerminalEditorApp.EditorTab source
            && (source.documentSession().document().io().busy() || formatWorkflow.affects(source.documentSession()));
        return new ActiveSurface(presentation.presentationId(), kind, focus, scope,
            uiSessionState.readingModeSetting || kind != ActiveSurface.Kind.SOURCE, locked, documentIo.busy(),
            terminal.mouseAvailable(), sourceSyntax().available(), uiSessionState.wrapLinesSetting, presentationLanguage(presentation));
    }

    private void applyUiIntent(InputActionDispatcher.Intent intent) {
        switch (intent) {
            case HELP_HINTS -> setModal(UiModalState.helpHints());
            case HELP_LICENSES -> setModal(UiModalState.helpLicenses());
            case HELP_AUTHOR -> setModal(UiModalState.helpAuthor());
            default -> { }
        }
    }

    private PresentationSyntaxPolicy.Source sourceSyntax() {
        return PresentationSyntaxPolicy.source(uiSessionState, host.theme);
    }

    private WindowChrome.Input chromeInput() {
        return new WindowChrome.Input(host, sessionContext, uiSessionState, activeSurface(), ui(), focusUi(), modalState(), notice(),
            startupRecoveryEntries.size(), rows, cols, topMenu, commandRouter, tabStrip);
    }

    private Rect blockingBounds() {
        // Bootstrap has no prepared owner yet; all subsequent input uses the published layers.
        return windowLayers == null ? NO_BLOCKING_BOUNDS : windowLayers.blockingBounds();
    }

    private void adoptChrome(FrameComposition.Layers layers) {
        windowLayers = Objects.requireNonNull(layers, "layers");
        tabBarPlacement = layers.tabBar();
        topMenuPlacement = layers.topMenu();
        saveAsBarPlacement = layers.saveAs();
        confirmBarPlacement = layers.confirm();
        openFileDialogPlacement = layers.openFile().dialog();
        openFileTreePlacement = layers.openFile().placement();
        helpOverlayPlacement = layers.help();
        formatPlacement = layers.format();
    }

    private boolean reportSearchError(String error) {
        if (error.equals(ui().reportedSearchError)) return false;
        ui().reportedSearchError = error;
        if (error.isEmpty()) return false;
        notice().error(error);
        return true;
    }

    private FrameComposition prepareComposition(boolean fullRedraw) {
        SearchPresentation.Snapshot searchSnapshot = ui().searchPresentation.resolve(
            ui().searchMode, ui().searchQueryInput.text(), ui().searchUi, host.toTabSessions(tabs), activeTabIndex);
        reportSearchError(searchSnapshot.error());
        if (host.profileEnabled) host.frameProfiler.searchNanos = host.frameProfiler.stopwatchNanos();
        SearchBarView searchBar = ui().searchMode ? SearchBarView.build(ui().searchQueryInput,
            ui().searchReplaceInput, cols, ui().searchUi.caseSensitive(), ui().searchUi.matchMode(), ui().searchUi.allTabs()) : null;
        var chromePreparation = WindowChrome.prepare(chromeInput(), searchBar);
        contentTopRow = chromePreparation.contentTopRow();
        searchBarPlacement = chromePreparation.search();
        int overlayRows = chromePreparation.prompt().height();
        engine.setTabWidth(uiSessionState.tabWidthSetting);
        EditorLayout editorLayout = null;
        for (int i = 0; i < 3; i++) {
            editorLayout = EditorLayout.resolve(
                model,
                engine.textLayout(),
                state,
                rows,
                cols,
                contentTopRow,
                overlayRows,
                uiSessionState.wrapLinesSetting,
                uiSessionState.wrapWordsSetting,
                uiSessionState.tabWidthSetting
            );
            int topBefore = state.getTopLine();
            int segmentBefore = state.getTopWrapSegment();
            int leftBefore = state.getLeftVisualColumn();
            viewportController.ensureCursorVisible(
                engine,
                editorLayout.contentRows(),
                editorLayout.contentColumns(),
                uiSessionState.wrapLinesSetting,
                uiSessionState.wrapWordsSetting,
                uiSessionState.tabWidthSetting
            );
            if (topBefore == state.getTopLine()
                && segmentBefore == state.getTopWrapSegment()
                && leftBefore == state.getLeftVisualColumn()) {
                break;
            }
        }
        editorLayout = EditorLayout.resolve(
            model,
            engine.textLayout(),
            state,
            rows,
            cols,
            contentTopRow,
            overlayRows,
            uiSessionState.wrapLinesSetting,
            uiSessionState.wrapWordsSetting,
            uiSessionState.tabWidthSetting
        );
        contentRows = editorLayout.contentRows();
        contentCols = editorLayout.contentColumns();
        contentLeft = editorLayout.gutterWidth();
        editorPointerRows = contentRows + editorLayout.horizontalScrollbarHeight();
        if (ui().searchEditorFocused && searchBarPlacement != null
            && (contentRows == 0 || contentCols == 0)) {
            ui().searchEditorFocused = false;
            host.searchFocusManager().next(searchBarPlacement.focusScope());
        }
        renderer.setHighlightActiveGutter(uiSessionState.highlightActiveGutterSetting);
        renderer.setVisibleWhitespace(uiSessionState.visibleWhitespaceSetting);
        renderer.setTabWidth(uiSessionState.tabWidthSetting);
        renderPlanBuilder.setSyntaxHighlightingEnabled(
            sourceSyntax().enabled()
        );
        renderPlanBuilder.setCommentSyntaxProfile(
            LanguageProfiles.resolveCommentSyntax(state.getCurrentResource() == null ? null : state.getCurrentResource().nameHint())
        );
        renderPlanBuilder.setRainbowBracketsEnabled(uiSessionState.rainbowBracketsSetting);
        renderPlanBuilder.setTabWidth(uiSessionState.tabWidthSetting);
        renderPlanBuilder.setDecorationStyles(
            host.styles.renderSelection(),
            host.styles.renderSearchMatch(),
            host.styles.renderCurrentSearchMatch(),
            host.styles.renderBracketMatch(),
            host.styles.renderQuoteMatch(),
            host.styles.renderUnmatchedBracket(),
            host.styles.renderRainbowBrackets()
        );
        renderPlanBuilder.setGutterDiffResolver(engine.gutterDiffMarkers());
        editorView.setTabWidth(uiSessionState.tabWidthSetting);
        engine.setTabInsertMode(uiSessionState.tabInsertModeSetting);
        engine.setAutoIndentEnabled(uiSessionState.autoIndentSetting);
        engine.setAutoPairsEnabled(uiSessionState.autoPairsSetting);
        engine.setAutoListEnabled(uiSessionState.autoListSetting);
        statusBarPlacement = host.buildStatusBar(state, model, cols, uiSessionState.readingModeSetting,
            sessionContext.activePresentationIndex(), sessionContext.presentations().size(), searchSnapshot.summary(),
            activeDocument().document().formatSummary(), rows - 1);
        var chrome = chromePreparation.finish(new WindowChrome.Content(activeSurface(),
            new Rect(contentLeft, contentTopRow, contentCols, contentRows), statusBarPlacement.text(),
            searchSnapshot.tabMatchFlags()));
        adoptChrome(chrome.layers());
        FrameComposition composition = new FrameComposition(
            new FrameComposition.Document(renderPlanBuilder, model, state, editorLayout),
            chrome.surface(),
            new FrameComposition.Search(ui().searchMode, searchSnapshot.query(), searchSnapshot.options(),
                uiSessionState.autoSelectionHighlightSetting, searchBarPlacement, ui().searchEditorFocused),
            chrome.layers(), uiSessionState.wrapLinesSetting, uiSessionState.wrapWordsSetting, fullRedraw);
        return composition;
    }

    @Override
    public void frameEnded(boolean rendered) {
        systemClipboard.frameEnded();
        if (host.profileEnabled) {
            host.frameProfiler.endFrame(rendered);
        }
    }

    @Override
    public long nanosUntilWakeup(long nowNanos) {
        return TerminalEditorApp.earlierDeadline(contentWakeup(nowNanos),
            systemClipboard.busy() ? SYNTAX_RETRY_DELAY_NANOS : -1L);
    }

    private long contentWakeup(long nowNanos) {
        if (sessionContext.activePresentation() instanceof PreviewTab) {
            long next = TerminalEditorApp.earlierDeadline(notice().nanosUntilExpire(nowNanos),
                host.modalAttention().nanosUntilChange(nowNanos));
            boolean pending = ui().preview.pending != null || documentIo.busy()
                || ui().openFileTree != null && ui().openFileTree.loading();
            return TerminalEditorApp.earlierDeadline(next, pending ? SYNTAX_RETRY_DELAY_NANOS : -1L);
        }
        long presentationDeadline = TerminalEditorApp.earlierDeadline(
            TerminalEditorApp.earlierDeadline(notice().nanosUntilExpire(nowNanos),
                host.modalAttention().nanosUntilChange(nowNanos)),
            TerminalEditorApp.earlierDeadline(editorView.nanosUntilSelectionScroll(nowNanos),
                TerminalEditorApp.earlierDeadline(engine.nanosUntilGutterDiffRefresh(nowNanos),
                    syntaxRetryAtNanos == Long.MIN_VALUE ? -1L : Math.max(0L, syntaxRetryAtNanos - nowNanos)))
        );
        return TerminalEditorApp.earlierDeadline(
            TerminalEditorApp.earlierDeadline(presentationDeadline, host.nanosUntilFileAccessRefresh(nowNanos)),
            documentIo.busy() ? SYNTAX_RETRY_DELAY_NANOS : -1L);
    }

    private TerminalEditorApp.HelpOverlayPlacement currentHelpOverlay() {
        return helpHintsMode || helpLicensesMode || helpAuthorMode ? helpOverlayPlacement : null;
    }

    private void cancelDocumentPointerInteraction() {
        editorView.cancelMouseSelection();

    }

    private void cancelPointerCapture(PointerCaptureCancellation reason) {
        pointerCapture.cancel(pointerFrame(), reason);
    }

    static FocusScope<String, ListView.Action> helpContentScope(TerminalEditorApp.HelpOverlayPlacement help) {
        return FocusScope.single(HELP_CONTENT_POINTER_SCOPE, FocusResetPolicy.CLEARED,
            new RoutedTarget<>("content", help.content(), FocusSpace.root(help.viewport()), false,
                true));
    }

    private FocusScope<String, Void> documentPointerScope() {
        Rect bounds = new Rect(0, contentTopRow, cols, Math.max(0, editorPointerRows));
        return FocusScope.single(DOCUMENT_POINTER_SCOPE, FocusResetPolicy.CLEARED,
            new RoutedTarget<>("document", new DocumentPointerTarget(bounds),
                FocusSpace.root(new Rect(0, 0, cols, rows)), false, plan != null));
    }

    private FocusScope<String, Void> previewPointerScope() {
        var preview = ui().preview;
        return FocusScope.single(PREVIEW_POINTER_SCOPE, FocusResetPolicy.CLEARED,
            new RoutedTarget<>("preview", new PreviewPointerTarget(preview),
                FocusSpace.root(new Rect(0, 0, cols, rows)), false, preview.ready()));
    }

    private final class PreviewPointerTarget implements PlacedPointerCapture<Void> {
        private final MarkdownPreviewState preview;
        PreviewPointerTarget(MarkdownPreviewState preview) { this.preview = preview; }
        @Override public Rect bounds() { return preview.bounds; }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {}
        @Override public boolean pointerCaptureActive() { return preview.pointerCaptureActive(); }
        @Override public InteractionResult<Void> cancelPointerCapture(PointerCaptureCancellation reason) {
            preview.cancelPointerInteraction();
            return InteractionResult.<Void>handled().withPointerCapture(PointerCaptureRequest.RELEASE);
        }
        @Override public InteractionResult<Void> handle(TuiInputEvent event, FocusContext context) {
            if (!(event instanceof PointerEvent pointer) || !context.enabled()
                || !preview.bounds.contains(pointer.column(), pointer.row()) && !preview.pointerCaptureActive())
                return InteractionResult.ignored();
            dispatchContentInput(WindowInput.physical(pointer, currentRecipient()));
            return InteractionResult.<Void>handled().withPointerCapture(preview.pointerCaptureActive()
                ? PointerCaptureRequest.ACQUIRE : PointerCaptureRequest.RELEASE);
        }
    }

    private boolean previewQueryFocused() {
        return !ui().searchEditorFocused
            && ui().searchFocusManager.activeScopeId().filter(PREVIEW_SEARCH_SCOPE::equals).isPresent()
            && ui().searchFocusManager.focused().orElse(null) == SearchFocus.FIND;
    }

    private FocusScope<SearchFocus, TextField.Action> previewSearchScope() {
        MarkdownPreviewState preview = ui().preview;
        boolean enabled = preview.searchVisible;
        var scope = FocusScope.single(PREVIEW_SEARCH_SCOPE, FocusResetPolicy.FIRST_FOCUSABLE,
            new RoutedTarget<>(SearchFocus.FIND, preview.searchPlacement(), FocusSpace.root(preview.bounds), true, enabled));
        if (enabled && modalState().isNone() && !ui().openFileMode && !topMenu.isOpen() && !ui().searchEditorFocused) ui().searchFocusManager.context(scope, SearchFocus.FIND);
        return scope;
    }

    private WindowPointerCapture.Frame pointerFrame() {
        WindowPointerCapture.Frame frame = WindowPointerCapture.frame(this);
        if (formatPlacement != null) {
            frame.addScope(formatPlacement.scope(), formatPlacement.state().focus, Runnable::run);
        }
        if (searchBarPlacement != null) {
            frame.addScope(searchBarPlacement.focusScope(), host.searchFocusManager(), ignored -> {},
                ui().searchMode && modalState().isNone() && !ui().openFileMode && !topMenu.isOpen()
                    && uiSessionState.mouseModeSetting && terminal.mouseAvailable());
        }
        if (saveAsBarPlacement != null) {
            frame.addScope(saveAsBarPlacement.focusScope(), host.saveAsFocusManager(), ignored -> {},
                modalState().isSaveAs() && uiSessionState.mouseModeSetting && terminal.mouseAvailable());
        }
        if (topMenuPlacement != null) {
            frame.addScope(topMenuPlacement.captureScope(), action -> {
                if (action instanceof MenuBar.StateChanged<CommandInvocation> changed) {
                    topMenu.adopt(changed.state());
                }
            });
        }
        if (openFileDialogPlacement != null) {
            frame.addScope(openFileDialogPlacement.focusScope(), ui().openFileFocusState.manager(), ignored -> {});
            frame.addScope(openFileDialogPlacement.captureScope(), action -> {
                if (action instanceof FileTreeView.StateChanged changed) {
                    openFileTree().adopt(changed.state());
                }
            });
        }
        TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
        if (help != null) {
            frame.addScope(helpContentScope(help), action -> {
                if (action instanceof ListView.StateChanged changed) {
                    host.setHelpScrollState(changed.state());
                }
            });
        }
        if (sessionContext.activePresentation() instanceof PreviewTab) {
            // Keep the disabled binding available for cancellation before hiding its query owner.
            frame.addScope(previewSearchScope(), ui().searchFocusManager, action -> {
                if (action == TextField.Action.VALUE_CHANGED) ui().preview.setSearchQuery(ui().preview.searchField.text());
            }, modalState().isNone() && !ui().openFileMode && !topMenu.isOpen()
                && uiSessionState.mouseModeSetting && terminal.mouseAvailable());
            frame.addScope(previewPointerScope(), ignored -> {});
        } else if (plan != null) {
            frame.addScope(documentPointerScope(), ignored -> {});
        }
        return frame;
    }

    /** A refused pointer command ends its gesture without terminating the editor loop. */
    private void runDocumentPointerAction(Runnable action) {
        try {
            action.run();
        } catch (DocumentEditLimitException refused) {
            cancelPointerCapture(PointerCaptureCancellation.RELEASE);
            cancelDocumentPointerInteraction();
            notice().error(refused.getMessage());
            eventLoop.invalidate();
        }
    }

    private void routeDocumentPointer(PointerEvent event) {
        var scope = ui().preview.active ? previewPointerScope() : documentPointerScope();
        var target = scope.target(ui().preview.active ? "preview" : "document").orElseThrow();
        InteractionResult<Void> result = target.placement().handle(event,
            new FocusContext(false, target.interactive(), target.space()));
        pointerCapture.observe(this, scope, target.id(), result);
    }

    private final class DocumentPointerTarget implements PlacedPointerCapture<Void> {
        private final Rect bounds;

        private DocumentPointerTarget(Rect bounds) { this.bounds = bounds; }
        @Override public Rect bounds() { return bounds; }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {}
        @Override public boolean pointerCaptureActive() {
            return editorView.pointerCaptureActive();
        }
        @Override public InteractionResult<Void> cancelPointerCapture(PointerCaptureCancellation reason) {
            cancelDocumentPointerInteraction();
            return InteractionResult.<Void>handled().withPointerCapture(PointerCaptureRequest.RELEASE);
        }
        @Override public InteractionResult<Void> handle(TuiInputEvent event, FocusContext context) {
            if (!(event instanceof PointerEvent pointer) || !context.enabled()) {
                return InteractionResult.ignored();
            }
            dispatchContentInput(WindowInput.physical(pointer, currentRecipient()));
            return InteractionResult.<Void>handled().withPointerCapture(pointerCaptureActive()
                ? PointerCaptureRequest.ACQUIRE : PointerCaptureRequest.RELEASE);
        }
    }

    private boolean documentPointerHit(PointerEvent mouse) {
        return new Rect(0, contentTopRow, cols, editorPointerRows)
            .contains(mouse.column(), mouse.row())
            && (TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) == ModalInputPolicy.Kind.NONE
                || blockingContentAvailable() && !blockingBounds().contains(mouse.column(), mouse.row()));
    }

    private boolean blockingContentAvailable() {
        return DialogCommandPolicy.mode(TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode)).permitsContentFocus()
            && contentRows > 0 && contentCols > 0
            && !blockingBounds().contains(new Rect(contentLeft, contentTopRow, contentCols, contentRows));
    }

    private void clearBlockingControlFocus() {
        if (saveAsBarPlacement != null) {
            host.saveAsFocusManager().clear(saveAsBarPlacement.focusScope());
        }
        if (confirmBarPlacement != null) {
            host.confirmFocusManager().clear(confirmBarPlacement.focusScope());
        }
        TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
        if (help != null) {
            host.helpFocusManager().clear(help.focusScope());
        }
    }

    private void focusDocument() {
        if (TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) != ModalInputPolicy.Kind.NONE) {
            focusUi().blockingContentFocused = true;
            clearBlockingControlFocus();
        } else if (ui().searchMode || ui().preview.active && ui().preview.searchVisible) {
            ui().searchEditorFocused = true;
            if (ui().preview.active) ui().searchFocusManager.clear(previewSearchScope());
            else if (searchBarPlacement != null) host.searchFocusManager().clear(searchBarPlacement.focusScope());
        }
    }

    private void focusFirstBlockingControl() {
        focusUi().blockingContentFocused = false;
        if (saveAsMode && saveAsBarPlacement != null) {
            DialogFocusTraversal.focusFirst(host.saveAsFocusManager(), saveAsBarPlacement.focusScope());
        } else if (confirmMode && confirmBarPlacement != null) {
            DialogFocusTraversal.focusFirst(host.confirmFocusManager(), confirmBarPlacement.focusScope());
        } else if (ui().openFileMode) {
            if (openFileDialogPlacement != null) {
                openFileTree().adopt(openFileTree().state().cancelPointerInteraction());
                openFileDialogPlacement.focusFirst(ui().openFileFocusState);
            }
        } else {
            TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
            if (help != null) {
                DialogFocusTraversal.focusFirst(host.helpFocusManager(), help.focusScope());
            }
        }
    }

    private void advanceBlockingFocus(KeyStroke stroke) {
        boolean available = blockingContentAvailable();
        if (saveAsMode && saveAsBarPlacement != null) {
            focusUi().blockingContentFocused = DialogFocusTraversal.advance(host.saveAsFocusManager(),
                saveAsBarPlacement.focusScope(), focusUi().blockingContentFocused, available, stroke);
        } else if (confirmMode && confirmBarPlacement != null) {
            focusUi().blockingContentFocused = DialogFocusTraversal.advance(host.confirmFocusManager(),
                confirmBarPlacement.focusScope(), focusUi().blockingContentFocused, false, stroke);
        } else if (ui().openFileMode) {
            if (openFileDialogPlacement != null) {
                OpenFileInputRouter.Result result = OpenFileInputRouter.route(
                    openFileDialogPlacement, ui().openFileFocusState, stroke, System.nanoTime(),
                    TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS);
                openFileTree().adopt(result.state());
            }
            focusUi().blockingContentFocused = false;
        } else {
            TerminalEditorApp.HelpOverlayPlacement help = currentHelpOverlay();
            if (help != null) {
                focusUi().blockingContentFocused = DialogFocusTraversal.advance(host.helpFocusManager(),
                    help.focusScope(), focusUi().blockingContentFocused, available, stroke);
            }
        }
    }

    private void rejectDialogInput() {
        DialogInputPolicy.apply(DialogInputPolicy.Decision.BLOCK, this::focusFirstBlockingControl,
            () -> host.modalAttention().request(TerminalEditorApp.modalInputOwner(modalState(), ui().openFileMode), System.nanoTime()));
        editorView.cancelMouseSelection();

        eventLoop.invalidate();
    }

    private java.util.Optional<WindowInput> routeBlockingInput(TuiInputEvent event, CommandInvocation binding,
        TuinkCommand command, ModalInputPolicy.Kind kind) {
        if (kind == ModalInputPolicy.Kind.NONE) {
            return java.util.Optional.empty();
        }
        DialogMode mode = DialogCommandPolicy.mode(kind);
        if (event instanceof PointerEvent mouse) {
            if (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable()) {
                return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
            }
            boolean drag = mouse.kind() == PointerEvent.Kind.DRAG
                || mouse.kind() == PointerEvent.Kind.MOVE && mouse.button() == PointerEvent.Button.LEFT;
            if (mode.permitsContentFocus()
                && (documentPointerHit(mouse)
                    || (drag || mouse.kind() == PointerEvent.Kind.RELEASE)
                        && (focusUi().blockingContentFocused || editorView.pointerCaptureActive()))) {
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() == PointerEvent.Button.LEFT)
                    focusDocument();
                return java.util.Optional.of(WindowInput.physical(mouse, currentRecipient()));
            }
            if (blockingBounds().contains(mouse.column(), mouse.row())) {
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() == PointerEvent.Button.LEFT
                    && focusUi().blockingContentFocused) {
                    focusFirstBlockingControl();
                }
                return java.util.Optional.empty();
            }
            if (!mode.permitsContentFocus()) {
                if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() != PointerEvent.Button.NONE
                    || mouse.kind() == PointerEvent.Kind.SCROLL_UP
                    || mouse.kind() == PointerEvent.Kind.SCROLL_DOWN
                    || mouse.kind() == PointerEvent.Kind.SCROLL_LEFT
                    || mouse.kind() == PointerEvent.Kind.SCROLL_RIGHT) {
                    rejectDialogInput();
                }
                return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
            }
            return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
        }
        TuiInputEvent focusEvent = isFocusTraversalCommand(command) ? KeyStroke.of(KeyStroke.Key.TAB) : event;
        if (DialogFocusTraversal.isTraversal(focusEvent)) {
            if (binding != null && isFocusTraversalCommand(command))
                return java.util.Optional.of(TerminalEditorApp.resolveEditorInput(commandRouter, event,
                    binding, null, currentRecipient()));
            advanceBlockingFocus((KeyStroke) focusEvent);
            editorView.cancelMouseSelection();
            eventLoop.invalidate();
            return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
        }
        if (focusUi().blockingContentFocused && DialogCommandPolicy.cancels(kind, command)) {
            focusFirstBlockingControl();
            return java.util.Optional.empty();
        }
        if (confirmMode && command == TuinkCommand.DOCUMENT_SAVE && host.confirmPolicy(modalState()).shortcuts().saveShortcut()) {
            // The explicit save accelerator belongs to the confirmation even after source browsing.
            focusFirstBlockingControl();
            return java.util.Optional.empty();
        }
        DialogInputPolicy.Action action = DialogCommandPolicy.action(event, command);
        DialogInputPolicy.Target target = DialogCommandPolicy.target(kind, ui().openFileFocusState.focus(),
            host.saveAsFocusManager().focused().orElse(null), focusUi().blockingContentFocused);
        DialogInputPolicy.Decision decision = DialogCommandPolicy.decide(mode, target, action);
        if (decision == DialogInputPolicy.Decision.BLOCK) {
            rejectDialogInput();
            return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
        }
        if (decision.consumed()) {
            return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
        }
        if (focusUi().blockingContentFocused) {
            return java.util.Optional.of(TerminalEditorApp.resolveEditorInput(commandRouter, event,
                binding, null, currentRecipient()));
        }
        if (saveAsMode && ControlCommandRouter.owns(command)) {
            return java.util.Optional.empty();
        }
        if (ModalInputPolicy.blocked(kind, event, command, target)) {
            rejectDialogInput();
            return java.util.Optional.of(WindowInput.Consumed.INSTANCE);
        }
        return java.util.Optional.empty();
    }

    private static boolean isFocusTraversalCommand(TuinkCommand command) {
        return command == TuinkCommand.EDIT_INSERT_TAB || command == TuinkCommand.SEARCH_FOCUS_NEXT;
    }

    private boolean searchVisible() {
        return modalState().isNone() && !ui().openFileMode
            && (ui().preview.active ? ui().preview.searchVisible : ui().searchMode);
    }

    private void advanceSearchFocus(KeyStroke event) {
        cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
        var scope = ui().preview.active ? previewSearchScope() : searchBarPlacement == null ? null : searchBarPlacement.focusScope();
        if (scope == null) return;
        ui().searchEditorFocused = DialogFocusTraversal.advance(ui().searchFocusManager,
            scope, ui().searchEditorFocused, contentRows > 0 && contentCols > 0, event);
        eventLoop.invalidate();
    }

    private boolean consumeSearchControlAction(SearchBarView.Action action) {
        if (action == SearchBarView.Action.CLOSE_REQUESTED) {
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            ui().searchMode = false;
            host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return true;
        }
        if (action == SearchBarView.Action.INPUT_REJECTED) {
            notice().error("Text is too large; nothing changed");
            return true;
        }
        SearchFocus toggle = TerminalEditorApp.toggleTarget(action);
        if (toggle != null) {
            ui().searchUi = SearchController.toggleFocusedSearchOption(ui().searchUi, toggle);
            return true;
        }
        return action == SearchBarView.Action.QUERY_CHANGED || action == SearchBarView.Action.REPLACEMENT_CHANGED;
    }

    private void executeSearchDocumentCommand(TuinkCommand command) {
        ActiveSurface surface = activeSurface();
        if (!surface.allows(command)) { notice().warning(surface.refusal(command)); return; }
        TabUiState source = ui();
        if (command.editsDocumentText() && rejectSavingDocumentEdit()) {
            return;
        }
        if ((command == TuinkCommand.SEARCH_REPLACE || command == TuinkCommand.SEARCH_REPLACE_ALL)
            && (searchBarPlacement == null || !searchBarPlacement.replacementContextVisible())) {
            notice().warning("Enlarge terminal to show Find and Replace fields before replacing");
            return;
        }
        if (command == TuinkCommand.CURSOR_PAGE_UP || command == TuinkCommand.CURSOR_PAGE_DOWN) {
            TerminalEditorApp.scrollDocumentViewport(model, engine.textLayout(), state, contentRows, contentCols,
                uiSessionState.wrapLinesSetting, uiSessionState.wrapWordsSetting,
                (command == TuinkCommand.CURSOR_PAGE_UP ? -1 : 1) * Math.max(1, contentRows),
                uiSessionState.tabWidthSetting);
            viewportController.onCommandResult(ViewportController.CursorPolicy.PAGE_SCROLL);
            return;
        }
        if (SearchController.isSearchActionFocus(host.searchFocusManager().focused().orElse(null))) {
            host.focusSearch(searchBarPlacement, SearchController.focusForAction(command));
        }
        SearchCommandHandler.Result result = SearchCommandHandler.execute(command,
            new SearchCommandHandler.Context(tabs.stream().map(TerminalEditorApp.EditorTab::documentSession).toList(), activeTabIndex,
                uiSessionState.readingModeSetting, ui().searchUi, ui().searchQueryInput.text(), ui().searchReplaceInput.text()),
            this::revealCurrentSelection);
        if (result.activeTabIndex() != activeTabIndex
                && !prepareSearchNavigation(source, tabs.get(result.activeTabIndex()).uiState())) {
            return;
        }
        tabs.get(result.activeTabIndex()).viewportController().onCommandResult(result.cursorPolicy());
        if (result.activeTabIndex() != activeTabIndex) activateTab(result.activeTabIndex());
        if (result.status() != null && !result.status().isBlank()) {
            switch (result.statusLevel()) {
                case ERROR -> notice().error(result.status());
                case WARNING -> notice().warning(result.status());
                case INFO -> notice().info(result.status());
            }
        }
    }

    /** Explicit cross-tab search carries values into independent destination controls. */
    private boolean prepareSearchNavigation(TabUiState source, TabUiState destination) {
        try {
            TextField query = new TextField(TextField.PastePolicy.PRESERVE_TABS);
            TextField replacement = new TextField(TextField.PastePolicy.PRESERVE_TABS);
            if (!query.set(source.searchQueryInput.text()) || !replacement.set(source.searchReplaceInput.text())) {
                notice().error("Not enough memory to continue this search in another tab");
                return false;
            }
            SearchFocus focus = source.searchFocusManager.focused().orElse(SearchFocus.FIND);
            // The destination keeps its dialog and filename state. Only this
            // explicit search result replaces its search session; tab navigation does not.
            destination.searchQueryInput = query;
            destination.searchReplaceInput = replacement;
            destination.searchUi = source.searchUi;
            destination.searchMode = source.searchMode;
            destination.searchEditorFocused = source.searchEditorFocused;
            destination.pendingSearchFocus = focus;
            return true;
        } catch (OutOfMemoryError memory) {
            notice().error("Not enough memory to continue this search in another tab");
            return false;
        }
    }

    private boolean revealCurrentSelection() {
        int topBefore = state.getTopLine();
        int wrapBefore = state.getTopWrapSegment();
        int leftBefore = state.getLeftVisualColumn();
        EditorViewNavigation.ensureCursorVisible(engine, contentRows, contentCols,
            uiSessionState.wrapLinesSetting, uiSessionState.wrapWordsSetting);
        return topBefore != state.getTopLine() || wrapBefore != state.getTopWrapSegment()
            || leftBefore != state.getLeftVisualColumn();
    }

    private void handleDocumentPointer(PointerEvent mouse) {
        if (mouse.kind() != PointerEvent.Kind.RELEASE && (mouse.button() == PointerEvent.Button.OTHER
            || !uiSessionState.mouseModeSetting || !terminal.mouseAvailable())) return;
        if (mouse.kind() == PointerEvent.Kind.PRESS && mouse.button() == PointerEvent.Button.LEFT
            && !documentPointerHit(mouse)) {
            editorView.cancelMouseSelection();
            return;
        }
        int leftBefore = state.getLeftVisualColumn();
        int topBefore = state.getTopLine();
        int wrapBefore = state.getTopWrapSegment();
        var result = editorView.handlePointer(mouse, engine, plan, rows, cols, contentRows, contentTopRow,
            TerminalEditorApp.shouldExtendEditorSelection(mouse));
        if (result.focusDocument()) focusDocument();
        if (result.textInteraction()) viewportController.onEditorTextInteraction();
        if (result.scroll().consumed()) {
            viewportController.onScrollbarInteraction(result.scroll(), leftBefore, state.getLeftVisualColumn(),
                topBefore, state.getTopLine());
            if (wrapBefore != state.getTopWrapSegment()) viewportController.onManualVerticalPan();
        }
    }

    @Override
    public void handlePoll(TuiEventLoop.Poll<TuiInputEvent> pollResult) {
        if (!host.profileEnabled || pollResult.event() == null) {
            handleInputPoll(pollResult);
            return;
        }
        long started = host.frameProfiler.inputStarted();
        try {
            handleInputPoll(pollResult);
        } finally {
            host.frameProfiler.inputHandled(started);
        }
    }

    private void handleInputPoll(TuiEventLoop.Poll<TuiInputEvent> pollResult) {
        TuiInputEvent inputEvent = pollResult.event();
        if (inputEvent == null) {
            return;
        }
        acceptWindowEvent(inputEvent);
    }

    private void acceptWindowEvent(TuiInputEvent inputEvent) {
        if (systemClipboard.deferInput(() -> {
            prepareTurn();
            acceptWindowEvent(inputEvent);
        })) return;
        try {
            dispatchWindowInput(routeWindowInput(inputEvent));
        } finally {
            systemClipboard.synchronize();
        }
    }

    private WindowInput routeWindowInput(TuiInputEvent inputEvent) {
        CommandInvocation binding = inputEvent instanceof KeyStroke stroke
            ? commandRouter.invocationFor(stroke).orElse(null) : null;
        if (binding != null && !commandRouter.accepts(binding)) return WindowInput.Consumed.INSTANCE;
        TuinkCommand navigationCommand = binding == null ? null : TuinkCommand.fromId(binding.id()).orElse(null);
        boolean cancelledCapture = pointerCapture.beforeInput(pointerFrame(), inputEvent);
        if (cancelledCapture && plan != null) {
            prepareComposition(false);
            eventLoop.invalidate();
        }
        if (inputEvent instanceof ResizeEvent) {
            tabStrip.invalidateLayout();
            host.setHelpScrollState(new ListView.State(-1, host.helpScrollState().firstVisibleIndex()));
            if (ui().openFileTree != null) ui().openFileTree.adopt(ui().openFileTree.state().cancelPointerInteraction());
            topMenu.adopt(topMenu.state().cancelPointerInteraction());
            return WindowInput.Consumed.INSTANCE;
        }
        if (inputEvent instanceof PointerEvent pointer && pointerCapture.active()) {
            if (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable()) {
                cancelPointerCapture(PointerCaptureCancellation.DISABLED);
                return WindowInput.Consumed.INSTANCE;
            }
            if (pointerCapture.dispatch(pointerFrame(), pointer)) {
                eventLoop.invalidate();
                return WindowInput.Consumed.INSTANCE;
            }
        }
        if (inputEvent instanceof PointerEvent && (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable()))
            return WindowInput.Consumed.INSTANCE;
        if (routeClipboardPointer(inputEvent)) return WindowInput.Consumed.INSTANCE;
        if (windowModal.isNone() && windowTabCommand(navigationCommand))
            return TerminalEditorApp.resolveEditorInput(commandRouter, inputEvent, binding, null, currentRecipient());
        if (routeTabChrome(inputEvent)) return WindowInput.Consumed.INSTANCE;
        if (navigationCommand == TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW
            && modalState().isNone() && !ui().openFileMode)
            return TerminalEditorApp.resolveEditorInput(commandRouter, inputEvent, binding, null, currentRecipient());
        if (modalState().isDocumentFormat()) {
            if (binding != null) return TerminalEditorApp.resolveEditorInput(commandRouter, inputEvent,
                binding, null, currentRecipient());
            if (!(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && terminal.mouseAvailable()) {
                if (formatPlacement != null) {
                    DocumentFormatDialogView.Action action = formatPlacement.handle(inputEvent, pointerCapture, this);
                    if (action != DocumentFormatDialogView.Action.NONE)
                        return WindowInput.command(commandRouter, action == DocumentFormatDialogView.Action.APPLY
                            ? TuinkCommand.DOCUMENT_FORMAT_APPLY : TuinkCommand.DOCUMENT_FORMAT_CANCEL, currentRecipient());
                }
            }
            eventLoop.invalidate();
            return WindowInput.Consumed.INSTANCE;
        }
        if (inputEvent instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.PRESS
            && pointer.button() == PointerEvent.Button.LEFT && statusBarPlacement != null
            && modalState().isNone() && !ui().openFileMode && !topMenu.isOpen() && !ui().preview.active
            && uiSessionState.mouseModeSetting && terminal.mouseAvailable()) {
            boolean encoding = statusBarPlacement.encoding().contains(pointer.column(), pointer.row());
            boolean endings = statusBarPlacement.endings().contains(pointer.column(), pointer.row());
            if (encoding || endings) {
                invokeCommand(CommandInvocation.of(TuinkCommand.DOCUMENT_FORMAT.id()), currentRecipient());
                if (ui().formatDialog != null) ui().formatDialog.requestedFocus = encoding
                    ? DocumentFormatDialogView.Target.ENCODING : DocumentFormatDialogView.Target.EOL;
                eventLoop.invalidate();
                return WindowInput.Consumed.INSTANCE;
            }
        }
        ModalInputPolicy.Kind blocker = TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode);
        TuinkCommand physicalCommand = navigationCommand;
        var blocked = routeBlockingInput(inputEvent, binding, physicalCommand, blocker);
        if (blocked.isPresent()) return blocked.orElseThrow();
        if (TerminalEditorApp.searchOwnsRawInput(ui().searchMode, modalState(), ui().openFileMode, topMenu.isOpen())
            && !ui().searchEditorFocused
            && DialogCommandPolicy.decide(DialogMode.NON_BLOCKING,
                SearchController.isSearchFieldFocus(host.searchFocusManager().focused().orElse(null))
                    ? DialogInputPolicy.Target.TEXT_INPUT : DialogInputPolicy.Target.BUTTON,
                DialogCommandPolicy.action(inputEvent, physicalCommand)).consumed()) {
            return WindowInput.Consumed.INSTANCE;
        }
        boolean startsAction = !(inputEvent instanceof PointerEvent pointer) || switch (pointer.kind()) {
            case PRESS, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT -> true;
            default -> false;
        };
        if (!saveAsMode && !confirmMode && startsAction) {
            notice().clearErrorOnUserAction();
        }
        if (binding == null && searchVisible() && !topMenu.isOpen() && DialogFocusTraversal.isTraversal(inputEvent)) {
            advanceSearchFocus((KeyStroke) inputEvent); return WindowInput.Consumed.INSTANCE;
        }
        if (binding != null && !confirmMode && !topMenu.isOpen())
            return TerminalEditorApp.resolveEditorInput(commandRouter, inputEvent, binding, null, currentRecipient());
        eventLoop.invalidate();
        if (modalState().isHelpHints() || modalState().isHelpLicenses() || modalState().isHelpAuthor()) {
            boolean pointerAllowed = !(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && terminal.mouseAvailable();
            if (pointerAllowed) {
                HelpOverlayInputRouter.Result helpInput = host.routeHelpOverlayInput(
                    currentHelpOverlay(), inputEvent, pointerCapture, this
                );
                if (helpInput.closeRequested()) {
                    host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                    setModal(UiModalState.none());
                    return WindowInput.Consumed.INSTANCE;
                }
                if (helpInput.consumed()) {
                    return WindowInput.Consumed.INSTANCE;
                }
            }
        }
        TuinkCommand routedSaveAsCommand = null;
        if (saveAsMode) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable())) {
                return WindowInput.Consumed.INSTANCE;
            }
            if (saveAsBarPlacement == null) {
                // Entering the modal always schedules a frame; keep
                // a malformed/custom runtime from leaking input.
                eventLoop.invalidate();
                return WindowInput.Consumed.INSTANCE;
            }
            String filenameBefore = ui().saveAsInput.text();
            SaveAsInputRouter.Result saveAsInputResult = SaveAsInputRouter.route(
                host.saveAsFocusManager(),
                saveAsBarPlacement,
                inputEvent, pointerCapture, this
            );
            routedSaveAsCommand = applySaveAsFieldAction(saveAsInputResult.action(), filenameBefore);
            if (inputEvent instanceof PointerEvent && saveAsInputResult.consumed()) {
                editorView.cancelMouseSelection();
            }
            if (routedSaveAsCommand == null && saveAsInputResult.consumed()) {
                return WindowInput.Consumed.INSTANCE;
            }
        }
        if (confirmMode) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable())) {
                return WindowInput.Consumed.INSTANCE;
            }
            if (confirmBarPlacement == null) {
                // A modal transition always schedules a frame before
                // input. Capture defensively if a custom runtime
                // violates that ordering.
                eventLoop.invalidate();
                return WindowInput.Consumed.INSTANCE;
            }
            ConfirmBarInputRouter.Result confirmInput = ConfirmBarInputRouter.route(
                host.confirmFocusManager(),
                confirmBarPlacement,
                host.confirmPolicy(modalState()),
                binding == null ? inputEvent : DialogCommandPolicy.eventFor(physicalCommand)
                    .<TuiInputEvent>map(event -> event).orElse(inputEvent)
            );
            ConfirmChoice decision = confirmInput.decision();
            if (decision != null) {
                notice().clearErrorOnUserAction();
            }
            if (decision != null && inputEvent instanceof PointerEvent) {
                editorView.cancelMouseSelection();
            }
            if (decision != null) return WindowInput.command(commandRouter, confirmationCommand(decision), currentRecipient());
            if (confirmInput.consumed()) return WindowInput.Consumed.INSTANCE;
        }
        if (ui().openFileMode) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable())) {
                return WindowInput.Consumed.INSTANCE;
            }
            if (openFileDialogPlacement == null) {
                eventLoop.invalidate();
                return WindowInput.Consumed.INSTANCE;
            }
            String locationBefore = ui().openFileFocusState.location().text();
            OpenFileInputRouter.Result openInput = OpenFileInputRouter.route(
                openFileDialogPlacement,
                ui().openFileFocusState,
                inputEvent,
                System.nanoTime(),
                TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS, pointerCapture, this
            );
            if (!locationBefore.equals(ui().openFileFocusState.location().text())) ui().pickerNotice = null;
            openFileTree().adopt(openInput.state());
            if (inputEvent instanceof PointerEvent && openInput.consumed()) {
                editorView.cancelMouseSelection();
            }
            if (openInput.intent() != null) {
                applyOpenFileResult(FilePickerOpenWorkflow.apply(openInput.intent(), openFileTree(),
                    host.toTabSessions(tabs), sessionContext.activeTabIndex(),
                    () -> TuiDocumentSession.create(host.clipboard), ui().pickerOpenMode));
                return WindowInput.Consumed.INSTANCE;
            }
            if (openInput.consumed()) {
                return WindowInput.Consumed.INSTANCE;
            }
            // The open-file dialog is modal even when a pointer
            // misses every current-frame control.
            editorView.cancelMouseSelection();
            return WindowInput.Consumed.INSTANCE;
        }
        CommandInvocation routedMenuCommand = null;
        CommandRouter.RoutedCommand routedMenuResult = null;
        if (modalState().isNone() && !ui().openFileMode) {
            boolean pointerAllowed = !(inputEvent instanceof PointerEvent)
                || uiSessionState.mouseModeSetting && terminal.mouseAvailable();
            if (!pointerAllowed && topMenu.isOpen()) {
                return WindowInput.Consumed.INSTANCE;
            }
            if (pointerAllowed) {
                if (topMenuPlacement == null) {
                    if (topMenu.isOpen()) {
                        eventLoop.invalidate();
                        return WindowInput.Consumed.INSTANCE;
                    }
                } else {
                    TopMenuInputRouter.Result menuInput = TopMenuInputRouter.route(
                        topMenu,
                        topMenuPlacement,
                        commandRouter,
                        inputEvent, pointerCapture, this
                    );
                    if (inputEvent instanceof PointerEvent && menuInput.consumed()) {
                        editorView.cancelMouseSelection();
                    }
                    if (menuInput.unavailable() != null) {
                        var command = TuinkCommand.fromId(menuInput.unavailable().id()).orElse(null);
                        var availability = activeSurface().availability(command);
                        notice().warning(availability.enabled() ? "Command is not available here" : availability.reason());
                        eventLoop.invalidate();
                        return WindowInput.Consumed.INSTANCE;
                    }
                    routedMenuCommand = menuInput.command();
                    if (routedMenuCommand != null) {
                        routedMenuResult = commandRouter.dispatch(routedMenuCommand)
                            .orElse(null);
                    } else if (menuInput.consumed()) {
                        return WindowInput.Consumed.INSTANCE;
                    }
                }
            }
        }
        SearchBarView.Action routedSearchAction = null;
        if (TerminalEditorApp.searchOwnsRawInput(
            ui().searchMode,
            modalState(),
            ui().openFileMode,
            topMenu.isOpen(),
            routedMenuCommand != null
        )) {
            if (inputEvent instanceof PointerEvent
                && (!uiSessionState.mouseModeSetting || !terminal.mouseAvailable())) {
                return WindowInput.Consumed.INSTANCE;
            }
            if (searchBarPlacement == null) {
                // Search activation schedules a frame before input;
                // capture defensively if a custom runtime violates it.
                eventLoop.invalidate();
                return WindowInput.Consumed.INSTANCE;
            }
            SearchInputRouter.Result searchInput = !ui().searchEditorFocused
                || inputEvent instanceof PointerEvent
                ? SearchInputRouter.route(host.searchFocusManager(), searchBarPlacement, inputEvent, pointerCapture, this)
                : new SearchInputRouter.Result(false, null);
            if (inputEvent instanceof PointerEvent pointer
                && pointer.kind() == PointerEvent.Kind.PRESS
                && pointer.button() == PointerEvent.Button.LEFT
                && searchInput.consumed()
                && host.searchFocusManager().focused().isPresent()) {
                ui().searchEditorFocused = false;
            }
            if (inputEvent instanceof PointerEvent && searchInput.consumed()) {
                editorView.cancelMouseSelection();
            }
            SearchBarView.Action searchAction = searchInput.action();
            if (consumeSearchControlAction(searchAction)) {
                return WindowInput.Consumed.INSTANCE;
            }
            routedSearchAction = searchAction;
            if (routedSearchAction == null && searchInput.consumed()) {
                return WindowInput.Consumed.INSTANCE;
            }
        }
        TuinkCommand routedSearchCommand = TerminalEditorApp.searchCommand(routedSearchAction);
        CommandInvocation pendingCommand = routedMenuCommand != null
            ? routedMenuCommand
            : routedSearchCommand != null
            ? CommandInvocation.of(routedSearchCommand.id())
            : routedSaveAsCommand == null ? binding : null;

        if (routedMenuCommand != null && routedMenuResult == null) return WindowInput.Consumed.INSTANCE;
        return routedSaveAsCommand != null
            ? WindowInput.command(commandRouter, routedSaveAsCommand, currentRecipient())
            : TerminalEditorApp.resolveEditorInput(commandRouter, inputEvent, pendingCommand, routedMenuResult, currentRecipient());
    }

    private static TuinkCommand confirmationCommand(ConfirmChoice choice) {
        return switch (choice) {
            case SAVE -> TuinkCommand.DIALOG_CONFIRM_SAVE;
            case DISCARD -> TuinkCommand.DIALOG_CONFIRM_DISCARD;
            case CANCEL -> TuinkCommand.DIALOG_CONFIRM_CANCEL;
        };
    }

    private static ConfirmChoice confirmationChoice(TuinkCommand command) {
        return switch (command) {
            case DIALOG_CONFIRM_SAVE -> ConfirmChoice.SAVE;
            case DIALOG_CONFIRM_DISCARD -> ConfirmChoice.DISCARD;
            case DIALOG_CONFIRM_CANCEL -> ConfirmChoice.CANCEL;
            default -> null;
        };
    }

    private boolean currentInput(WindowInput input) {
        UiRecipient recipient = switch (input) {
            case WindowInput.Command command -> command.recipient();
            case WindowInput.Text text -> text.recipient();
            case WindowInput.Gesture gesture -> gesture.recipient();
            case WindowInput.Consumed ignored -> null;
        };
        return recipient != null && current(recipient) && (recipient.scope() == UiRecipient.Scope.WINDOW
            || recipient.presentationId().equals(sessionContext.activePresentation().presentationId()));
    }

    private void dispatchWindowInput(WindowInput key) {
        if (!currentInput(key)) return;
        if (key instanceof WindowInput.Command command) { executeResolvedCommand(command); return; }
        if (key instanceof WindowInput.Gesture gesture && gesture.motion() instanceof WindowInput.Pointer pointer) {
            if (modalState().isNone() && ui().preview.active && ui().preview.searchVisible
                && (ui().preview.searchField.captures(pointer.event())
                    || pointer.event().row() == ui().preview.bounds.y()
                        && ui().preview.bounds.contains(pointer.event().column(), pointer.event().row()))
                && routePreviewSearchOwner(key)) return;
            routeDocumentPointer(pointer.event()); return;
        }
        if (ui().preview.active && ui().preview.searchVisible && routePreviewSearchOwner(key)) return;
        dispatchContentInput(key);
    }

    /** Semantic entry shared by chrome controls and programmatic application invocations. */
    void invokeCommand(CommandInvocation invocation, UiRecipient recipient) {
        if (systemClipboard.deferInput(() -> {
            prepareTurn();
            invokeCommand(invocation, recipient);
        })) return;
        try {
            commandRouter.dispatch(invocation).ifPresent(route ->
                executeResolvedCommand(new WindowInput.Command(invocation, route, recipient)));
        } finally {
            systemClipboard.synchronize();
        }
    }

    private void executeResolvedCommand(WindowInput.Command key) {
        executeResolvedCommand(key, true);
    }

    /** Mouse clipboard gestures resolve a real text owner before entering the common command path. */
    private boolean routeClipboardPointer(TuiInputEvent event) {
        if (!uiSessionState.rightClickClipboardSetting || !(event instanceof PointerEvent pointer)
            || pointer.kind() != PointerEvent.Kind.PRESS || pointer.button() != PointerEvent.Button.RIGHT
            || !pointer.modifiers().isEmpty() || topMenu.isOpen()) return false;

        if (modalState().isSaveAs() && saveAsBarPlacement != null
            && clipboardFieldPointer(pointer, saveAsBarPlacement.focusScope(), host.saveAsFocusManager(),
                saveAsBarPlacement::field, () -> focusUi().blockingContentFocused = false)) return true;
        if (modalState().isNone() && ui().openFileMode && openFileDialogPlacement != null
            && clipboardFieldPointer(pointer, openFileDialogPlacement.focusScope(), ui().openFileFocusState.manager(),
                id -> id == OpenFileDialogView.Control.LOCATION
                    ? java.util.Optional.of(openFileDialogPlacement.location()) : java.util.Optional.empty(),
                () -> { focusUi().blockingContentFocused = false;
                    ui().openFileFocusState.focus(OpenFileInputRouter.Focus.LOCATION); })) return true;
        if (modalState().isNone() && !ui().openFileMode) {
            if (ui().preview.active && ui().preview.searchVisible
                && clipboardFieldPointer(pointer, previewSearchScope(), ui().searchFocusManager,
                    id -> java.util.Optional.of(ui().preview.searchField), () -> ui().searchEditorFocused = false)) return true;
            if (!ui().preview.active && ui().searchMode && searchBarPlacement != null
                && clipboardFieldPointer(pointer, searchBarPlacement.focusScope(), host.searchFocusManager(),
                    searchBarPlacement::field, () -> ui().searchEditorFocused = false)) return true;
        }

        if (!documentPointerHit(pointer)) return false;
        if (ui().preview.active) {
            var preview = ui().preview;
            if (!preview.ready() || !PreviewScrollbars.from(preview.bounds, preview.result, preview.searchVisible,
                preview.top, preview.left).layout().contentBounds().contains(pointer.column(), pointer.row())) return false;
            focusDocument();
            return pointerClipboardAction(preview.selection.snapshot().selected(), preview.selection::collapseSelection);
        }
        if (plan == null || !dev.tuink.tui.ui.EditorScrollbars.from(plan, rows, cols, contentTopRow).layout().contentBounds()
            .contains(pointer.column(), pointer.row())) return false;
        focusDocument();
        return pointerClipboardAction(state.hasSelection(), () -> {
            Position caret = state.getCursor();
            activeDocument().execute(new DocumentCommand.Select(DocumentCommand.SelectionAction.RESTORE,
                new TextPosition(caret.line(), caret.col()), null));
        });
    }

    private <K, A> boolean clipboardFieldPointer(PointerEvent pointer, FocusScope<K, A> scope,
        FocusManager<K> focus,
        Function<K, Optional<TextField>> fields, Runnable focusOwner) {
        var target = ClipboardPointerRouter.fieldAt(scope, pointer, fields).orElse(null);
        if (target == null) return false;
        focus.focus(scope, target.id());
        focusOwner.run();
        return pointerClipboardAction(target.field().hasSelection(), target.field()::collapseSelection);
    }

    private boolean pointerClipboardAction(boolean selected, Runnable collapseSelection) {
        if (selected) {
            long before = host.clipboard.revision();
            invokeCommand(CommandInvocation.of(TuinkCommand.EDIT_COPY.id()), currentRecipient());
            if (host.clipboard.revision() != before) runDocumentPointerAction(collapseSelection);
        } else if (activeSurface().allows(TuinkCommand.EDIT_PASTE)) {
            invokeCommand(CommandInvocation.of(TuinkCommand.EDIT_PASTE.id()), currentRecipient());
        }
        eventLoop.invalidate();
        return true;
    }

    /** A late clipboard receipt may never paste into a replacement document or field. */
    private BooleanSupplier clipboardTarget(WindowInput.Command key, ActiveSurface surface) {
        WindowTab presentation = sessionContext.activePresentation();
        UiModalState modal = modalState();
        TextField field = clipboardField(surface);
        String text = field == null ? null : field.text();
        int cursor = field == null ? 0 : field.cursor();
        int start = field == null ? 0 : field.selectionStart();
        int end = field == null ? 0 : field.selectionEnd();
        TextModel document = field == null && presentation instanceof TerminalEditorApp.EditorTab source
            ? source.tabSession().model() : null;
        long revision = document == null ? 0 : document.revision();
        EditorState view = document == null ? null : ((TerminalEditorApp.EditorTab) presentation).tabSession().state();
        Position caret = view == null ? null : view.getCursor();
        Position anchor = view == null ? null : view.getSelectionAnchor();
        return () -> {
            if (disposed || !currentInput(key) || sessionContext.activePresentation() != presentation) return false;
            ActiveSurface current = activeSurface();
            // Other tabs may finish I/O while this read waits. Only the target and its
            // current editing permission can revoke the paste, not window-wide runtime state.
            return surface.kind() == current.kind() && surface.focus() == current.focus()
                && surface.modalScope() == current.modalScope() && current.allows(TuinkCommand.EDIT_PASTE)
                && modal.equals(modalState()) && clipboardField(current) == field
                && (field == null || text.equals(field.text()) && cursor == field.cursor()
                    && start == field.selectionStart() && end == field.selectionEnd())
                && (document == null || revision == document.revision() && caret.equals(view.getCursor())
                    && Objects.equals(anchor, view.getSelectionAnchor()));
        };
    }

    private TextField clipboardField(ActiveSurface surface) {
        if (!surface.editableField()) return null;
        if (modalState().isSaveAs()) return ui().saveAsInput;
        if (ui().openFileMode) return ui().openFileFocusState.location();
        if (surface.preview()) return ui().preview.searchField;
        return host.currentSearchFocus() == SearchFocus.REPLACE ? ui().searchReplaceInput : ui().searchQueryInput;
    }

    private void executeResolvedCommand(WindowInput.Command key, boolean readSystemClipboard) {
        if (!currentInput(key)) return;
        eventLoop.invalidate();
        TuinkCommand availability = key.route() instanceof CommandRouter.ActivateTab ? TuinkCommand.TAB_ACTIVATE
            : ((CommandRouter.RoutedAction) key.route()).action();
        availability = previewSearchIntent(availability);
        ActiveSurface surface = activeSurface();
        if (isFocusTraversalCommand(availability) && !modalState().isDocumentFormat()) {
            if (TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) != ModalInputPolicy.Kind.NONE) {
                advanceBlockingFocus(KeyStroke.of(KeyStroke.Key.TAB)); return;
            }
            if (searchVisible()) {
                if (surface.allows(TuinkCommand.SEARCH_FOCUS_NEXT)) advanceSearchFocus(KeyStroke.of(KeyStroke.Key.TAB));
                return;
            }
        }
        if (surface.focus() != ActiveSurface.Focus.CONTENT && ControlCommandRouter.unsupportedLocally(availability)) return;
        var access = surface.availability(availability);
        if (!access.enabled()) { notice().warning(access.reason()); return; }
        if (readSystemClipboard && availability == TuinkCommand.EDIT_PASTE
            && surface.focus() != ActiveSurface.Focus.CONTROL) {
            var target = clipboardTarget(key, surface);
            systemClipboard.paste(target, () -> executeResolvedCommand(key, false));
            return;
        }
        if (key.route() instanceof CommandRouter.ActivateTab tab) {
            if (tab.tabIndex() >= 0 && tab.tabIndex() < sessionContext.presentations().size())
                activatePresentation(tab.tabIndex());
            return;
        }
        TuinkCommand resolvedCommand = ((CommandRouter.RoutedAction) key.route()).action();
        if (executeTabCommand(resolvedCommand, key.invocation())) return;
        if (modalState().isDocumentFormat()) {
            if (resolvedCommand == TuinkCommand.APP_QUIT) closeFormatDialog();
            else if (handleFormatCommand(resolvedCommand)) return;
            else {
                KeyStroke control = ControlCommandRouter.eventFor(resolvedCommand).orElse(null);
                if (control != null && formatPlacement != null) {
                    var action = formatPlacement.handle(control, pointerCapture, this);
                    if (action != DocumentFormatDialogView.Action.NONE)
                        handleFormatCommand(action == DocumentFormatDialogView.Action.APPLY
                            ? TuinkCommand.DOCUMENT_FORMAT_APPLY : TuinkCommand.DOCUMENT_FORMAT_CANCEL);
                }
                eventLoop.invalidate(); return;
            }
        }
        if (ui().openFileMode) {
            if (openFileDialogPlacement != null) {
                String before = ui().openFileFocusState.location().text();
                var input = OpenFileInputRouter.routeCommand(openFileDialogPlacement, ui().openFileFocusState,
                    resolvedCommand, host.clipboard);
                if (!before.equals(ui().openFileFocusState.location().text())) ui().pickerNotice = null;
                openFileTree().adopt(input.state());
                if (input.intent() != null) applyOpenFileResult(FilePickerOpenWorkflow.apply(input.intent(), openFileTree(),
                    host.toTabSessions(tabs), sessionContext.activeTabIndex(), () -> TuiDocumentSession.create(host.clipboard), ui().pickerOpenMode));
            }
            return;
        }
        if (modalState().isHelpHints() || modalState().isHelpLicenses() || modalState().isHelpAuthor()) {
            KeyStroke control = ControlCommandRouter.eventFor(resolvedCommand).orElse(null);
            if (control != null) {
                var input = host.routeHelpOverlayInput(currentHelpOverlay(), control, pointerCapture, this);
                if (input.closeRequested()) { host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP); setModal(UiModalState.none()); }
                if (input.consumed()) return;
            }
        }
        if (modalState().isSaveAs() && !focusUi().blockingContentFocused && ControlCommandRouter.owns(resolvedCommand)) {
            if (saveAsBarPlacement == null) return;
            String before = ui().saveAsInput.text();
            var input = SaveAsInputRouter.routeCommand(host.saveAsFocusManager(), saveAsBarPlacement, resolvedCommand, host.clipboard);
            TuinkCommand intent = applySaveAsFieldAction(input.action(), before);
            if (input.consumed() && intent == null) return;
            if (intent != null) resolvedCommand = intent;
        }
        if (resolvedCommand == TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW
            && modalState().isNone() && !ui().openFileMode) { switchMarkdownPresentation(); return; }
        if (focusUi().blockingContentFocused
            && TerminalEditorApp.modalInputKind(modalState(), ui().openFileMode) != ModalInputPolicy.Kind.NONE) {
            if (!handleApplicationCommand(resolvedCommand)) dispatchContentInput(key);
            return;
        }
        ConfirmChoice routedConfirmDecision = resolvedCommand == null ? null : confirmationChoice(resolvedCommand);
        if (sessionContext.activePresentation() instanceof PreviewTab selected && !globalModal(modalState())) {
            if (routedConfirmDecision != null || saveAsMode) {
                if (resolvedCommand != null) handlePreviewSaveCommand(selected, resolvedCommand);
                return;
            }
        }
        if (TerminalEditorApp.searchOwnsRawInput(ui().searchMode, modalState(), ui().openFileMode, topMenu.isOpen())
            && ui().searchEditorFocused
            && resolvedCommand == TuinkCommand.UI_ESCAPE) {
            ui().searchMode = false;
            host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
            return;
        }
        if (modalState().isHelpHints()) {
            HelpHintsController.Result helpHintsResult = HelpHintsController.handle(
                key,
                true
            );
            if (helpHintsResult.consumed()) {
                if (!helpHintsResult.helpHintsMode()) {
                    host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                }
                setModal(helpHintsResult.helpHintsMode() ? UiModalState.helpHints() : UiModalState.none());
                return;
            }
        }

        if (modalState().isHelpLicenses()) {
            TuinkCommand action = resolvedCommand;
            if (action == TuinkCommand.UI_ESCAPE
                || action == TuinkCommand.EDIT_INSERT_NEWLINE
                || action == TuinkCommand.HELP_LICENSES) {
                host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                setModal(UiModalState.none());
            }
            return;
        }

        if (modalState().isHelpAuthor()) {
            TuinkCommand action = resolvedCommand;
            if (action == TuinkCommand.UI_ESCAPE
                || action == TuinkCommand.EDIT_INSERT_NEWLINE
                || action == TuinkCommand.HELP_AUTHOR) {
                host.helpFocusManager().deactivate(HelpOverlayInputRouter.Scope.HELP);
                setModal(UiModalState.none());
            }
            return;
        }

        if (modalState().isRecoveryConfirm()) {
            if (routedConfirmDecision != null) {
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    var restored = host.restoreRecoveredTabs(
                        startupRecoveryEntries,
                        sessionContext,
                        recoveryAutosave,
                        System.nanoTime()
                    );
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(restored.failedEntries());
                    int restoredCount = restored.restoredCount();
                    if (restoredCount > 0) {
                        refreshMarkdownHighlighters();
                        activeTabIndex = sessionContext.activeTabIndex();
                        state = sessionContext.state();
                        openFileTree().setRoot(FilePickerOpenWorkflow.initialRoot(JvmDocumentStorage.pathOf(state.getCurrentResource())));
                        notice().info("Recovered " + restoredCount + " tab(s)");
                    } else {
                        notice().info("No recovery snapshots restored");
                    }
                    if (!restored.failedEntries().isEmpty()) {
                        notice().error("Could not restore " + restored.failedEntries().size()
                            + " snapshot(s); data kept. Choose Later or Delete. "
                            + String.join("; ", restored.failureDetails()));
                        return;
                    }
                } else if (routedConfirmDecision == ConfirmChoice.DISCARD) {
                    TerminalEditorApp.RecoveryAutosaveManager.DeletionResult deletion =
                        recoveryAutosave.deleteEntries(startupRecoveryEntries);
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(deletion.failedEntries());
                    if (!deletion.successful()) {
                        notice().error(deletion.failureMessage());
                        return;
                    }
                    notice().info("Recovery snapshots discarded");
                } else {
                    var release = recoveryAutosave.releaseEntries(startupRecoveryEntries);
                    startupRecoveryEntries.clear();
                    startupRecoveryEntries.addAll(release.failedEntries());
                    if (!release.successful()) {
                        notice().error(release.failureMessage());
                        return;
                    }
                    notice().info("Recovery postponed; snapshots kept for next launch or File > Recover drafts");
                }
                setModal(UiModalState.none());
                return;
            }
        }

        if (modalState().isSaveAs()) {
            if (resolvedCommand == TuinkCommand.UI_ESCAPE || resolvedCommand == TuinkCommand.APP_QUIT) {
                cancelPendingSave(activeEditingView());
            }
            applySaveResult(SaveWorkflow.saveAsInput(saveContext(), resolvedCommand, modalState(), ui().saveAsInput.text()));
            return;
        }
        if (routedConfirmDecision != null) {
            if (modalState().isOpenUtf16Confirm()) {
                Path target = modalState().pendingOpenPath();
                setModal(UiModalState.none());
                if (routedConfirmDecision == ConfirmChoice.SAVE) {
                    applyOpenFileResult(FilePickerOpenWorkflow.openAsUtf16(target,
                        host.toTabSessions(tabs), sessionContext.activeTabIndex(),
                        () -> TuiDocumentSession.create(host.clipboard)));
                } else {
                    ui().openFileMode = true;
                }
                return;
            }
            if (modalState().isSaveUtf8Confirm()) {
                applySaveResult(SaveWorkflow.encodingChoice(modalState(), routedConfirmDecision));
                return;
            }
            if (modalState().isSaveOverwriteConfirm()) {
                applySaveResult(SaveWorkflow.overwrite(saveContext(), modalState(), routedConfirmDecision));
                return;
            }
            if (modalState().isQuitConfirm()) {
                if (routedConfirmDecision == ConfirmChoice.SAVE && finishPendingDialogsBeforeQuit()) return;
                applyCloseDecision(ApplicationCloseWorkflow.choose(closeState, switch (routedConfirmDecision) {
                    case SAVE -> ApplicationCloseWorkflow.Choice.SAVE;
                    case DISCARD -> ApplicationCloseWorkflow.Choice.DISCARD;
                    case CANCEL -> ApplicationCloseWorkflow.Choice.CANCEL;
                }));
                if (routedConfirmDecision != ConfirmChoice.SAVE) applySaveResult(SaveWorkflow.quit(saveContext(), routedConfirmDecision));
                return;
            }
            if (modalState().isCloseTabConfirm()) {
                applySaveResult(SaveWorkflow.closeTab(saveContext(), modalState(), routedConfirmDecision));
                return;
            }
        }

        if (ui().searchMode && !ui().searchEditorFocused && resolvedCommand == TuinkCommand.SEARCH_OPEN) {
            executeSearchDocumentCommand(TuinkCommand.SEARCH_NEXT); return;
        }
        CommandRecipient recipient = CommandRecipient.resolve(resolvedCommand,
            ui().searchMode, ui().searchEditorFocused);
        if (recipient == CommandRecipient.SEARCH_CONTROL) {
            if (searchBarPlacement != null) {
                SearchInputRouter.Result result = SearchInputRouter.routeCommand(host.searchFocusManager(),
                    searchBarPlacement, resolvedCommand, host.clipboard);
                if (!consumeSearchControlAction(result.action())) {
                    TuinkCommand intent = TerminalEditorApp.searchCommand(result.action());
                    if (intent != null) executeSearchDocumentCommand(intent);
                }
            }
            return;
        }
        if (recipient == CommandRecipient.SEARCH_DOCUMENT) {
            executeSearchDocumentCommand(resolvedCommand);
            return;
        }

        if (ui().preview.active && ui().preview.searchVisible && routePreviewSearchOwner(key)) return;
        if (handleApplicationCommand(resolvedCommand)) return;
        if (resolvedCommand == TuinkCommand.UI_ESCAPE) { cancelCurrentOperation(); return; }
        dispatchContentInput(key);
    }

    /** The single content target for ordinary, captured and modal-content delivery. */
    private void dispatchContentInput(WindowInput key) {
        if (!currentInput(key)) return;
        if (key instanceof WindowInput.Gesture gesture) {
            if (gesture.motion() instanceof WindowInput.Pointer pointer) {
                if (ui().preview.active) {
                    if (pointer.event().kind() == PointerEvent.Kind.PRESS && pointer.event().button() == PointerEvent.Button.LEFT)
                        focusDocument();
                    ui().preview.pointer(pointer.event());
                }
                else runDocumentPointerAction(() -> handleDocumentPointer(pointer.event()));
            } else if (ui().preview.active && gesture.motion() instanceof WindowInput.Pan pan)
                ui().preview.scroll(pan.rows(), pan.columns());
            eventLoop.invalidate();
            return;
        }
        TuinkCommand resolvedCommand = key instanceof WindowInput.Command resolved
            && resolved.route() instanceof CommandRouter.RoutedAction action ? action.action() : null;
        if (ui().preview.active) {
            if (resolvedCommand != null) handlePreviewContentCommand(resolvedCommand);
            return;
        }
        if (key instanceof WindowInput.Text text && text.pasted()) {
            if (text.truncated()) {
                notice().error("Paste is too large and was not inserted");
                return;
            }
            if (modalState().isNone() && !ui().openFileMode && !topMenu.isOpen()) {
                if (rejectSavingDocumentEdit()) return;
                if (uiSessionState.readingModeSetting) {
                    notice().warning("Reading mode: editing is disabled");
                } else {
                    long revisionBefore = model.revision();
                    Position cursorBefore = state.getCursor();
                    try {
                        activeDocument().execute(new DocumentCommand.Paste(text.value()));
                    } catch (DocumentEditLimitException limit) {
                        notice().error(limit.getMessage());
                        return;
                    }
                    if (revisionBefore != model.revision() || !cursorBefore.equals(state.getCursor()))
                        viewportController.onEditorTextInteraction();
                }
            }
            return;
        }
        if (key instanceof WindowInput.Text charInput) {
            if (rejectSavingDocumentEdit()) {
                return;
            } else if (uiSessionState.readingModeSetting) {
                notice().warning("Reading mode: editing is disabled");
            } else {
                long revisionBefore = model.revision();
                Position cursorBefore = state.getCursor();
                try {
                    activeDocument().execute(new DocumentCommand.Input(charInput.value()));
                } catch (DocumentEditLimitException limit) {
                    notice().error(limit.getMessage());
                    return;
                }
                if (revisionBefore != model.revision() || !cursorBefore.equals(state.getCursor())) {
                    viewportController.onEditorTextInteraction();
                }
            }
            return;
        }

        TuinkCommand action = resolvedCommand;
        if (action.editsDocumentText() && rejectSavingDocumentEdit()) return;
        if (handleFormatCommand(action)) return;
        InputActionDispatcher.DispatchResult dispatchResult = dispatchInputAction(action);
        if (dispatchResult.handled()) {
            viewportController.onCommandResult(dispatchResult.viewportPolicy());
            applyUiIntent(dispatchResult.intent());
            if (dispatchResult.statusMessage() != null && !dispatchResult.statusMessage().isBlank()) {
                if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.ERROR) {
                    notice().error(dispatchResult.statusMessage());
                } else if (dispatchResult.statusLevel() == InputActionDispatcher.StatusLevel.WARNING) {
                    notice().warning(dispatchResult.statusMessage());
                } else {
                    notice().info(dispatchResult.statusMessage());
                }
            }
            reportTemporaryPreferencesChange();
            return;
        }
    }

    private void reopenRecovery() {
        if (disposed) return;
        if (documentIo.busy()) {
            notice().info("A file operation is already in progress; try Recover drafts again when it finishes");
            return;
        }
        Object request = new Object();
        UiRecipient recipient = currentRecipient();
        UiRecipient window = UiRecipient.window(globalUi, null);
        pendingRecoveryDiscovery = request;
        notice(recipient).info("Looking for recovery drafts…");
        documentIo.submit(recoveryAutosave::discoverAvailable, completion -> {
            var discovered = completion.value();
            if (pendingRecoveryDiscovery != request || disposed) {
                if (discovered != null) documentIo.dispose(discovered, recoveryAutosave::releaseDiscovered);
                return;
            }
            pendingRecoveryDiscovery = null;
            if (completion.failure() != null) {
                if (current(recipient)) notice(recipient).error("Could not discover recovery drafts: " + completion.failure().getMessage());
                afterDocumentIo();
                return;
            }
            if (!windowModal.isNone() || current(recipient)
                && (!modalState(recipient).isNone() || recipient.state().openFileMode || recipient.state().searchMode)) {
                documentIo.dispose(discovered, recoveryAutosave::releaseDiscovered);
                if (current(recipient)) notice(recipient).info("Finish the current dialog, then choose File > Recover drafts again");
                afterDocumentIo();
                return;
            }
            var knownIds = startupRecoveryEntries.stream().map(RecoveryStore.PendingSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
            for (var entry : discovered.entries()) {
                if (knownIds.add(entry.id())) startupRecoveryEntries.add(entry);
            }
            if (!startupRecoveryEntries.isEmpty()) {
                setModal(window, UiModalState.recoveryConfirm());
            } else if (discovered.skipped().isEmpty()) {
                if (current(recipient)) notice(recipient).info("No recovery drafts available");
            }
            if (!discovered.skipped().isEmpty()) {
                if (current(recipient)) notice(windowModal.isRecoveryConfirm() ? window : recipient).warning("Skipped " + discovered.skipped().size()
                    + " incompatible recovery snapshot(s); their data was kept");
            }
            afterDocumentIo();
        }, recoveryAutosave::releaseDiscovered);
    }

    private void cancelRecoveryDiscovery() {
        if (pendingRecoveryDiscovery == null) return;
        pendingRecoveryDiscovery = null;
        documentIo.cancelRead();
    }

    private boolean handleFormatCommand(TuinkCommand command) {
        DocumentFormatDialogView.Purpose purpose = switch (command) {
            case DOCUMENT_FORMAT -> DocumentFormatDialogView.Purpose.FORMAT;
            case DOCUMENT_OPEN_ENCODING -> DocumentFormatDialogView.Purpose.OPEN;
            case DOCUMENT_REOPEN_ENCODING -> DocumentFormatDialogView.Purpose.REOPEN;
            default -> null;
        };
        if (purpose != null) {
            boolean preview = sessionContext.activePresentation() instanceof PreviewTab;
            if (preview && purpose != DocumentFormatDialogView.Purpose.OPEN) {
                notice().info("Use the source tab to change or reopen its format"); return true;
            }
            if (documentIo.busy() || !preview && activeDocument().document().io().busy()) {
                notice().info("A file operation is already in progress");
                return true;
            }
            if (purpose == DocumentFormatDialogView.Purpose.REOPEN && state.getCurrentResource() == null) {
                notice().info("Save this document before reopening it with an encoding");
                return true;
            }
            topMenu.close();
            cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
            ui().openFileMode = false;
            dev.tuink.editor.format.DocumentFormatSummary summary;
            if (preview) {
                FrozenDocumentSource captured = previewSource((PreviewTab) sessionContext.activePresentation());
                boolean finalNewline = captured.text().lineCount() > 1
                    && captured.text().lineText(captured.text().lineCount() - 1).isEmpty();
                summary = dev.tuink.editor.format.DocumentFormatSummary.from(captured.metadata(), finalNewline);
            } else summary = activeDocument().document().formatSummary();
            ui().formatDialog = new DocumentFormatDialogView.State(purpose, summary, DocumentFormatDialogView.Target.ENCODING);
            UiModalState modal = UiModalState.documentFormat();
            setModal(activeEditingView() == null ? modal : modal.bindTo(activeEditingView()));
            cancelDocumentPointerInteraction();
            eventLoop.invalidate();
            return true;
        }
        if (command != TuinkCommand.DOCUMENT_FORMAT_APPLY && command != TuinkCommand.DOCUMENT_FORMAT_CANCEL) return false;
        if (!modalState().isDocumentFormat() || ui().formatDialog == null) return true;
        if (command == TuinkCommand.DOCUMENT_FORMAT_CANCEL) {
            closeFormatDialog();
            return true;
        }
        var draft = ui().formatDialog;
        if (draft.busy) return true;
        var access = DocumentFormatPolicy.apply(draft.purpose, activeSurface());
        if (!access.allowed()) {
            draft.error = access.refusal();
            notice().warning(access.refusal());
            eventLoop.invalidate();
            return true;
        }
        if (draft.purpose == DocumentFormatDialogView.Purpose.OPEN) {
            var mode = draft.openMode();
            closeFormatDialog();
            ui().pickerOpenMode = mode;
            ui().openFileMode = true;
            ui().pickerNotice = null;
            ui().lastPickerDirectoryFailure = null;
            openFileTree().setRoot(FilePickerOpenWorkflow.initialRoot(currentPresentationPath()));
            ui().openFileFocusState.focus(OpenFileInputRouter.Focus.LIST);
            eventLoop.invalidate();
            return true;
        }
        if (draft.purpose == DocumentFormatDialogView.Purpose.REOPEN && state.isDirty() && !draft.confirmDiscard) {
            draft.confirmDiscard = true;
            draft.requestedFocus = DocumentFormatDialogView.Target.CANCEL;
            eventLoop.invalidate();
            return true;
        }
        UiRecipient recipient = currentRecipient();
        TabUiState owner = recipient.state();
        TuiDocumentSession document = activeDocument();
        long submittedNoticeRevision = notice(recipient).revision();
        formatWorkflow.apply(document, draft,
            () -> !disposed && current(recipient) && owner.formatDialog == draft,
            loaded -> WorkspaceDocuments.findOpenFile(tabs.stream()
                .filter(tab -> tab.documentSession().document() != document.document())
                .map(TerminalEditorApp.EditorTab::tabSession).toList(), -1,
                ((dev.tuink.storage.jvm.FileBaseline) loaded.baseline()).target()) < 0,
            outcome -> {
                if (!current(recipient) || owner.formatDialog != draft) return;
                if (outcome.succeeded()) {
                    owner.formatDialog = null;
                    setModal(recipient, UiModalState.none());
                    UiEffects.notice(notice(recipient), SaveWorkflow.Notice.success(outcome.message()), submittedNoticeRevision);
                } else {
                    draft.error = outcome.message();
                    notice(recipient).error(outcome.message());
                }
                eventLoop.invalidate();
            });
        eventLoop.invalidate();
        return true;
    }

    private void closeFormatDialog() {
        if (sessionContext.active() != null && formatWorkflow.affects(sessionContext.active().documentSession())) formatWorkflow.cancel();
        cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
        ui().formatDialog = null;
        formatPlacement = null;
        setModal(UiModalState.none());
        eventLoop.invalidate();
    }

    private void applyOpenFileResult(FilePickerOpenWorkflow.Result result) {
        applyOpenFileResult(currentRecipient(), result);
    }

    private void applyOpenFileResult(UiRecipient recipient, FilePickerOpenWorkflow.Result result) {
        applyOpenFileResult(recipient, result, -1);
    }

    private void applyOpenFileResult(UiRecipient recipient, FilePickerOpenWorkflow.Result result, long submittedNoticeRevision) {
        if (!current(recipient)) return;
        TabUiState owner = recipient.state();
        boolean publishToOpenedView = !background(recipient) && windowModal.isNone();
        if (result.pendingOpen() != null) {
            if (documentIo.busy() || owner.deferredOpenResult != null) {
                notice(recipient).info("A file operation is already in progress"); return;
            }
            var request = result.pendingOpen();
            pendingOpen = request;
            owner.pendingOpen = request;
            owner.openFileMode = false;
            owner.pickerNotice = null;
            notice(recipient).info("Opening document…");
            long noticeRevision = notice(recipient).revision();
            documentIo.submit(request::inspect, completion -> {
                boolean currentAttempt = pendingOpen == request && owner.pendingOpen == request;
                if (currentAttempt) { pendingOpen = null; owner.pendingOpen = null; }
                if (!disposed && currentAttempt && current(recipient)) {
                    applyOpenFileResult(recipient, FilePickerOpenWorkflow.completeInspected(
                        request, completion.value(), completion.failure(), host.toTabSessions(tabs), viewIndex(recipient),
                        () -> TuiDocumentSession.create(host.clipboard)), noticeRevision);
                } else request.cancel();
                afterDocumentIo();
            });
            return;
        }
        if (result.navigateDirectory() != null) {
            openFileTree(recipient).setRoot(result.navigateDirectory());
            owner.openFileFocusState.focus(OpenFileInputRouter.Focus.LIST);
            owner.pickerNotice = null;
        }
        UiModalState modal = modalState(recipient);
        boolean documentPromptActive = modal.isDocumentFormat() || modal.isSaveAs() || modal.isSaveOverwriteConfirm()
            || modal.isSaveUtf8Confirm() || modal.isCloseTabConfirm();
        if (documentPromptActive && (result.openFileMode() || result.pendingExplicitOpenPath() != null)) {
            owner.deferredOpenResult = result;
            if (result.noticeKind() == FilePickerOpenWorkflow.NoticeKind.ERROR) notice(recipient).error(result.message());
            else UiEffects.information(notice(recipient), "Finish the current dialog to choose the file encoding", submittedNoticeRevision);
            return;
        }
        ViewId openedView = null;
        if (result.openedDocument() != null) {
            TuiDocumentSession document = result.openedDocument();
            RenderPlanBuilder builder = document.newRenderPlanBuilder(
                host.resolveHighlighter(JvmDocumentStorage.pathOf(document.view().state().getCurrentResource()),
                    sourceSyntax()));
            sessionContext.addAll(List.of(new TerminalEditorApp.EditorTab(document, builder)));
            if (!documentPromptActive && publishToOpenedView) {
                openedView = document.viewId();
                activateTab(sessionContext.indexOf(openedView));
            }
        } else if (result.activateViewId() != null && !documentPromptActive && publishToOpenedView) {
            openedView = result.activateViewId();
            activateTab(sessionContext.indexOf(openedView));
        }
        owner.openFileMode = result.openFileMode();
        if (result.pendingExplicitOpenPath() != null) setModal(recipient, UiModalState.openUtf16Confirm(result.pendingExplicitOpenPath()));
        if (!owner.openFileMode) owner.pickerNotice = null;
        switch (result.noticeKind()) {
            case UNCHANGED -> { }
            case SUCCESS -> {
                if (documentPromptActive && result.activateTabIndex() >= 0)
                    UiEffects.information(notice(recipient), "File is already open in tab " + (result.activateTabIndex() + 1), submittedNoticeRevision);
                else UiEffects.notice(notice(recipient), SaveWorkflow.Notice.success(result.message()), submittedNoticeRevision);
            }
            case WARNING -> {
                if (owner.openFileMode) owner.pickerNotice = result.notice();
                else if (!UiEffects.preserveNotice(notice(recipient), submittedNoticeRevision)) notice(recipient).warning(result.message());
            }
            case ERROR -> {
                if (owner.openFileMode) owner.pickerNotice = result.notice();
                else notice(recipient).error(result.message());
            }
        }
        // Foreground Open intentionally hands its success to the opened ViewId.
        if (result.noticeKind() == FilePickerOpenWorkflow.NoticeKind.SUCCESS && openedView != null
            && !Objects.equals(openedView, recipient.viewId())) {
            var opened = sessionContext.source(openedView);
            if (opened != null) {
                var destination = notice(UiRecipient.presentation(opened, UiRecipient.Flow.LOCAL));
                if (!destination.requiresAttention()) destination.success(result.message());
            }
        }
        if (result.openedDocument() != null) {
            var newlyOpened = sessionContext.source(result.openedDocument().viewId());
            applyMarkdownOpenPolicy(newlyOpened, !documentPromptActive && publishToOpenedView);
        }
    }

    private TuiDocumentSession activeDocument() {
        return tabs.get(activeEditingIndex()).documentSession();
    }

    private ViewId activeEditingView() {
        WindowTab selected = sessionContext.activePresentation();
        return selected instanceof TerminalEditorApp.EditorTab source ? source.documentSession().viewId()
            : selected instanceof PreviewTab preview && preview.source() instanceof PreviewTab.LiveSource live ? live.viewId() : null;
    }

    private int activeEditingIndex() {
        ViewId view = activeEditingView();
        return view == null ? -1 : sessionContext.indexOf(view);
    }

    private SaveWorkflow.Context saveContext() { return saveContext(currentRecipient()); }

    private SaveWorkflow.Context saveContext(UiRecipient recipient) {
        java.util.Map<ViewId, Integer> displayNumbers = new java.util.HashMap<>();
        for (int index = 0; index < sessionContext.presentations().size(); index++) {
            if (sessionContext.presentations().get(index) instanceof TerminalEditorApp.EditorTab source)
                displayNumbers.put(source.documentSession().viewId(), index + 1);
        }
        return new SaveWorkflow.Context(tabs.stream().map(TerminalEditorApp.EditorTab::documentSession).toList(),
            viewIndex(recipient), saveAsSuggestions, recipient.state().overwriteApproval, displayNumbers);
    }

    private boolean applyFilenameEffect(SaveFlowController.TextInputEffect effect) {
        return applyFilenameEffect(currentRecipient(), effect);
    }

    private boolean applyFilenameEffect(UiRecipient recipient, SaveFlowController.TextInputEffect effect) {
        if (!current(recipient)) return false;
        if (UiEffects.filename(recipient.state(), effect)) return true;
        setModal(recipient, UiModalState.none());
        if (!background(recipient)) saveAsBarPlacement = null;
        if (recipient.flow() == UiRecipient.Flow.WINDOW) closeState = ApplicationCloseWorkflow.cancel(closeState);
        recipient.state().saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
        notice(recipient).error("Not enough memory to open Save As; the filename was not changed");
        return false;
    }

    private void applySaveResult(SaveWorkflow.Result result) { applySaveResult(currentRecipient(), result, null); }

    private void applySaveResult(UiRecipient origin, SaveWorkflow.Result result, DocumentId savedDocumentId) {
        ViewId view = result.modalState().targetViewId();
        if (view == null && result.pendingSave() != null) view = result.pendingSave().session().viewId();
        if (view == null && result.activateTabIndex() >= 0 && result.activateTabIndex() < tabs.size())
            view = tabs.get(result.activateTabIndex()).documentSession().viewId();
        boolean global = origin.flow() == UiRecipient.Flow.WINDOW || globalModal(modalState(origin))
            || result.modalState().saveContinuation() == SaveContinuation.QUIT
            || result.pendingSave() != null && isGlobalSave(result.pendingSave());
        UiRecipient recipient;
        boolean dismissWindow = globalModal(modalState(origin)) && result.modalState().isNone()
            && result.activateTabIndex() < 0 && result.closeTabIndex() < 0 && result.pendingSave() == null
            && result.filename().kind() == SaveFlowController.TextInputEffectKind.NONE;
        if (dismissWindow) recipient = UiRecipient.window(globalUi, origin.viewId());
        else if (view == null) recipient = origin;
        else if (global) recipient = saveRecipient(view, UiRecipient.Flow.WINDOW);
        else if (view.equals(origin.viewId()) && origin.scope() == UiRecipient.Scope.PRESENTATION) recipient = origin;
        else recipient = saveRecipient(view, UiRecipient.Flow.LOCAL);
        if (!current(recipient)) {
            if (result.pendingSave() != null) result.pendingSave().session().document().io().cancel(result.pendingSave().operation());
            return;
        }
        applySaveResultOnOwner(recipient, result, savedDocumentId, -1, false);
    }

    private void applySaveResultOnOwner(UiRecipient recipient, SaveWorkflow.Result result, DocumentId savedDocumentId,
        long submittedNoticeRevision, boolean preserveFilename) {
        if (result.refreshSavedHighlighter() && (savedDocumentId != null || viewIndex(recipient) >= 0))
            refreshSavedHighlighter(savedDocumentId == null
                ? tabs.get(viewIndex(recipient)).documentSession().document().documentId() : savedDocumentId);
        WindowTab owner = recipient.presentationId() == null ? null : sessionContext.presentation(recipient.presentationId());
        if (result.activateTabIndex() >= 0 && !background(recipient)
            && (recipient.flow() == UiRecipient.Flow.WINDOW || !(owner instanceof PreviewTab))) activateTab(result.activateTabIndex());
        if (result.closeTabIndex() >= 0) {
            ViewId closing = tabs.get(result.closeTabIndex()).documentSession().viewId();
            boolean closingActive = closing.equals(activeEditingView());
            if (closingActive) { cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED); cancelDocumentPointerInteraction(); }
            host.closeTab(sessionContext, closing, saveAsSuggestions);
            if (closingActive) {
                searchBarPlacement = null; saveAsBarPlacement = null; confirmBarPlacement = null;
                openFileDialogPlacement = null; openFileTreePlacement = null; helpOverlayPlacement = null;
            }
        }
        if (!current(recipient)) return;
        if (!preserveFilename && !applyFilenameEffect(recipient, result.filename())) return;
        UiRecipient noticeOwner = recipient.flow() == UiRecipient.Flow.WINDOW && globalModal(result.modalState())
            ? UiRecipient.window(globalUi, recipient.viewId()) : recipient;
        applySaveNotice(noticeOwner, result.notice(), submittedNoticeRevision);
        if (recipient.flow() == UiRecipient.Flow.WINDOW || result.modalState().isQuitConfirm()
            || result.pendingSave() != null && isGlobalSave(result.pendingSave())) {
            boolean continuesQuit = result.modalState().saveContinuation() == SaveContinuation.QUIT
                || result.pendingSave() != null && isGlobalSave(result.pendingSave());
            var progress = !result.running() ? ApplicationCloseWorkflow.SaveProgress.FINISHED
                : result.modalState().isQuitConfirm() ? ApplicationCloseWorkflow.SaveProgress.CONFIRM
                : continuesQuit ? ApplicationCloseWorkflow.SaveProgress.CONTINUE
                : ApplicationCloseWorkflow.SaveProgress.CANCELLED;
            closeState = ApplicationCloseWorkflow.saveProgress(closeState, recipient, progress);
        }
        if (submittedNoticeRevision < 0 || !UiEffects.preserveModal(modalState(recipient), result.modalState(), preserveFilename))
            setModal(recipient, result.modalState().bindTo(recipient.viewId()));
        recipient.state().overwriteApproval = result.overwriteApproval();
        if (result.focusFilename()) UiEffects.focusFilename(recipient.state());
        if (result.pendingSave() != null && !disposed)
            startSave(recipient, result.pendingSave(), submittedNoticeRevision);
    }

    private PreviewTab livePreviewOf(DocumentId documentId) {
        for (WindowTab tab : sessionContext.presentations()) {
            if (tab instanceof PreviewTab preview && preview.source() instanceof PreviewTab.LiveSource live
                && live.documentId().equals(documentId)) return preview;
        }
        return null;
    }

    private void refreshLivePreviewBinding(SaveWorkflow.SaveRequest request) {
        PreviewTab preview = livePreviewOf(request.session().document().documentId());
        if (preview == null) return;
        PreviewTab next = preview.saved();
        sessionContext.updatePreview(next);
        var saved = request.session().document().freezeSource();
        next.uiState().preview.updateBinding(saved.binding(), saved.reopenMode(), next.generation());
    }

    private void refreshSavedHighlighter(DocumentId savedDocumentId) {
        refreshHighlighters(tab -> tab.documentSession().document().documentId().equals(savedDocumentId));
    }

    private void refreshMarkdownHighlighters() {
        refreshHighlighters(tab -> MarkdownOpenPolicy.isMarkdown(JvmDocumentStorage.pathOf(tab.state().getCurrentResource())));
    }

    private void refreshHighlighters(java.util.function.Predicate<TerminalEditorApp.EditorTab> selected) {
        for (int index = 0; index < tabs.size(); index++) {
            TerminalEditorApp.EditorTab tab = tabs.get(index);
            if (selected.test(tab)) {
                RenderPlanBuilder builder = tab.documentSession().newRenderPlanBuilder(
                    host.resolveHighlighter(JvmDocumentStorage.pathOf(tab.state().getCurrentResource()),
                        sourceSyntax()));
                sessionContext.update(tab.withRenderPlanBuilder(builder));
                if (sessionContext.activePresentation() instanceof TerminalEditorApp.EditorTab
                    && index == sessionContext.activeTabIndex()) renderPlanBuilder = builder;
            }
        }
    }

    /** Resolve retained dialog identities before painting or consuming their next input. */
    private void synchronizeModalTarget() {
        ViewId currentView = activeEditingView();
        if (currentView != null) setModal(modalState().bindTo(currentView));
        ViewId target = modalState().targetViewId();
        if (target == null) return;
        int index = sessionContext.indexOf(target);
        if (index < 0) {
            if (ui().overwriteApproval != null && target.equals(ui().overwriteApproval.request().session().viewId())) {
                ui().overwriteApproval.request().session().document().io().cancel(ui().overwriteApproval.inspection());
                ui().overwriteApproval = null;
            }
            setModal(UiModalState.none());
            notice().info("The document view for this dialog was closed");
        } else if (modalState().isCloseTabConfirm()
                || modalState().saveContinuation() == SaveContinuation.CLOSE_TAB) {
            setModal(modalState().withCloseTabIndex(index));
        }
    }

    private void startSave(SaveWorkflow.SaveRequest request) { startSave(currentRecipient(), request); }

    private void startSave(UiRecipient origin, SaveWorkflow.SaveRequest unsubmitted) {
        startSave(origin, unsubmitted, -1);
    }

    private void startSave(UiRecipient origin, SaveWorkflow.SaveRequest unsubmitted, long originalNoticeRevision) {
        if (disposed) { unsubmitted.session().document().io().stop(); return; }
        boolean global = origin.flow() == UiRecipient.Flow.WINDOW || isGlobalSave(unsubmitted);
        UiRecipient recipient = !global && origin.scope() == UiRecipient.Scope.PRESENTATION
            && Objects.equals(origin.viewId(), unsubmitted.session().viewId()) ? origin
            : saveRecipient(unsubmitted.session().viewId(), global ? UiRecipient.Flow.WINDOW : UiRecipient.Flow.LOCAL);
        if (!current(recipient)) { unsubmitted.session().document().io().cancel(unsubmitted.operation()); return; }
        SaveWorkflow.SaveRequest request = unsubmitted.submission() == null
            ? unsubmitted.submitted(recipient.state().saveAsInput.text(), recipient.state().filenameRevision) : unsubmitted;
        if (documentIo.busy()) {
            request.session().document().io().cancel(request.operation());
            notice(recipient).info("A file operation is already in progress; retry saving after it finishes");
            setModal(recipient, request.origin()); return;
        }
        pendingSave = request;
        var notice = notice(recipient);
        String progress = SaveProgressPresentation.message(request.operation().progress(), saveEscapeAvailable(recipient));
        if (originalNoticeRevision < 0) notice.info(progress);
        else UiEffects.information(notice, progress, originalNoticeRevision);
        SaveUiAttempt attempt = new SaveUiAttempt(request, recipient,
            originalNoticeRevision < 0 ? notice.revision() : originalNoticeRevision);
        pendingSaveUi = attempt;
        documentIo.submitSave(request::perform, completion -> {
            boolean currentAttempt = pendingSave == request && pendingSaveUi == attempt;
            if (currentAttempt) { pendingSave = null; pendingSaveUi = null; }
            // Domain receipts are processed even when their UI address retired.
            var transition = request.accept(completion.value(), completion.failure());
            closeOperationCompleted(completion.operation(), transition instanceof DocumentIoCoordinator.Saved
                ? ApplicationCloseWorkflow.Outcome.SAVED : transition instanceof DocumentIoCoordinator.Ignored
                ? ApplicationCloseWorkflow.Outcome.CANCELLED : ApplicationCloseWorkflow.Outcome.FAILED);
            if (transition instanceof DocumentIoCoordinator.Ignored) {
                if (currentAttempt && !disposed && current(recipient)
                    && completion.value() != null && completion.value().cancelled()) notice(recipient).info("Save cancelled");
                afterDocumentIo(); return;
            }
            if (disposed) {
                if (current(recipient) && transition instanceof DocumentIoCoordinator.Failed failed)
                    notice(recipient).error(SaveTargetPolicy.saveErrorMessage(failed.failure()));
                return;
            }
            if (currentAttempt && current(recipient)) {
                boolean performed = completion.succeeded() && completion.value().succeeded();
                completeSaveOnOwner(attempt, transition, performed);
            } else {
                if (transition instanceof DocumentIoCoordinator.Approval approval) request.session().document().io().cancel(approval.confirmation());
                if (transition instanceof DocumentIoCoordinator.Saved) {
                    refreshLivePreviewBinding(request); refreshSavedHighlighter(request.session().document().documentId());
                    if (currentAttempt && isGlobalSave(request) && closeState.phase() == ApplicationCloseWorkflow.Phase.SAVING) {
                        UiRecipient window = UiRecipient.window(globalUi, null);
                        applySaveResult(window, SaveWorkflow.quit(saveContext(window), ConfirmChoice.SAVE),
                            request.session().document().documentId());
                    }
                } else if (currentAttempt && isGlobalSave(request) && transition instanceof DocumentIoCoordinator.Failed failed) {
                    UiRecipient window = UiRecipient.window(globalUi, null);
                    applySaveResult(window, new SaveWorkflow.Result(true, UiModalState.quitConfirm(), -1, -1,
                        SaveFlowController.TextInputEffect.none(), SaveWorkflow.Notice.error(SaveTargetPolicy.saveErrorMessage(failed.failure())),
                        false, false), null);
                }
            }
            afterDocumentIo();
        });
    }

    private static boolean isGlobalSave(SaveWorkflow.SaveRequest request) {
        return request.purpose() == SaveWorkflow.SavePurpose.QUIT || request.origin().saveContinuation() == SaveContinuation.QUIT;
    }

    private void completeSaveOnOwner(SaveUiAttempt attempt, DocumentIoCoordinator.Transition transition, boolean performed) {
        SaveWorkflow.SaveRequest request = attempt.request();
        UiRecipient recipient = attempt.recipient();
        SaveWorkflow.Result result = SaveWorkflow.complete(saveContext(recipient), request, transition);
        boolean succeeded = performed && result.notice().kind() != SaveWorkflow.NoticeKind.ERROR;
        if (transition instanceof DocumentIoCoordinator.Saved saved) {
            PreviewTab previous = livePreviewOf(request.session().document().documentId());
            refreshLivePreviewBinding(request);
            if (previous != null && previous.presentationId().equals(recipient.presentationId()))
                recipient = recipient.afterAcceptedSaveRebind(livePreviewOf(request.session().document().documentId()), request.operation(), saved);
        }
        boolean standalone = request.purpose() == SaveWorkflow.SavePurpose.CURRENT
            || request.purpose() == SaveWorkflow.SavePurpose.SAVE_AS && request.origin().saveContinuation() == SaveContinuation.NONE;
        long filenameRevision = request.submission().filenameRevision();
        boolean sameFilename = recipient.state() == attempt.recipient().state()
            && recipient.state().filenameRevision == filenameRevision
            && recipient.state().saveAsInput.text().equals(request.submission().filename());
        if (request.inspecting() && succeeded && result.pendingSave() != null && standalone) {
            startSave(recipient, result.pendingSave(), attempt.noticeRevision());
        } else if (!request.inspecting() && succeeded && standalone) {
            if (result.refreshSavedHighlighter()) refreshSavedHighlighter(request.session().document().documentId());
            if (sameFilename) applyFilenameEffect(recipient, result.filename());
            applySaveNotice(recipient, result.notice(), attempt.noticeRevision());
        } else {
            // Continuations may address another document. Resolve that stable
            // target explicitly; the completed owner's edited fields remain its own.
            ViewId nextView = result.modalState().targetViewId();
            if (nextView == null && result.pendingSave() != null) nextView = result.pendingSave().session().viewId();
            if (nextView == null && result.activateTabIndex() >= 0 && result.activateTabIndex() < tabs.size())
                nextView = tabs.get(result.activateTabIndex()).documentSession().viewId();
            UiRecipient next = nextView == null || Objects.equals(nextView, recipient.viewId()) ? recipient
                : saveRecipient(nextView, recipient.flow());
            if (!current(next)) {
                if (result.pendingSave() != null) result.pendingSave().session().document().io().cancel(result.pendingSave().operation());
                return;
            }
            boolean sameOwner = next.state() == recipient.state();
            applySaveResultOnOwner(next, result, request.session().document().documentId(), sameOwner ? attempt.noticeRevision() : -1,
                sameOwner && !sameFilename);
        }
    }

    private void applySaveNotice(UiRecipient recipient, SaveWorkflow.Notice effect, long submittedRevision) {
        if (current(recipient)) UiEffects.notice(notice(recipient), effect, submittedRevision);
    }

    private boolean rejectSavingDocumentEdit() {
        if (formatWorkflow.affects(activeDocument())) {
            notice().info("A format operation is in progress; cancel it to resume editing");
            return true;
        }
        if (!activeDocument().document().io().busy()) {
            return false;
        }
        notice().info("Saving this document; editing will resume when the write finishes");
        return true;
    }

    /** Revoke publication rights before dropping a preparation worker's late reply. */
    private DocumentIoCoordinator.CancelResult cancelPendingSave(ViewId owner) {
        SaveWorkflow.SaveRequest request = pendingSave;
        if (request == null || owner != null && !owner.equals(request.session().viewId())) {
            return DocumentIoCoordinator.CancelResult.IDLE;
        }
        SaveUiAttempt attempt = pendingSaveUi;
        DocumentIoCoordinator.CancelResult result = request.session().document().io().cancel(request.operation());
        if (result == DocumentIoCoordinator.CancelResult.CANCELLED) {
            documentIo.cancelSave();
            pendingSave = null;
            if (pendingSaveUi == attempt) pendingSaveUi = null;
            if (closeState.phase() != ApplicationCloseWorkflow.Phase.CANCELLING)
                closeState = ApplicationCloseWorkflow.cancel(closeState);
            reportSaveStatus(attempt, () -> "Save cancelled", false);
        } else if (result == DocumentIoCoordinator.CancelResult.DRAINING_WRITE) {
            reportSaveStatus(attempt, () -> "Finishing save…", false);
        }
        return result;
    }

    private void updateSaveProgress() {
        SaveUiAttempt attempt = pendingSaveUi;
        if (attempt == null || pendingSave != attempt.request() || disposed) return;
        SaveWorkflow.SaveRequest request = attempt.request();
        if (isGlobalSave(request) && request.operation().publishing())
            applyCloseDecision(ApplicationCloseWorkflow.publishing(closeState, documentIo.pendingOperation(), System.nanoTime()));
        reportSaveStatus(attempt,
            () -> SaveProgressPresentation.message(request.operation().progress(), saveEscapeAvailable(attempt.recipient())), true);
    }

    private boolean saveEscapeAvailable(UiRecipient recipient) {
        return !recipient.state().searchMode && !recipient.state().openFileMode && modalState(recipient).isNone()
            && (background(recipient) || !topMenu.isOpen());
    }

    private void reportSaveStatus(SaveUiAttempt attempt, Supplier<String> text, boolean preserveAlerts) {
        if (attempt == null || !current(attempt.recipient())) return;
        var notice = notice(attempt.recipient());
        if (preserveAlerts && notice.requiresAttention() || UiEffects.preserveNotice(notice, attempt.noticeRevision())) return;
        String message = text.get();
        boolean changed = !message.equals(notice.message);
        if (changed) { notice.info(message); eventLoop.invalidate(); }
    }

    private void afterDocumentIo() { eventLoop.invalidate(); }

    private void cancelPendingOpen() {
        FilePickerOpenWorkflow.OpenRequest request = pendingOpen;
        if (request == null) return;
        request.cancel();
        documentIo.cancelRead();
        pendingOpen = null;
        for (TabUiState owner : knownUiStates) {
            if (owner.pendingOpen == request) owner.pendingOpen = null;
        }
        for (TerminalEditorApp.EditorTab tab : tabs) {
            if (tab.uiState().pendingOpen == request) tab.uiState().pendingOpen = null;
        }
    }

    /** Cancel preparation and bound publication waiting before final recovery capture. */
    void closeDocumentIo() {
        if (disposed) return;
        disposed = true;
        systemClipboard.close();
        cancelPreviewReopen();
        markdownPreview.close();
        host.closeFileAccess();
        snapshotSaves.close();
        cancelRecoveryDiscovery();
        formatWorkflow.cancel();
        for (TabUiState owner : knownUiStates) owner.close();
        for (WindowTab tab : sessionContext.presentations()) {
            if (tab instanceof PreviewTab preview) preview.close();
            else tab.uiState().close();
        }
        globalUi.close();
        cancelPendingOpen();
        SaveWorkflow.SaveRequest stoppingSave = pendingSave;
        DocumentIoCoordinator.CancelResult stoppedSave = stoppingSave == null
            ? DocumentIoCoordinator.CancelResult.IDLE : stoppingSave.session().document().io().stop();
        for (TerminalEditorApp.EditorTab tab : tabs) tab.documentSession().document().io().stop();
        if (stoppingSave != null && stoppedSave == DocumentIoCoordinator.CancelResult.CANCELLED) {
            documentIo.cancelSave();
            pendingSave = null;
            pendingSaveUi = null;
        }
        documentIo.close();
    }

    private void handlePreviewSaveCommand(PreviewTab preview, TuinkCommand command) {
        ConfirmChoice choice = confirmationChoice(command);
        if (modalState().isOpenUtf16Confirm()) {
            Path target = modalState().pendingOpenPath(); setModal(UiModalState.none());
            if (choice == ConfirmChoice.SAVE) applyOpenFileResult(FilePickerOpenWorkflow.openAsUtf16(target,
                host.toTabSessions(tabs), -1, () -> TuiDocumentSession.create(host.clipboard)));
            else ui().openFileMode = true;
            return;
        }
        if (preview.source() instanceof PreviewTab.ClosedSnapshot) {
            if (choice != null) snapshotSaves.confirm(preview, choice);
            else if (command != null) snapshotSaves.input(preview, command);
            return;
        }
        ViewId owner = ((PreviewTab.LiveSource) preview.source()).viewId();
        savePresentationOwners.put(owner, preview.presentationId());
        UiRecipient recipient = UiRecipient.presentation(preview, UiRecipient.Flow.LOCAL);
        UiModalState modal = modalState(recipient);
        if (modal.isSaveAs()) {
            if (command == TuinkCommand.UI_ESCAPE || command == TuinkCommand.APP_QUIT) cancelPendingSave(owner);
            applySaveResult(recipient, SaveWorkflow.saveAsInput(saveContext(recipient), command, modal,
                recipient.state().saveAsInput.text()), null);
        } else if (modal.isSaveOverwriteConfirm() && choice != null)
            applySaveResult(recipient, SaveWorkflow.overwrite(saveContext(recipient), modal, choice), null);
        else if (modal.isSaveUtf8Confirm() && choice != null)
            applySaveResult(recipient, SaveWorkflow.encodingChoice(modal, choice), null);
    }

    private boolean handleApplicationCommand(TuinkCommand command) {
        boolean readingBefore = uiSessionState.readingModeSetting;
        boolean mouseBefore = uiSessionState.mouseModeSetting;
        var result = ApplicationCommandHandler.decide(command, uiSessionState, activeSurface(), topMenu,
            terminal::setMouseTracking, () -> {
                if (sessionContext.activePresentation() instanceof TerminalEditorApp.EditorTab source) {
                    source.state().setTopWrapSegment(0); source.state().setLeftColumn(0); source.state().setLeftVisualColumn(0);
                }
            });
        if (!result.handled()) return false;
        if (readingBefore != uiSessionState.readingModeSetting) settingsSession.readingModeChanged();
        if (mouseBefore != uiSessionState.mouseModeSetting) settingsSession.mouseModeChanged();
        if (command == TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX) refreshMarkdownHighlighters();
        applyUiIntent(result.ui().intent());
        if (result.ui().statusMessage() != null) {
            switch (result.ui().statusLevel()) {
                case ERROR -> notice().error(result.ui().statusMessage());
                case WARNING -> notice().warning(result.ui().statusMessage());
                default -> notice().info(result.ui().statusMessage());
            }
        }
        switch (result.action()) {
            case NONE -> { }
            case OPEN -> {
                topMenu.close();
                ui().openFileMode = true; ui().pickerNotice = null; ui().lastPickerDirectoryFailure = null;
                ui().pickerOpenMode = dev.tuink.editor.storage.DocumentOpenMode.DETECT;
                openFileTree().setRoot(FilePickerOpenWorkflow.initialRoot(currentPresentationPath()));
                ui().openFileFocusState.focus(OpenFileInputRouter.Focus.LIST);
            }
            case OPEN_ENCODING -> handleFormatCommand(command);
            case RECOVER -> reopenRecovery();
            case THEME -> {
                var effect = EditorActionController.decide(command, running(), modalState(), false, false, false,
                    -1, currentPresentationPath(), false);
                host.applyEditorActionResult(effect, sessionContext, tabs, -1, notice(), saveAsSuggestions, renderer);
                settingsSession.themeChanged();
            }
            case QUIT -> requestWindowClose(currentRecipient());
            case SAVE, SAVE_AS -> executeSaveCommand(command);
            case COPY, SELECT_ALL, FIND -> executeCommonContentCommand(command);
        }
        reportTemporaryPreferencesChange();
        pointerCapture.reconcile(pointerFrame());
        eventLoop.invalidate();
        return true;
    }

    private void reportTemporaryPreferencesChange() {
        if (settingsSession.canSavePreferences()) return;
        if (settingsSession.temporaryPreferencesChanged(uiSessionState.settingsSnapshot(host.theme))
                && !notice().isError()) {
            notice().persistentWarning(settingsSession.saveUnavailableWarning());
        }
    }

    private void cancelCurrentOperation() {
        if (pendingPreviewReopen != null) { cancelPreviewReopen(); return; }
        if (pendingRecoveryDiscovery != null) {
            cancelRecoveryDiscovery(); notice().info("Recovery lookup cancelled; drafts were kept"); return;
        }
        if (sessionContext.activePresentation() instanceof PreviewTab preview
            && preview.source() instanceof PreviewTab.ClosedSnapshot) snapshotSaves.cancel(preview);
        else cancelPendingSave(activeEditingView());
    }

    private void executeSaveCommand(TuinkCommand command) {
        WindowTab presentation = sessionContext.activePresentation();
        if (presentation instanceof PreviewTab preview && preview.source() instanceof PreviewTab.ClosedSnapshot) {
            if (documentIo.busy()) { notice().info("A file operation is already in progress"); return; }
            if (command == TuinkCommand.DOCUMENT_SAVE_AS) snapshotSaves.openSaveAs(preview);
            else snapshotSaves.saveCurrent(preview);
            return;
        }
        ViewId owner = activeEditingView();
        var source = sessionContext.source(owner);
        if (source == null) return;
        boolean preview = presentation instanceof PreviewTab;
        if (source.documentSession().document().io().busy() || formatWorkflow.affects(source.documentSession())
            || documentIo.busy() && (preview || command == TuinkCommand.DOCUMENT_SAVE && currentPresentationPath() != null)) {
            notice().info("A file operation is already in progress"); return;
        }
        if (preview) savePresentationOwners.put(owner, presentation.presentationId());
        else savePresentationOwners.remove(owner);
        UiRecipient recipient = currentRecipient();
        Path path = currentPresentationPath();
        var action = EditorActionController.decide(command, running(), modalState(recipient),
            uiSessionState.confirmOnCloseSetting, host.hasDirtyTabs(tabs), false, viewIndex(recipient), path,
            path != null && SaveTargetPolicy.isReadOnlyFile(path));
        setModal(recipient, action.modalState().bindTo(owner));
        if (action.notificationError() != null) notice(recipient).error(action.notificationError());
        applyFilenameEffect(recipient, SaveWorkflow.initialFilename(saveContext(recipient), action.saveAsPreset()));
        if (action.requestSaveCurrent()) applySaveResult(recipient, SaveWorkflow.saveCurrent(saveContext(recipient)), null);
    }

    private void executeCommonContentCommand(TuinkCommand command) {
        if (ui().preview.active) {
            MarkdownPreviewState preview = ui().preview;
            switch (command) {
                case EDIT_COPY -> {
                    if (preview.ready()) {
                        String copied;
                        try {
                            copied = preview.copyText(uiSessionState.markdownVisualCopySetting);
                        } catch (dev.tuink.render.markdown.MarkdownVisualCopy.LimitException | OutOfMemoryError refused) {
                            notice().error("The selected Markdown is too large to copy; clipboard unchanged");
                            return;
                        }
                        host.clipboard.setText(copied);
                        notice().info("Copied Markdown preview text");
                    }
                }
                case EDIT_SELECT_ALL -> preview.selectAll();
                case SEARCH_OPEN -> {
                    preview.beginSearch(); ui().searchEditorFocused = false;
                    var scope = previewSearchScope();
                    ui().searchFocusManager.focus(scope, SearchFocus.FIND);
                }
                default -> throw new IllegalArgumentException("Not a common content operation");
            }
            return;
        }
        var result = dispatchInputAction(command);
        viewportController.onCommandResult(result.viewportPolicy());
        if (result.intent() == InputActionDispatcher.Intent.FIND) {
            ui().searchMode = true; ui().searchEditorFocused = false;
            String selectedQuery = SearchSelection.prefill(model, state);
            if (selectedQuery != null && !ui().searchQueryInput.set(selectedQuery)) {
                ui().searchMode = false; host.searchFocusManager().deactivate(TerminalEditorApp.SEARCH_SCOPE_ID);
                searchBarPlacement = null; notice().error("Not enough memory to open Find with the selected text"); return;
            }
            host.focusSearch(searchBarPlacement, SearchFocus.FIND);
        }
        if (result.statusMessage() != null) {
            switch (result.statusLevel()) {
                case ERROR -> notice().error(result.statusMessage());
                case WARNING -> notice().warning(result.statusMessage());
                default -> notice().info(result.statusMessage());
            }
        }
    }

    private TuinkCommand applySaveAsFieldAction(SaveAsBarView.Action action, String before) {
        if (action == SaveAsBarView.Action.SUBMIT_REJECTED) {
            if (notice().message == null) notice().warning("Enlarge terminal to show the filename before saving");
        } else if (action == SaveAsBarView.Action.INPUT_REJECTED) notice().error("Text is too large; nothing changed");
        else if (action == SaveAsBarView.Action.VALUE_CHANGED) {
            if (!before.equals(ui().saveAsInput.text())) { ui().filenameRevision++; notice().clear(); }
            if (sessionContext.active() != null) saveAsSuggestions.rememberDraft(sessionContext.active().tabSession(), ui().saveAsInput.text());
        } else if (action == SaveAsBarView.Action.SUBMITTED) return TuinkCommand.EDIT_INSERT_NEWLINE;
        else if (action == SaveAsBarView.Action.CANCELLED) return TuinkCommand.UI_ESCAPE;
        return null;
    }

    private void requestWindowClose(UiRecipient recipient) {
        if (disposed) return;
        applyCloseDecision(ApplicationCloseWorkflow.request(closeState, recipient, documentIo.pendingOperation()));
    }

    private boolean confirmDirtyDocuments() {
        return uiSessionState.confirmOnCloseSetting && host.hasDirtyTabs(tabs);
    }

    /** Ordered cancellation precedes any dirty decision or publication wait. */
    private void prepareWindowClose() {
        cancelPreviewReopen();
        cancelRecoveryDiscovery();
        formatWorkflow.cancel();
        cancelPendingOpen();
        documentIo.cancelRead();
        snapshotSaves.cancelPendingForClose();
        for (WindowTab tab : sessionContext.presentations()) {
            if (tab instanceof PreviewTab preview && preview.source() instanceof PreviewTab.ClosedSnapshot)
                snapshotSaves.cancel(preview);
        }
        cancelPendingSave(null);
        var operation = documentIo.pendingOperation();
        var policy = operation != null && operation == snapshotSaves.pendingOperation()
            ? ApplicationCloseWorkflow.Policy.SNAPSHOT_RETAIN : ApplicationCloseWorkflow.Policy.LIVE_RECOVERY;
        var continuation = pendingSave != null && isGlobalSave(pendingSave)
            ? ApplicationCloseWorkflow.Continuation.SAVE_ALL : ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS;
        applyCloseDecision(ApplicationCloseWorkflow.prepared(closeState, operation, policy, continuation,
            System.nanoTime(), confirmDirtyDocuments()));
    }

    private void applyCloseDecision(ApplicationCloseWorkflow.Decision<UiRecipient, DocumentIoController.Operation> decision) {
        if (disposed || decision.effect() == ApplicationCloseWorkflow.Effect.NONE && decision.state() == closeState) return;
        if (decision.effect() == ApplicationCloseWorkflow.Effect.EXPIRE_LIVE_SAVE) {
            // expireSave may collect a just-ready receipt and run a replacement
            // operation. Keep the old WAITING state until that check resolves.
            try { documentIo.expireSave(decision.state().waiting().operation()); }
            catch (IllegalStateException unknown) { closeState = decision.state(); throw unknown; }
            return;
        }
        closeState = decision.state();
        switch (decision.effect()) {
            case NONE, EXIT -> { }
            case CANCEL_PREPARATION -> prepareWindowClose();
            case CONFIRM -> setModal(UiRecipient.window(globalUi, null), UiModalState.quitConfirm());
            case SAVE_ALL -> applySaveResult(SaveWorkflow.quit(saveContext(), ConfirmChoice.SAVE));
            case WAIT -> {
                if (current(closeState.recipient())) notice(closeState.recipient()).info(
                    closeState.waiting().policy() == ApplicationCloseWorkflow.Policy.SNAPSHOT_RETAIN
                        ? "Finishing snapshot save before quitting…" : "Finishing save…");
            }
            case RETAIN_SNAPSHOT -> {
                if (current(closeState.recipient())) notice(closeState.recipient()).warning(
                    "Snapshot save outcome is still pending; the preview stays open. Verify the file before retrying quit.");
            }
            case EXPIRE_LIVE_SAVE -> throw new IllegalStateException("Live expiry must check its exact worker first");
        }
        eventLoop.invalidate();
    }

    void checkWindowCloseDeadline(long now) {
        var decision = ApplicationCloseWorkflow.tick(closeState, documentIo.pendingOperation(), now);
        if ((decision.effect() == ApplicationCloseWorkflow.Effect.RETAIN_SNAPSHOT
            || decision.effect() == ApplicationCloseWorkflow.Effect.EXPIRE_LIVE_SAVE)
            && documentIo.collect(decision.state().waiting().operation())) return;
        applyCloseDecision(decision);
    }

    private void closeOperationCompleted(DocumentIoController.Operation operation, ApplicationCloseWorkflow.Outcome outcome) {
        if (!disposed) applyCloseDecision(ApplicationCloseWorkflow.completed(closeState, operation, outcome, confirmDirtyDocuments()));
    }

    private void snapshotSaveCompleted(SnapshotSaveController.Completion completion) {
        closeOperationCompleted(completion.operation(), completion.transition() instanceof DocumentIoCoordinator.Saved
            ? ApplicationCloseWorkflow.Outcome.SAVED : ApplicationCloseWorkflow.Outcome.FAILED);
        eventLoop.invalidate();
    }

    private boolean handlePreviewContentCommand(TuinkCommand command) {
        MarkdownPreviewState preview = ui().preview;
        boolean extend = command.name().startsWith("CURSOR_SELECT_");
        switch (command) {
            case CURSOR_UP, CURSOR_SELECT_UP -> preview.moveSelection(PreviewSelection.Move.UP, extend);
            case CURSOR_DOWN, CURSOR_SELECT_DOWN -> preview.moveSelection(PreviewSelection.Move.DOWN, extend);
            case CURSOR_PAGE_UP -> preview.moveSelection(PreviewSelection.Move.PAGE_UP, false);
            case CURSOR_PAGE_DOWN -> preview.moveSelection(PreviewSelection.Move.PAGE_DOWN, false);
            case CURSOR_LEFT, CURSOR_SELECT_LEFT, CURSOR_WORD_LEFT -> preview.moveSelection(PreviewSelection.Move.LEFT, extend);
            case CURSOR_RIGHT, CURSOR_SELECT_RIGHT, CURSOR_WORD_RIGHT -> preview.moveSelection(PreviewSelection.Move.RIGHT, extend);
            case CURSOR_HOME, CURSOR_SELECT_HOME -> preview.moveSelection(PreviewSelection.Move.HOME, extend);
            case CURSOR_END, CURSOR_SELECT_END -> preview.moveSelection(PreviewSelection.Move.END, extend);
            case CURSOR_DOCUMENT_START, CURSOR_SELECT_DOCUMENT_START -> preview.moveSelection(PreviewSelection.Move.START, extend);
            case CURSOR_DOCUMENT_END, CURSOR_SELECT_DOCUMENT_END -> preview.moveSelection(PreviewSelection.Move.DOCUMENT_END, extend);
            case SEARCH_NEXT -> preview.nextMatch(false);
            case SEARCH_PREVIOUS -> preview.nextMatch(true);
            case SEARCH_CLOSE -> preview.searchVisible = false;
            case VIEW_PREVIEW_NEXT_HEADING, VIEW_PREVIEW_PREVIOUS_HEADING -> {
                if (preview.ready()) {
                    var headings = dev.tuink.markdown.MarkdownNavigation.headings(preview.result.document());
                    boolean previous = command == TuinkCommand.VIEW_PREVIEW_PREVIOUS_HEADING;
                    dev.tuink.markdown.MarkdownNavigation.Heading target = null;
                    for (var heading : headings) {
                        if (previous && heading.projectionOffset() < preview.cursorOffset()) target = heading;
                        if (!previous && heading.projectionOffset() > preview.cursorOffset()) { target = heading; break; }
                    }
                    if (target != null) preview.selectAt(preview.result.rowForProjectionOffset(target.projectionOffset()), 0, false);
                }
            }
            case VIEW_PREVIEW_FOLLOW_LINK -> {
                if (preview.ready()) {
                    var link = dev.tuink.markdown.MarkdownNavigation.linkAt(preview.result.document(), preview.cursorOffset());
                    var heading = link == null ? null : dev.tuink.markdown.MarkdownNavigation.resolveAnchor(preview.result.document(), link.destination());
                    if (heading != null) preview.selectAt(preview.result.rowForProjectionOffset(heading.projectionOffset()), 0, false);
                    else notice().info("No local heading target at this position");
                }
            }
            case VIEW_PREVIEW_COPY_SOURCE -> {
                if (preview.ready()) {
                    var target = MarkdownPreviewActions.source(preview.result.document(), preview.sourceForFreeze().text(), preview.cursorOffset());
                    if (target != null) { host.clipboard.setText(target.text()); notice().info("Copied " + target.kind() + " source"); }
                }
            }
            case VIEW_PREVIEW_COPY_TABLE_TSV -> {
                if (preview.ready()) {
                    String tsv = MarkdownPreviewActions.table(preview.result.document(), preview.cursorOffset());
                    if (tsv != null) { host.clipboard.setText(tsv); notice().info("Copied table as TSV"); }
                    else notice().info("Select a table first");
                }
            }
            case VIEW_PREVIEW_REVEAL_SOURCE -> {
                if (preview.ready()) {
                    var source = MarkdownPreviewActions.sourceRange(preview.result.document(), preview.cursorOffset());
                    if (source != null) {
                        openPreviewSource((PreviewTab) sessionContext.activePresentation(), source.start());
                    }
                }
            }
            case DOCUMENT_FORMAT, DOCUMENT_FORMAT_APPLY -> notice().warning("Return to source to change document format");
            default -> {
                if (!command.editsDocumentText()) return false;
                notice().warning("Markdown preview: return to source to edit");
            }
        }
        eventLoop.invalidate();
        return true;
    }

    /** Contextual intent after validation of the original command, before its capability gate. */
    private TuinkCommand previewSearchIntent(TuinkCommand command) {
        if (ui().preview.active && ui().preview.searchVisible && modalState().isNone()
            && !ui().openFileMode && !topMenu.isOpen()
            && (command == TuinkCommand.EDIT_INSERT_NEWLINE || command == TuinkCommand.SEARCH_OPEN
                && (!ui().preview.searchQuery.isEmpty() || previewQueryFocused())))
            return TuinkCommand.SEARCH_NEXT;
        return command;
    }

    private boolean routePreviewSearchOwner(WindowInput input) {
        MarkdownPreviewState preview = ui().preview;
        if (!preview.searchVisible || !modalState().isNone() || ui().openFileMode || topMenu.isOpen()) return false;
        if (input instanceof WindowInput.Command resolved && resolved.route() instanceof CommandRouter.RoutedAction action) {
            TuinkCommand intent = previewSearchIntent(action.action());
            if (intent == TuinkCommand.UI_ESCAPE || intent == TuinkCommand.SEARCH_CLOSE) {
                cancelPointerCapture(PointerCaptureCancellation.SURFACE_CHANGED);
                ui().searchFocusManager.deactivate(PREVIEW_SEARCH_SCOPE);
                preview.searchVisible = false; preview.searchField.cancelMouseSelection(); return true;
            }
            if (intent == TuinkCommand.SEARCH_NEXT || intent == TuinkCommand.SEARCH_PREVIOUS) {
                preview.nextMatch(intent == TuinkCommand.SEARCH_PREVIOUS); return true;
            }
            if (ui().searchEditorFocused) return false;
        }
        if (input instanceof WindowInput.Gesture gesture && gesture.motion() instanceof WindowInput.Pointer pointer) {
            if (pointer.event().kind() == PointerEvent.Kind.PRESS && pointer.event().button() == PointerEvent.Button.LEFT) {
                ui().searchEditorFocused = false;
                ui().searchFocusManager.focus(previewSearchScope(), SearchFocus.FIND);
            }
        } else if (ui().searchEditorFocused) return false;
        var scope = previewSearchScope();
        var context = ui().searchFocusManager.peekContext(scope, SearchFocus.FIND).orElseThrow();
        var result = FieldInputDispatcher.input(input, preview.searchField, host.clipboard,
            scope, SearchFocus.FIND, context, pointerCapture, this, MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16);
        if (result.output().orElse(null) == TextField.Action.INPUT_REJECTED)
            notice().warning("Preview search input was rejected");
        if (result.consumed()) preview.setSearchQuery(preview.searchField.text());
        return result.consumed() || !(input instanceof WindowInput.Command);
    }

    private InputActionDispatcher.DispatchResult dispatchInputAction(TuinkCommand command) {
        if (ui().preview.active && command.editsDocumentText())
            return InputActionDispatcher.DispatchResult.handled("Markdown preview: return to source to edit");
        boolean readingBefore = uiSessionState.readingModeSetting;
        boolean mouseBefore = uiSessionState.mouseModeSetting;
        TerminalEditorApp.EditorTab target = tabs.get(activeEditingIndex());
        TabSession view = target.tabSession();
        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatchDocument(command, uiSessionState,
            new InputActionDispatcher.DispatchContext(view.engine(), contentRows, contentCols, target.navigation(), target.documentSession()));
        if (uiSessionState.readingModeSetting != readingBefore) {
            settingsSession.readingModeChanged();
        }
        if (uiSessionState.mouseModeSetting != mouseBefore) {
            settingsSession.mouseModeChanged();
        }
        return result;
    }
}
