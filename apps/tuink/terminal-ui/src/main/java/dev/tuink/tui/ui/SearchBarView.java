package dev.tuink.tui.ui;

import dev.tuink.core.SearchOptions;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PlacedCursorSource;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.SearchNavigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Application-owned two-row search composition built from reusable widgets. */
public final class SearchBarView {
    public static final String FIND_LABEL = "Find: ";
    public static final String REPLACE_LABEL = "Replace: ";
    private static final int RIGHT_MARGIN = 1;

    private final TextField queryField;
    private final TextField replacementField;
    private final String row1;
    private final String row2;
    private final int queryStart;
    private final int queryWidth;
    private final int replacementStart;
    private final int replacementWidth;
    private final List<ButtonSpec> buttons;

    private SearchBarView(
        TextField queryField,
        TextField replacementField,
        int columns,
        boolean caseSensitive,
        SearchOptions.MatchMode matchMode,
        boolean allTabs
    ) {
        this.queryField = Objects.requireNonNull(queryField, "queryField");
        this.replacementField = Objects.requireNonNull(replacementField, "replacementField");
        int safeColumns = Math.max(0, columns);
        SearchOptions.MatchMode safeMatchMode = matchMode == null
            ? SearchOptions.MatchMode.SUBSTRING
            : matchMode;

        Button caseToggle = new Button("Аа:" + (caseSensitive ? "x" : " "));
        Button wordToggle = new Button(
            "word:" + (safeMatchMode == SearchOptions.MatchMode.WHOLE_WORD ? "x" : " ")
        );
        Button wildcardToggle = new Button(
            "*:" + (safeMatchMode == SearchOptions.MatchMode.WILDCARD ? "x" : " ")
        );
        Button allToggle = new Button("All tabs:" + (allTabs ? "x" : " "));
        Button next = new Button("Next");
        Button previous = new Button("Prev");
        Button replace = new Button("Replace");
        Button replaceAll = new Button("Replace all");

        List<ButtonTarget> optionTargets = List.of(
            new ButtonTarget(SearchFocus.CASE, caseToggle, Action.TOGGLE_CASE),
            new ButtonTarget(SearchFocus.WORD, wordToggle, Action.TOGGLE_WORD),
            new ButtonTarget(SearchFocus.WILDCARD, wildcardToggle, Action.TOGGLE_WILDCARD),
            new ButtonTarget(SearchFocus.ALL, allToggle, Action.TOGGLE_ALL_TABS)
        );
        List<ButtonTarget> actionTargets = List.of(
            new ButtonTarget(SearchFocus.NEXT_BUTTON, next, Action.FIND_NEXT),
            new ButtonTarget(SearchFocus.PREV_BUTTON, previous, Action.FIND_PREVIOUS),
            new ButtonTarget(SearchFocus.REPLACE_BUTTON, replace, Action.REPLACE),
            new ButtonTarget(SearchFocus.ALL_BUTTON, replaceAll, Action.REPLACE_ALL)
        );

        int optionsStart = clusterStart(optionTargets, safeColumns);
        int actionsStart = clusterStart(actionTargets, safeColumns);
        int optionLeftWidth = leftWidth(optionTargets, safeColumns);
        int actionLeftWidth = leftWidth(actionTargets, safeColumns);
        int findLabelWidth = CellText.width(FIND_LABEL);
        int replaceLabelWidth = CellText.width(REPLACE_LABEL);
        boolean optionsVisible = optionLeftWidth - findLabelWidth >= 1;
        boolean actionsVisible = actionLeftWidth - replaceLabelWidth >= 1;
        if (!optionsVisible) {
            optionLeftWidth = safeColumns;
        }
        if (!actionsVisible) {
            actionLeftWidth = safeColumns;
        }
        this.queryStart = Math.min(findLabelWidth, optionLeftWidth);
        this.queryWidth = Math.max(0, optionLeftWidth - findLabelWidth);
        this.replacementStart = Math.min(replaceLabelWidth, actionLeftWidth);
        this.replacementWidth = Math.max(0, actionLeftWidth - replaceLabelWidth);
        this.row1 = CellText.fit(FIND_LABEL, safeColumns);
        this.row2 = CellText.fit(REPLACE_LABEL, safeColumns);

        ArrayList<ButtonSpec> placedButtons = new ArrayList<>(8);
        appendButtons(placedButtons, optionTargets, optionsStart, 0, optionsVisible);
        appendButtons(placedButtons, actionTargets, actionsStart, 1, actionsVisible);
        this.buttons = List.copyOf(placedButtons);
    }

    public static SearchBarView build(
        TextField queryField,
        TextField replacementField,
        int columns,
        boolean caseSensitive,
        SearchOptions.MatchMode matchMode,
        boolean allTabs
    ) {
        return new SearchBarView(
            queryField,
            replacementField,
            columns,
            caseSensitive,
            matchMode,
            allTabs
        );
    }

    /** Raster content contains labels/background only; controls paint themselves. */
    public String text() {
        return row1 + "\n" + row2;
    }

