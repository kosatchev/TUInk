package dev.tuink.tui.ui;

import dev.tuink.editor.search.SearchOptions;
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
import dev.tuiframe.widget.InteractionVisibility;
import dev.tuiframe.widget.PlacedCursorSource;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.SearchNavigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Application-owned search composition built from reusable widgets. */
public final class SearchBarView {
    public static final String FIND_LABEL = "Find: ";
    public static final String REPLACE_LABEL = "Replace: ";
    private static final int RIGHT_MARGIN = 1;
    // A two-cell glyph and its insertion cursor must fit side by side.
    private static final int MIN_FIELD_WIDTH = 3;

    private final TextField queryField;
    private final TextField replacementField;
    private final int columns;
    private final boolean readOnly;
    private final String row1;
    private final String row2;
    private final String text;
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
        boolean allTabs,
        boolean readOnly
    ) {
        this.queryField = Objects.requireNonNull(queryField, "queryField");
        this.replacementField = readOnly ? null : Objects.requireNonNull(replacementField, "replacementField");
        this.readOnly = readOnly;
        int safeColumns = Math.max(0, columns);
        this.columns = safeColumns;
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

        List<ButtonTarget> optionTargets = readOnly ? List.of(
            new ButtonTarget(SearchFocus.CASE, caseToggle, Action.TOGGLE_CASE),
            new ButtonTarget(SearchFocus.WORD, wordToggle, Action.TOGGLE_WORD),
            new ButtonTarget(SearchFocus.WILDCARD, wildcardToggle, Action.TOGGLE_WILDCARD),
            new ButtonTarget(SearchFocus.NEXT_BUTTON, next, Action.FIND_NEXT),
            new ButtonTarget(SearchFocus.PREV_BUTTON, previous, Action.FIND_PREVIOUS)
        ) : List.of(
            new ButtonTarget(SearchFocus.CASE, caseToggle, Action.TOGGLE_CASE),
            new ButtonTarget(SearchFocus.WORD, wordToggle, Action.TOGGLE_WORD),
            new ButtonTarget(SearchFocus.WILDCARD, wildcardToggle, Action.TOGGLE_WILDCARD),
            new ButtonTarget(SearchFocus.ALL, allToggle, Action.TOGGLE_ALL_TABS)
        );
        List<ButtonTarget> actionTargets = readOnly ? List.of() : List.of(
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
        boolean optionsVisible = optionLeftWidth - findLabelWidth >= MIN_FIELD_WIDTH;
        boolean actionsVisible = readOnly || actionLeftWidth - replaceLabelWidth >= MIN_FIELD_WIDTH;
        boolean compact = !optionsVisible || !actionsVisible;
        if (compact) { optionLeftWidth = safeColumns; actionLeftWidth = safeColumns; }
        if (!optionsVisible) {
            optionLeftWidth = safeColumns;
        }
        if (!actionsVisible) {
            actionLeftWidth = safeColumns;
        }
        this.queryStart = Math.min(findLabelWidth, optionLeftWidth);
        this.queryWidth = Math.max(0, optionLeftWidth - findLabelWidth);
        String replaceLabel = compact && safeColumns < 12 ? "Repl: " : REPLACE_LABEL;
        int actualReplaceLabelWidth = CellText.width(replaceLabel);
        this.replacementStart = Math.min(actualReplaceLabelWidth, actionLeftWidth);
        this.replacementWidth = Math.max(0, actionLeftWidth - actualReplaceLabelWidth);
        this.row1 = CellText.fit(FIND_LABEL, safeColumns);
        this.row2 = readOnly ? "" : CellText.fit(replaceLabel, safeColumns);

        ArrayList<ButtonSpec> placedButtons = new ArrayList<>(8);
        List<String> rows = new ArrayList<>(readOnly ? List.of(row1) : List.of(row1, row2));
        if (compact) {
            appendFlow(placedButtons, optionTargets, rows, safeColumns);
            appendFlow(placedButtons, actionTargets, rows, safeColumns);
        } else {
            appendButtons(placedButtons, optionTargets, optionsStart, 0, optionsVisible);
            appendButtons(placedButtons, actionTargets, actionsStart, 1, actionsVisible);
        }
        this.text = String.join("\n", rows);
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
            allTabs,
            false
        );
    }

    /** Find-only controls for presentations which cannot modify document text. */
    public static SearchBarView buildReadOnly(TextField queryField, int columns,
        boolean caseSensitive, SearchOptions.MatchMode matchMode) {
        return new SearchBarView(queryField, null, columns, caseSensitive, matchMode, false, true);
    }

    /** Raster content contains labels/background only; controls paint themselves. */
    public String text() {
        return text;
    }

    public String row1() {
        return row1;
    }

    public String row2() {
        return row2;
    }

    /** Creates one immutable placement shared by focus, routing, paint and cursor lookup. */
    public Placement place(Object scopeId, int topRow, Rect interactionClip) {
        return place(scopeId, new Rect(0, topRow, columns, text.split("\n", -1).length), interactionClip);
    }

    /** Places children inside a parent-owned rectangle without recalculating window coordinates. */
    public Placement place(Object scopeId, Rect bounds, Rect interactionClip) {
        Objects.requireNonNull(scopeId, "scopeId");
        Rect area = Objects.requireNonNull(bounds, "bounds");
        Rect safeClip = Objects.requireNonNull(interactionClip, "interactionClip").intersection(area);
        int topRow = area.y();
        ArrayList<RoutedTarget<SearchFocus, Action>> targets = new ArrayList<>(SearchNavigation.order().size());

        TextField.Placement queryPlacement = queryField.layout(
            new Rect(area.x() + queryStart, topRow, queryWidth, 1)
        );
        addTarget(
            targets,
            SearchFocus.FIND,
            queryPlacement.mapOutput(action -> fieldAction(action, Action.QUERY_CHANGED)),
            safeClip
        );
        TextField.Placement replacementPlacement = readOnly ? null : replacementField.layout(
            new Rect(area.x() + replacementStart, topRow + 1, replacementWidth, 1));
        if (replacementPlacement != null) addTarget(targets, SearchFocus.REPLACE,
            replacementPlacement.mapOutput(action -> fieldAction(action, Action.REPLACEMENT_CHANGED)), safeClip);
        boolean replacementContextVisible = replacementPlacement != null && queryPlacement.bounds().width() >= MIN_FIELD_WIDTH
            && replacementPlacement.bounds().width() >= MIN_FIELD_WIDTH
            && InteractionVisibility.FULL_BOUNDS.allows(queryPlacement.bounds(), safeClip)
            && InteractionVisibility.FULL_BOUNDS.allows(replacementPlacement.bounds(), safeClip);
        for (ButtonSpec spec : buttons) {
            PlacedInteractiveWidget<Button.Action> placedButton = spec.button().layout(
                new Rect(area.x() + spec.column(), topRow + spec.row(), spec.visible() ? spec.button().width() : 0, 1)
            );
            addTarget(
                targets,
                spec.target(),
                placedButton.mapOutput(ignored -> spec.action()),
                safeClip,
                replacementContextVisible || (spec.action() != Action.REPLACE && spec.action() != Action.REPLACE_ALL)
            );
        }

        List<SearchFocus> order = readOnly ? List.of(SearchFocus.FIND, SearchFocus.CASE, SearchFocus.WORD,
            SearchFocus.WILDCARD, SearchFocus.NEXT_BUTTON, SearchFocus.PREV_BUTTON) : SearchNavigation.order();
        FocusScope<SearchFocus, Action> scope = new FocusScope<>(
            scopeId,
            FocusResetPolicy.FIRST_FOCUSABLE,
            targets,
            order,
            order
        );
        return new Placement(row1, row2, text, scope, queryField, replacementField, queryPlacement,
            replacementPlacement, replacementContextVisible, readOnly);
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
        private final String text;
        private final FocusScope<SearchFocus, Action> focusScope;
        private final TextField queryField;
        private final TextField replacementField;
        private final TextField.Placement queryPlacement;
        private final TextField.Placement replacementPlacement;
        private final boolean replacementContextVisible;
        private final boolean readOnly;

        private Placement(
            String row1,
            String row2,
            String text,
            FocusScope<SearchFocus, Action> focusScope,
            TextField queryField,
            TextField replacementField,
            TextField.Placement queryPlacement,
            TextField.Placement replacementPlacement,
            boolean replacementContextVisible,
            boolean readOnly
        ) {
            this.row1 = Objects.requireNonNull(row1, "row1");
            this.row2 = Objects.requireNonNull(row2, "row2");
            this.text = Objects.requireNonNull(text, "text");
            this.focusScope = Objects.requireNonNull(focusScope, "focusScope");
            this.queryField = Objects.requireNonNull(queryField, "queryField");
            this.replacementField = replacementField;
            this.queryPlacement = Objects.requireNonNull(queryPlacement, "queryPlacement");
            this.replacementPlacement = replacementPlacement;
            this.replacementContextVisible = replacementContextVisible;
            this.readOnly = readOnly;
        }

        public String text() {
            return text;
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
            if (targetId == null || !focusScope.target(targetId).map(RoutedTarget::interactive).orElse(false)) {
                return Optional.empty();
            }
            return switch (targetId) {
                case FIND -> Optional.of(queryField);
                case REPLACE -> Optional.of(replacementField);
                default -> Optional.empty();
            };
        }

        /** Replacement needs both editable values on screen, including a cell beside each caret. */
        public boolean replacementContextVisible() {
            return replacementContextVisible;
        }

        public boolean readOnly() { return readOnly; }

        /** Raw field placement shares the same coordinates as the routed descriptor. */
        public Optional<TextField.Placement> fieldPlacement(SearchFocus target) {
            return Optional.ofNullable(target == SearchFocus.FIND ? queryPlacement
                : target == SearchFocus.REPLACE ? replacementPlacement : null);
        }

        public Rect targetBounds(SearchFocus target) {
            return focusScope.target(Objects.requireNonNull(target, "target"))
                .orElseThrow()
                .placement()
                .bounds();
        }

        /** Expanded bars navigate their painted rows; the ordinary two-row bar keeps its logical pairs. */
        public SearchFocus verticalTarget(SearchFocus current, boolean up) {
            if (!readOnly && text.indexOf('\n') == text.lastIndexOf('\n')) return SearchNavigation.vertical(current, up);
            Rect origin = targetBounds(current);
            SearchFocus best = current;
            int nearestRow = Integer.MAX_VALUE;
            long nearestColumn = Long.MAX_VALUE;
            for (SearchFocus candidate : focusScope.tabOrder()) {
                if (!readOnly && (candidate == SearchFocus.FIND || candidate == SearchFocus.REPLACE)) continue;
                var target = focusScope.target(candidate).orElseThrow();
                if (!target.focusEligible()) continue;
                Rect bounds = target.placement().bounds();
                int distance = up ? origin.y() - bounds.y() : bounds.y() - origin.y();
                if (distance <= 0) continue;
                long horizontal = Math.abs((2L * origin.x() + origin.width()) - (2L * bounds.x() + bounds.width()));
                if (distance < nearestRow || distance == nearestRow && horizontal < nearestColumn) {
                    best = candidate; nearestRow = distance; nearestColumn = horizontal;
                }
            }
            return best;
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
            for (SearchFocus id : focusScope.tabOrder()) {
                RoutedTarget<SearchFocus, Action> target = focusScope.target(id).orElseThrow();
                FocusContext context = focusManager.peekContext(focusScope, id).orElseThrow();
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
            FocusContext context = focusManager.peekContext(focusScope, focused).orElseThrow();
            return cursorSource.cursorPosition(context).flatMap(context.space()::terminalPoint);
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

    /** Narrow rows retain every action in the same focus scope instead of hiding whole clusters. */
    private static void appendFlow(List<ButtonSpec> destination, List<ButtonTarget> targets,
        List<String> rows, int columns) {
        int column = 0;
        boolean unavailable = false;
        for (ButtonTarget target : targets) {
            Button button = target.button();
            if (button.width() > columns && target.target() == SearchFocus.ALL_BUTTON) button = new Button("Rpl all");
            if (button.width() > columns && target.target() == SearchFocus.ALL) button = new Button("All");
            boolean visible = button.width() <= columns;
            if (column == 0 || column + button.width() > columns) { rows.add(" ".repeat(columns)); column = 0; }
            destination.add(new ButtonSpec(target.target(), button, target.action(), rows.size() - 1, column, visible));
            column += button.width() + 1;
            unavailable |= !visible;
        }
        if (unavailable) rows.add(CellText.fit("Enlarge window", columns));
    }

    private static void addTarget(
        List<RoutedTarget<SearchFocus, Action>> targets,
        SearchFocus id,
        PlacedInteractiveWidget<Action> placement,
        Rect interactionClip
    ) {
        addTarget(targets, id, placement, interactionClip, true);
    }

    private static void addTarget(
        List<RoutedTarget<SearchFocus, Action>> targets,
        SearchFocus id,
        PlacedInteractiveWidget<Action> placement,
        Rect interactionClip,
        boolean enabled
    ) {
        targets.add(new RoutedTarget<>(
            id,
            placement,
            FocusSpace.root(interactionClip),
            true,
            enabled
        ));
    }

    private static Action fieldAction(TextField.Action action, Action changedAction) {
        return switch (action) {
            case VALUE_CHANGED -> changedAction;
            case SUBMITTED -> Action.FIND_NEXT;
            case INPUT_REJECTED -> Action.INPUT_REJECTED;
        };
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
