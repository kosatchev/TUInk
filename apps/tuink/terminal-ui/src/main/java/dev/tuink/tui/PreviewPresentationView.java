package dev.tuink.tui;

import dev.tuink.editor.storage.FrozenDocumentSource;
import java.util.Objects;
import dev.tuink.tui.ui.SearchBarView;

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
        var preview = input.tab().uiState().preview;
        var options = window.tab().searchUi;
        var searchBar = preview.searchVisible ? SearchBarView.buildReadOnly(preview.searchField,
            WindowChrome.documentPane(window).width(), options.caseSensitive(), options.matchMode(), options.wholeWord(), window.tab().searchModes) : null;
        var prepared = WindowChrome.prepare(window, searchBar);
        var surface = window.surface();
        var bounds = prepared.contentBounds(true);
        if (!preview.active) preview.activate(input.source(), input.tab().generation(), bounds, input.service());
        else preview.refresh(input.source(), input.tab().generation(), bounds, input.service());
        preview.setSearchOptions(SearchController.toSearchOptions(window.tab().searchUi));
        var placement = preview.placement(MarkdownPreviewState.Placement.Focus.NONE);
        var chrome = prepared.finish(new WindowChrome.Content(surface, bounds,
            status(input, placement, prepared.documentPane().width()), null));
        preview.prepareSearch(chrome.search());
        placement = preview.placement(MarkdownPreviewState.Placement.Focus.NONE);
        var layers = chrome.layers();
        boolean overlay = layers.saveAs() != null || layers.confirm() != null || layers.openFile().visible()
            || layers.help() != null || layers.format() != null || layers.explorerDialog() != null;
        var focus = overlay
            ? layers.contentFocused() ? MarkdownPreviewState.Placement.Focus.CONTENT : MarkdownPreviewState.Placement.Focus.NONE
            : surface.focus() == ActiveSurface.Focus.CONTENT ? MarkdownPreviewState.Placement.Focus.CONTENT
                : surface.focus() == ActiveSurface.Focus.EDITABLE_TEXT && placement.searchVisible()
                    ? MarkdownPreviewState.Placement.Focus.QUERY : MarkdownPreviewState.Placement.Focus.NONE;
        placement = placement.withFocus(focus);
        return new PreviewFrameComposition(window.rows(), window.cols(), prepared.contentTopRow(), placement,
            chrome.surface(), layers, chrome.search(), input.fullRedraw());
    }

    private static String status(Input input, MarkdownPreviewState.Placement preview, int columns) {
        var metadata = input.source().metadata();
        return MarkdownPreviewStatus.text(preview, input.tab().source() instanceof PreviewTab.ClosedSnapshot,
            metadata.charset().name(), metadata.bomKind(), input.window().context().activePresentationIndex(),
            input.window().context().presentations().size(), columns);
    }

}