    public String row1() {
        return row1;
    }

    public String row2() {
        return row2;
    }

    /** Creates one immutable placement shared by focus, routing, paint and cursor lookup. */
    public Placement place(Object scopeId, int topRow, Rect interactionClip) {
        Objects.requireNonNull(scopeId, "scopeId");
        Rect safeClip = Objects.requireNonNull(interactionClip, "interactionClip");
        ArrayList<RoutedTarget<SearchFocus, Action>> targets = new ArrayList<>(SearchNavigation.order().size());

        TextField.Placement queryPlacement = queryField.layout(
            new Rect(queryStart, topRow, queryWidth, 1)
        );
        addTarget(
            targets,
            SearchFocus.FIND,
            new FieldPlacement(queryPlacement, Action.QUERY_CHANGED),
            safeClip
        );
        TextField.Placement replacementPlacement = replacementField.layout(
            new Rect(replacementStart, topRow + 1, replacementWidth, 1)
        );
        addTarget(
            targets,
            SearchFocus.REPLACE,
            new FieldPlacement(replacementPlacement, Action.REPLACEMENT_CHANGED),
            safeClip
        );
        for (ButtonSpec spec : buttons) {
            PlacedInteractiveWidget<Button.Action> placedButton = spec.button().layout(
                new Rect(spec.column(), topRow + spec.row(), spec.visible() ? spec.button().width() : 0, 1)
            );
            addTarget(
                targets,
                spec.target(),
                new ButtonPlacement(placedButton, spec.action()),
                safeClip
            );
        }

        FocusScope<SearchFocus, Action> scope = new FocusScope<>(
            scopeId,
            FocusResetPolicy.FIRST_FOCUSABLE,
            targets,
            SearchNavigation.order(),
            SearchNavigation.order()
        );
        return new Placement(row1, row2, scope, queryField, replacementField);
    }

    /** Typed application intents emitted by the composed reusable controls. */
    public enum Action {
        QUERY_CHANGED,
        REPLACEMENT_CHANGED,
        INPUT_REJECTED,
        FIND_NEXT,
        FIND_PREVIOUS,
        REPLACE,
        REPLACE_ALL,
        TOGGLE_CASE,
        TOGGLE_WORD,
        TOGGLE_WILDCARD,
        TOGGLE_ALL_TABS,
        CLOSE_REQUESTED
    }

    /** Exact current-frame search placement. */
    public static final class Placement {
        private final String row1;
        private final String row2;
        private final FocusScope<SearchFocus, Action> focusScope;
        private final TextField queryField;
        private final TextField replacementField;

        private Placement(
            String row1,
            String row2,
            FocusScope<SearchFocus, Action> focusScope,
            TextField queryField,
            TextField replacementField
        ) {
            this.row1 = Objects.requireNonNull(row1, "row1");
            this.row2 = Objects.requireNonNull(row2, "row2");
            this.focusScope = Objects.requireNonNull(focusScope, "focusScope");
            this.queryField = Objects.requireNonNull(queryField, "queryField");
            this.replacementField = Objects.requireNonNull(replacementField, "replacementField");
        }

        public String text() {
            return row1 + "\n" + row2;
        }

        public String row1() {
            return row1;
        }

        public String row2() {
            return row2;
        }

        public FocusScope<SearchFocus, Action> focusScope() {
            return focusScope;
        }

        /** The enabled buffer for an explicitly selected recipient; never falls back to Find. */
        public Optional<TextField> field(SearchFocus targetId) {
            if (targetId == null || !focusScope.target(targetId).map(RoutedTarget::enabled).orElse(false)) {
                return Optional.empty();
            }
            return switch (targetId) {
                case FIND -> Optional.of(queryField);
                case REPLACE -> Optional.of(replacementField);
                default -> Optional.empty();
            };
        }

        public Rect targetBounds(SearchFocus target) {
            return focusScope.target(Objects.requireNonNull(target, "target"))
                .orElseThrow()
                .placement()
                .bounds();
        }

        /** Enters or reconciles the search scope without duplicating focus in application state. */
        public void activate(FocusManager<SearchFocus> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            focusManager.context(focusScope, SearchFocus.FIND);
        }

        /** Paints every control exactly once from this placement snapshot. */
        public void paint(Canvas canvas, Theme theme, FocusManager<SearchFocus> focusManager) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(focusManager, "focusManager");
            for (SearchFocus id : SearchNavigation.order()) {
                RoutedTarget<SearchFocus, Action> target = focusScope.target(id).orElseThrow();
                FocusContext context = focusContext(focusManager, target);
                target.placement().paint(canvas, theme, context.widgetState());
            }
        }

        /** Maps the focused field's parent-local cursor intent to terminal coordinates. */
        public Optional<Point> cursorPosition(FocusManager<SearchFocus> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            SearchFocus focused = focusManager.focused().orElse(null);
            if (focused == null) {
                return Optional.empty();
            }
            RoutedTarget<SearchFocus, Action> target = focusScope.target(focused).orElse(null);
            if (target == null || !(target.placement() instanceof PlacedCursorSource cursorSource)) {
                return Optional.empty();
            }
            FocusContext context = focusContext(focusManager, target);
            return cursorSource.cursorPosition(context).flatMap(context.space()::terminalPoint);
        }

