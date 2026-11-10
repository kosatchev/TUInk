package dev.tuink.tui;

import dev.tuiframe.explorer.Explorer;

import static dev.tuink.tui.file.FileTreeTestSupport.awaitLoaded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuink.tui.render.WindowTextPainter;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuink.tui.ui.HelpPages;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameCompositionTest {
    @TempDir
    Path root;

    @Test
    void renderEntryConsumesOneCompositionAndNoLongForwarderRemains() {
        Class<?> controller = Arrays.stream(TerminalEditorApp.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("RenderFrameController"))
            .findFirst()
            .orElseThrow();
        List<Method> entries = Arrays.stream(controller.getDeclaredMethods())
            .filter(method -> method.getName().equals("renderFrameIfNeeded"))
            .toList();

        assertEquals(1, entries.size());
        Method entry = entries.get(0);
        assertEquals(1, entry.getParameterCount());
        assertEquals(FrameComposition.class, entry.getParameterTypes()[0]);
        assertTrue(
            Arrays.stream(TerminalEditorApp.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("doRenderFrameIfNeeded"))
        );
        List<Method> coreMethods = Arrays.stream(TerminalEditorApp.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("renderFrame"))
            .toList();
        assertEquals(1, coreMethods.size());
        Method core = coreMethods.get(0);
        assertEquals(2, core.getParameterCount());
        assertEquals(FrameComposition.class, core.getParameterTypes()[0]);
        assertTrue(
            Arrays.stream(core.getParameterTypes())
                .anyMatch(type -> type.equals(FrameComposition.class))
        );
    }

    @Test
    void snapshotKeepsOneAuthoritativeGeometryAndBorrowedDocumentIdentity() {
        FrameComposition composition = composition(InlinePromptBar.hidden(), 0);

        assertSame(composition.document().model(), composition.document().planBuilder().textLayout().model());
        assertEquals(composition.document().layout().rows(), 8);
        assertEquals(composition.document().layout().cols(), 40);
        assertEquals(composition.document().layout().contentTopRow(), 1);
        assertEquals(composition.surface().topBar().text(), " File ");
        assertEquals(composition.surface().topBar().menuPrefixWidth(), 6);
        assertTrue(composition.allowsScrollHints());
        assertFalse(
            new FrameComposition(
                composition.document(),
                composition.surface(),
                composition.search(),
                composition.layers(),
                composition.wrapLines(),
                composition.wrapWords(),
                true
            ).allowsScrollHints()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> composition(InlinePromptBar.of("prompt", Style.DEFAULT, 1), 0)
        );
    }

    @Test
    void snapshotContainsNoCollectionControllerOrSessionStateComponents() {
        assertTrue(FrameComposition.class.isRecord());
        for (Class<?> type : FrameComposition.class.getDeclaredClasses()) {
            if (!type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                Class<?> componentType = component.getType();
                assertFalse(Collection.class.isAssignableFrom(componentType));
                assertFalse(componentType.equals(Explorer.class));
                assertFalse(componentType.equals(UiSessionState.class));
            }
        }
    }

    @Test
    void exclusivePromptAndOpenFileSlicesRejectMixedFrames() {
        FrameComposition base = composition(InlinePromptBar.hidden(), 0);
        FrameComposition.OpenFile hidden = base.layers().openFile();

        FrameComposition.Layers conflicting = new FrameComposition.Layers(
            null,
            null,
            true,
            null,
            null,
            hidden,
            new TerminalEditorApp().helpOverlayPlacement(8, 40, HelpPages.KEYMAP),
            null,
            false,
            false,
            new Rect(0, 0, 0, 0),
            null,
            null, null
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new FrameComposition(
                base.document(),
                base.surface(),
                base.search(),
                conflicting,
                false,
                false,
                false
            )
        );
    }

    @Test
    void sourceFramesRejectBlockingGeometryFromAnotherWindowSnapshot() {
        var base = composition(InlinePromptBar.hidden(), 0);
        var layers = new FrameComposition.Layers(null, null, false, null, null,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, 1, 40, 6), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new FrameComposition(base.document(), base.surface(),
            base.search(), layers, false, false, false));
    }

    @Test
    void openTreeScrollHintRequiresAnOtherwiseIdenticalSurface() {
        FrameComposition.OpenTreeSurfaceKey surface = treeSurface(7L, 0, new Rect(2, 3, 20, 5));
        FrameComposition.OpenTreeDiffKey before = new FrameComposition.OpenTreeDiffKey(surface, 4);

        assertTrue(new FrameComposition.OpenTreeDiffKey(surface, 5).isVerticalScrollFrom(before));
        assertFalse(new FrameComposition.OpenTreeDiffKey(surface, 4).isVerticalScrollFrom(before));
        assertFalse(
            new FrameComposition.OpenTreeDiffKey(
                treeSurface(8L, 0, new Rect(2, 3, 20, 5)),
                5
            ).isVerticalScrollFrom(before)
        );
        assertFalse(
            new FrameComposition.OpenTreeDiffKey(
                treeSurface(7L, 1, new Rect(2, 3, 20, 5)),
                5
            ).isVerticalScrollFrom(before)
        );
        assertFalse(
            new FrameComposition.OpenTreeDiffKey(
                treeSurface(7L, 0, new Rect(2, 3, 19, 5)),
                5
            ).isVerticalScrollFrom(before)
        );
    }

    @Test
    void openTreeDiffKeyProjectsExactPlacementRevisionGeometryAndHorizontalState() throws IOException {
        Files.createFile(root.resolve(".hidden.txt"));
        for (int index = 0; index < 9; index++) {
            Files.createFile(root.resolve("very-long-tree-row-" + index + "-中.txt"));
        }
        OpenDialogLayout layout = new OpenDialogLayout(1, 1, 24, 8, 3, 5);
        Rect bounds = new Rect(2, 3, 16, 4);
        Rect clip = new Rect(0, 0, 40, 12);
        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeView view = FileTreeView.fromSnapshot(snapshot, false, false);
            FileTreeView.State initialState = new FileTreeView.State(
                null,
                new dev.tuiframe.widget.TreeView.State(0, 0, 0),
                snapshot.revision(),
                null,
                0L
            );
            FileTreeView.Placement initial = view.place(bounds, initialState, clip, true);
            FileTreeView.State verticalState = new FileTreeView.State(
                initial.state().selectedId(),
                new dev.tuiframe.widget.TreeView.State(0, 1, 0),
                snapshot.revision(),
                null,
                0L
            );
            FileTreeView.Placement vertical = view.place(bounds, verticalState, clip, true);
            FrameComposition.OpenTreeDiffKey initialKey = openFile(layout, initial).diffKey();
            FrameComposition.OpenTreeDiffKey verticalKey = openFile(layout, vertical).diffKey();

            assertTrue(verticalKey.isVerticalScrollFrom(initialKey));

            FileTreeView.State horizontalState = new FileTreeView.State(
                vertical.state().selectedId(),
                new dev.tuiframe.widget.TreeView.State(0, 1, 6),
                snapshot.revision(),
                null,
                0L
            );
            FileTreeView.Placement horizontal = view.place(bounds, horizontalState, clip, true);
            assertTrue(horizontal.state().widgetState().horizontalOffset() > 0);
            assertFalse(openFile(layout, horizontal).diffKey().isVerticalScrollFrom(initialKey));

            FileTreeView.Placement resized = view.place(
                new Rect(2, 3, 17, 4),
                verticalState,
                clip,
                true
            );
            assertFalse(openFile(layout, resized).diffKey().isVerticalScrollFrom(initialKey));

            model.setShowHiddenFiles(true);
            FileTreeModel.Snapshot revisedSnapshot = awaitLoaded(model);
            FileTreeView.Placement revised = FileTreeView
                .fromSnapshot(revisedSnapshot, false, false)
                .place(bounds, vertical.state(), clip, true);
            assertFalse(openFile(layout, revised).diffKey().isVerticalScrollFrom(initialKey));
        }
    }

    private static FrameComposition composition(InlinePromptBar prompt, int overlayRows) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, "frame");
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        TerminalEditorTextLayout textLayout = new TerminalEditorTextLayout(model);
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(text -> java.util.List.of(), textLayout);
        EditorLayout layout = EditorLayout.resolve(
            model,
            textLayout,
            state,
            8,
            40,
            1,
            overlayRows,
            false,
            false,
            4
        );
        return new FrameComposition(
            new FrameComposition.Document(builder, model, state, layout),
            new FrameComposition.Surface(
                new TopMenuView.TopBar(" File ", 6),
                "status",
                prompt,
                NotificationBar.hidden(),
                WindowTextPainter.Layout.fullWindow(8, 40, prompt.height(), 0), null, null
            ),
            new FrameComposition.Search(false, "", SearchOptions.DEFAULT, false, null, false),
            new FrameComposition.Layers(
                null,
                null,
                false,
                null,
                null,
                FrameComposition.OpenFile.hidden(),
                null,
                null,
                false,
                false,
                new Rect(0, 0, 0, 0),
                null,
                null, null
            ),
            false,
            false,
            false
        );
    }

    private static FrameComposition.OpenTreeSurfaceKey treeSurface(
        long revision,
        int horizontalOffset,
        Rect content
    ) {
        OpenDialogLayout layout = new OpenDialogLayout(1, 1, 24, 8, 3, 5);
        return new FrameComposition.OpenTreeSurfaceKey(
            "/tmp/tree",
            false,
            revision,
            layout,
            new Rect(2, 3, 20, 5),
            content,
            content,
            null,
            new Rect(21, 3, 1, 5),
            null,
            50,
            30,
            horizontalOffset,
            8,
            17
        );
    }

    private FrameComposition.OpenFile openFile(
        OpenDialogLayout layout,
        FileTreeView.Placement placement
    ) {
        return new FrameComposition.OpenFile(
            root.toString(),
            OpenFileDialogView.place(TerminalEditorApp.OPEN_FILE_SCOPE_ID, layout, placement, false,
                new Rect(0, 0, 40, 12)),
            OpenFileInputRouter.Focus.LIST
        );
    }
}
