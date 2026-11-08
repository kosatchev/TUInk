package dev.tuink.tui;

import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuiframe.widget.PathBar;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Presentation origin belongs to a document and survives Source/Preview attachment changes. */
final class DocumentPaths {
    private final Set<DocumentId> explorerOpened = new HashSet<>();

    void opened(DocumentId document, boolean fromExplorer) {
        if (fromExplorer) explorerOpened.add(document); else explorerOpened.remove(document);
    }
    void inherit(DocumentId original, DocumentId reopened) { opened(reopened, explorerOpened.contains(original)); }
    void retain(Set<DocumentId> documents) { explorerOpened.retainAll(documents); }

    PathBar component(TerminalEditorApp host, TerminalEditorApp.SessionContext context, Path root) {
        WindowTab presentation = context.activePresentation();
        Path path;
        if (presentation instanceof TerminalEditorApp.EditorTab editor)
            path = JvmDocumentStorage.pathOf(editor.documentSession().document().binding().resource());
        else if (((PreviewTab) presentation).source() instanceof PreviewTab.ClosedSnapshot frozen)
            path = JvmDocumentStorage.pathOf(frozen.source().binding().resource());
        else {
            var live = (PreviewTab.LiveSource) ((PreviewTab) presentation).source();
            path = JvmDocumentStorage.pathOf(context.source(live.viewId()).documentSession().document().binding().resource());
        }
        boolean draft = path == null && host.deletedDraftCaption(presentation) != null;
        if (draft) path = host.deletedDraftCaption(presentation);
        boolean relative = path != null && root != null && explorerOpened.contains(TerminalEditorApp.documentId(presentation))
            && path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
        return component(path, relative ? root : null, draft);
    }

    static PathBar component(Path path, Path relativeRoot, boolean draft) {
        if (path == null) return new PathBar("", List.of("Untitled"), "/");
        Path absolute = path.toAbsolutePath().normalize();
        String suffix = draft ? " (Draft)" : "";
        String separator = path.getFileSystem().getSeparator();
        if (relativeRoot != null) {
            Path relative = relativeRoot.toAbsolutePath().normalize().relativize(absolute);
            var labels = new ArrayList<String>();
            Path current = relativeRoot;
            for (Path segment : relative) { current = current.resolve(segment); labels.add(PathLabels.name(current)); }
            return new PathBar("", labels, separator, suffix, PathPresentation.LABEL_POLICY);
        }
        String full = PathLabels.full(absolute);
        if (full.startsWith("file:")) {
            String raw = absolute.toUri().getRawPath();
            int start = full.length() - raw.length();
            return new PathBar(full.substring(0, start + 1), List.of(raw.substring(1).split("/")), "/",
                suffix, PathPresentation.LABEL_POLICY);
        }
        var labels = new ArrayList<String>();
        absolute.forEach(segment -> labels.add(segment.toString()));
        return new PathBar(absolute.getRoot().toString(), labels, separator, suffix, PathPresentation.LABEL_POLICY);
    }
}