        private FocusContext focusContext(
            FocusManager<SearchFocus> focusManager,
            RoutedTarget<SearchFocus, Action> target
        ) {
            boolean active = focusManager.activeScopeId().filter(focusScope.id()::equals).isPresent();
            boolean focused = active
                && focusManager.focused().filter(target.id()::equals).isPresent()
                && target.enabled();
            return new FocusContext(focused, target.enabled(), target.space());
        }
    }

    private static int clusterStart(List<ButtonTarget> targets, int columns) {
        int clusterWidth = clusterWidth(targets);
        long required = (long) clusterWidth + RIGHT_MARGIN;
        return required >= columns ? 0 : columns - (int) required;
    }

    private static int leftWidth(List<ButtonTarget> targets, int columns) {
        long required = (long) clusterWidth(targets) + RIGHT_MARGIN;
        return required >= columns ? 0 : columns - (int) required;
    }

    private static int clusterWidth(List<ButtonTarget> targets) {
        int result = 0;
        for (int index = 0; index < targets.size(); index++) {
            if (index > 0) {
                result++;
            }
            result += targets.get(index).button().width();
        }
        return result;
    }

    private static void appendButtons(
        List<ButtonSpec> destination,
        List<ButtonTarget> targets,
        int startColumn,
        int row,
        boolean visible
    ) {
        int column = startColumn;
        for (ButtonTarget target : targets) {
            destination.add(new ButtonSpec(
                target.target(),
                target.button(),
                target.action(),
                row,
                column,
                visible
            ));
            column += target.button().width() + 1;
        }
    }

    private static void addTarget(
        List<RoutedTarget<SearchFocus, Action>> targets,
        SearchFocus id,
        PlacedInteractiveWidget<Action> placement,
        Rect interactionClip
    ) {
        Rect bounds = placement.bounds();
        boolean interactive = !bounds.isEmpty() && interactionClip.contains(bounds);
        targets.add(new RoutedTarget<>(
            id,
            placement,
            FocusSpace.root(interactionClip),
            interactive,
            interactive
        ));
    }

    private static InteractionResult<Action> mapped(
        InteractionResult<?> source,
        Action output
    ) {
        return source.mapOutput(value -> Objects.requireNonNull(output, "output"));
    }

    private record FieldPlacement(
        TextField.Placement delegate,
        Action changedAction
    ) implements PlacedPointerCapture<Action>, PlacedCursorSource {
        private FieldPlacement {
            Objects.requireNonNull(delegate, "delegate");
            if (changedAction != Action.QUERY_CHANGED
                && changedAction != Action.REPLACEMENT_CHANGED) {
                throw new IllegalArgumentException("field placement requires a changed action");
            }
        }

        @Override
        public Rect bounds() {
            return delegate.bounds();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, dev.tuiframe.widget.WidgetState state) {
            delegate.paint(canvas, theme, state);
        }

        @Override
        public boolean pointerCaptureActive() {
            return delegate.pointerCaptureActive();
        }

        @Override
        public InteractionResult<Action> cancelPointerCapture(PointerCaptureCancellation reason) {
            return delegate.cancelPointerCapture(reason).mapOutput(value -> changedAction);
        }

        @Override
        public InteractionResult<Action> handle(
            dev.tuiframe.input.TuiInputEvent event,
            FocusContext context
        ) {
            InteractionResult<TextField.Action> result = delegate.handle(event, context);
            if (result.output().isEmpty()) {
                return mapped(result, changedAction);
            }
            Action action = switch (result.output().orElseThrow()) {
                case VALUE_CHANGED -> changedAction;
                case SUBMITTED -> Action.FIND_NEXT;
                case INPUT_REJECTED -> Action.INPUT_REJECTED;
            };
            return mapped(result, action);
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            return delegate.cursorPosition(context);
        }
    }

    private record ButtonPlacement(
        PlacedInteractiveWidget<Button.Action> delegate,
        Action action
    ) implements PlacedInteractiveWidget<Action> {
        private ButtonPlacement {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(action, "action");
        }

        @Override
        public Rect bounds() {
            return delegate.bounds();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, dev.tuiframe.widget.WidgetState state) {
            delegate.paint(canvas, theme, state);
        }

        @Override
        public InteractionResult<Action> handle(
            dev.tuiframe.input.TuiInputEvent event,
            FocusContext context
        ) {
            return mapped(delegate.handle(event, context), action);
        }
    }

    private record ButtonTarget(SearchFocus target, Button button, Action action) {
        private ButtonTarget {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(button, "button");
            Objects.requireNonNull(action, "action");
        }
    }

    private record ButtonSpec(
        SearchFocus target,
        Button button,
        Action action,
        int row,
        int column,
        boolean visible
    ) {
    }
}
