package dev.tuink.tui;

import dev.tuink.editor.storage.FrozenDocumentSource;
import java.util.Objects;

/** Owner-thread preview content preparation; surrounding window preparation belongs to WindowChrome. */
final class PreviewPresentationView {
    record Input(WindowChrome.Input window, PreviewTab tab, FrozenDocumentSource source,
        MarkdownPreviewService service, boolean fullRedraw) {
        Input {
            Objects.requireNonNull(window); Objects.requireNonNull(tab); Objects.requireNonNull(source);
            Objects.requireNonNull(service);
        }
    }

    private PreviewPresentationView() {}

    static PreviewFrameComposition prepare(Input input) {
        var window = input.window();
        var prepared = WindowChrome.prepare(window, null);
        var bounds = prepared.contentBounds(true);
        var preview = input.tab().uiState().preview;
        if (!preview.active) preview.activate(input.source(), input.tab().generation(), bounds, input.service());
        else preview.refresh(input.source(), input.tab().generation(), bounds, input.service());
        var placement = preview.placement();
        var chrome = prepared.finish(new WindowChrome.Content(window.surface(), bounds, status(input, placement), null));
        var layers = chrome.layers();
        // A retained query is content behind a modal; only its current input owner is painted focused.
        if ((layers.saveAs() != null || layers.confirm() != null || layers.openFile().visible()
            || layers.help() != null || layers.format() != null) && !layers.contentFocused()
            && placement.searchInput() != null) placement = unfocusedQuery(placement);
        return new PreviewFrameComposition(window.rows(), window.cols(), prepared.contentTopRow(), placement,
            chrome.surface(), layers, input.fullRedraw());
    }

    private static String status(Input input, MarkdownPreviewState.Placement preview) {
        var metadata = input.source().metadata();
        return MarkdownPreviewStatus.text(preview, input.tab().source() instanceof PreviewTab.ClosedSnapshot,
            metadata.charset().name(), metadata.bomKind(), input.window().context().activePresentationIndex(),
            input.window().context().presentations().size(), input.window().cols());
    }

    private static MarkdownPreviewState.Placement unfocusedQuery(MarkdownPreviewState.Placement p) {
        return new MarkdownPreviewState.Placement(p.bounds(), p.result(), p.top(), p.left(), p.loading(), p.error(),
            p.selection(), p.searchMatches(), p.activeMatch(), p.searchQuery(), p.searchEditing(), p.caret(), null, p.geometry());
    }
}
