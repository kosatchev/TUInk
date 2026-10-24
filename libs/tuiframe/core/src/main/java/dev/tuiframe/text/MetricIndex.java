package dev.tuiframe.text;

import java.util.Arrays;

/** Persistent slices of terminal metric arrays; nodes contain no source text references. */
final class MetricIndex {
    private static final int MAX_BLOCK_GRAPHEMES = 256;
    interface Data {
        int count();
        int boundary(int index);
        int floor(int offset);
        int column(int index);
        int firstTab(int from, int to);
        int width(int index);
        boolean whitespace(int index);
        int weight();
        boolean noWhitespace();
    }

    private final Node root;
    private final int tabWidth;
    private MetricIndex(Node root, int tabWidth) { this.root = root; this.tabWidth = tabWidth; }
    static MetricIndex of(Data data, int tabWidth) {
        return new MetricIndex(data.count() == 0 ? null : new Node(data, 0, data.count(), tabWidth), tabWidth);
    }
    int count() { return count(root); }
    int weight() { return root == null ? 1 : root.weight; }
    boolean noWhitespace() { return root == null || root.noWhitespace; }
    int uniformWidth() {
        if (root == null || root.tabs) return 0;
        return root.leading == root.count ? 1 : root.leading == 2L * root.count ? 2 : 0;
    }
    int boundary(int index) {
        if (index < 0 || index > count()) throw new IndexOutOfBoundsException(index);
        Node node = root;
        int offset = 0;
        while (node != null && node.data == null) {
            if (index < count(node.left)) node = node.left;
            else { index -= count(node.left); offset += units(node.left); node = node.right; }
        }
        return node == null ? 0 : offset + node.data.boundary(node.from + index) - node.data.boundary(node.from);
    }
    int floor(int offset) {
        Node node = root;
        int index = 0;
        while (node != null && node.data == null) {
            if (offset < units(node.left)) node = node.left;
            else { offset -= units(node.left); index += count(node.left); node = node.right; }
        }
        return node == null ? 0 : index + node.data.floor(offset + node.data.boundary(node.from)) - node.from;
    }
    int column(int index) {
        Node node = root;
        int column = 0;
        while (node != null && node.data == null) {
            if (index < count(node.left)) node = node.left;
            else { index -= count(node.left); column = node.left.advance(column, tabWidth); node = node.right; }
        }
        return node == null ? 0 : advance(node.data, node.from, node.from + index, column, tabWidth);
    }
    int width(int index) {
        Node node = root;
        while (node.data == null) {
            if (index < count(node.left)) node = node.left;
            else { index -= count(node.left); node = node.right; }
        }
        return node.data.width(node.from + index);
    }
    boolean whitespace(int index) {
        Node node = root;
        while (node.data == null) {
            if (index < count(node.left)) node = node.left;
            else { index -= count(node.left); node = node.right; }
        }
        return node.data.whitespace(node.from + index);
    }
    MetricIndex slice(int from, int to) {
        if (from < 0 || to < from || to > count()) throw new IndexOutOfBoundsException();
        return new MetricIndex(slice(root, from, to, tabWidth), tabWidth);
    }
    static MetricIndex join(MetricIndex first, MetricIndex middle, MetricIndex last) {
        return new MetricIndex(join(join(first.root, middle.root, first.tabWidth), last.root, first.tabWidth), first.tabWidth);
    }
    private static int advance(Data data, int from, int to, int column, int tabWidth) {
        int tab = data.firstTab(from, to);
        if (tab < 0) return column + data.column(to) - data.column(from);
        int beforeTab = column + data.column(tab) - data.column(from);
        return beforeTab + tabWidth - beforeTab % tabWidth + data.column(to) - data.column(tab + 1);
    }
    private static Node slice(Node node, int from, int to, int tabWidth) {
        if (from == to) return null;
        if (from == 0 && to == count(node)) return node;
        if (node.data != null) {
            Node result = new Node(node.data, node.from + from, to - from, tabWidth);
            return result.count <= MAX_BLOCK_GRAPHEMES && result.count * 4L < node.data.count()
                ? compact(result, null, tabWidth) : result;
        }
        int leftCount = count(node.left);
        if (to <= leftCount) return slice(node.left, from, to, tabWidth);
        if (from >= leftCount) return slice(node.right, from - leftCount, to - leftCount, tabWidth);
        return join(slice(node.left, from, leftCount, tabWidth), slice(node.right, 0, to - leftCount, tabWidth), tabWidth);
    }
    private static Node join(Node left, Node right, int tabWidth) {
        if (left == null) return right;
        if (right == null) return left;
        if (left.data != null && left.data == right.data && left.from + left.count == right.from) {
            return new Node(left.data, left.from, left.count + right.count, tabWidth);
        }
        if (left.data != null && right.data != null && left.count + right.count <= MAX_BLOCK_GRAPHEMES) {
            return compact(left, right, tabWidth);
        }
        if (height(left) > height(right) + 1) return balance(left.left, join(left.right, right, tabWidth), tabWidth);
        if (height(right) > height(left) + 1) return balance(join(left, right.left, tabWidth), right.right, tabWidth);
        return new Node(left, right, tabWidth);
    }
    private static Node balance(Node left, Node right, int tabWidth) {
        if (height(left) > height(right) + 1) {
            if (height(left.left) < height(left.right)) {
                Node pivot = left.right;
                return new Node(new Node(left.left, pivot.left, tabWidth), new Node(pivot.right, right, tabWidth), tabWidth);
            }
            return new Node(left.left, new Node(left.right, right, tabWidth), tabWidth);
        }
        if (height(right) > height(left) + 1) {
            if (height(right.right) < height(right.left)) {
                Node pivot = right.left;
                return new Node(new Node(left, pivot.left, tabWidth), new Node(pivot.right, right.right, tabWidth), tabWidth);
            }
            return new Node(new Node(left, right.left, tabWidth), right.right, tabWidth);
        }
        return new Node(left, right, tabWidth);
    }
    private static int count(Node node) { return node == null ? 0 : node.count; }
    private static int units(Node node) { return node == null ? 0 : node.units; }
    private static int height(Node node) { return node == null ? 0 : node.height; }

    /** Bounded text-free compaction keeps a long typing session from retaining one node per key. */
    private static Node compact(Node left, Node right, int tabWidth) {
        int count = left.count + count(right);
        boolean noWhitespace = left.noWhitespace && (right == null || right.noWhitespace);
        int firstLength = left.data.boundary(left.from + 1) - left.data.boundary(left.from);
        int firstWidth = left.data.width(left.from);
        boolean uniform = noWhitespace && !left.tabs && (right == null || !right.tabs);
        for (int index = 0; uniform && index < count; index++) {
            Node source = index < left.count ? left : right;
            int at = source.from + (index < left.count ? index : index - left.count);
            uniform = source.data.boundary(at + 1) - source.data.boundary(at) == firstLength
                && source.data.width(at) == firstWidth;
        }
        if (uniform) {
            return new Node(new Block(count, firstLength, firstWidth, null, null, null, null, null, true),
                0, count, tabWidth);
        }
        int[] boundaries = new int[count + 1];
        int[] columns = new int[count + 1];
        byte[] widths = new byte[count];
        boolean[] blanks = new boolean[count];
        int[] tabs = new int[count];
        int tabCount = 0;
        for (int index = 0; index < count; index++) {
            Node source = index < left.count ? left : right;
            int at = source.from + (index < left.count ? index : index - left.count);
            boundaries[index + 1] = boundaries[index] + source.data.boundary(at + 1) - source.data.boundary(at);
            int width = source.data.width(at);
            boolean tab = source.data.firstTab(at, at + 1) >= 0;
            if (tab) tabs[tabCount++] = index;
            widths[index] = (byte) width;
            blanks[index] = source.data.whitespace(at);
            columns[index + 1] = columns[index] + (tab ? tabWidth - columns[index] % tabWidth : Math.max(1, width));
        }
        return new Node(new Block(count, 0, 0, boundaries, columns, widths, blanks,
            Arrays.copyOf(tabs, tabCount), noWhitespace), 0, count, tabWidth);
    }

    private record Block(int count, int unitLength, int unitWidth, int[] boundaries, int[] columns,
        byte[] widths, boolean[] blanks, int[] tabs, boolean noWhitespace) implements Data {
        @Override public int boundary(int index) { return boundaries == null ? index * unitLength : boundaries[index]; }
        @Override public int floor(int offset) {
            if (boundaries == null) return offset / unitLength;
            int found = Arrays.binarySearch(boundaries, offset);
            return found >= 0 ? found : Math.max(0, -found - 2);
        }
        @Override public int column(int index) { return columns == null ? index * Math.max(1, unitWidth) : columns[index]; }
        @Override public int firstTab(int from, int to) {
            if (tabs == null) return -1;
            int found = Arrays.binarySearch(tabs, from);
            int index = found >= 0 ? found : -found - 1;
            return index < tabs.length && tabs[index] < to ? tabs[index] : -1;
        }
        @Override public int width(int index) { return widths == null ? unitWidth : widths[index]; }
        @Override public boolean whitespace(int index) { return blanks != null && blanks[index]; }
        @Override public int weight() {
            return boundaries == null ? 1 : boundaries.length + columns.length + widths.length + blanks.length + tabs.length;
        }
    }
    private static final class Node {
        final Data data;
        final int from;
        final Node left;
        final Node right;
        final int count;
        final int units;
        final int height;
        final int weight;
        final boolean tabs;
        final int leading;
        final int trailing;
        final boolean noWhitespace;
        Node(Data data, int from, int count, int tabWidth) {
            this.data = data;
            this.from = from;
            left = right = null;
            this.count = count;
            units = data.boundary(from + count) - data.boundary(from);
            height = 1;
            int firstTab = data.firstTab(from, from + count);
            tabs = firstTab >= 0;
            leading = data.column(tabs ? firstTab : from + count) - data.column(from);
            trailing = tabs ? data.column(from + count) - data.column(firstTab + 1) : 0;
            noWhitespace = data.noWhitespace();
            // Slicing does not shorten a retained backing array. Overcount
            // arrays shared by multiple slices rather than underbudget a cache.
            weight = (int) Math.min(Integer.MAX_VALUE, (long) data.weight() + 12);
        }
        Node(Node left, Node right, int tabWidth) {
            data = null;
            from = 0;
            this.left = left;
            this.right = right;
            count = count(left) + count(right);
            units = units(left) + units(right);
            height = Math.max(height(left), height(right)) + 1;
            tabs = left.tabs || right.tabs;
            leading = left.tabs ? left.leading : left.leading + right.leading;
            trailing = left.tabs ? right.advance(left.trailing, tabWidth) : right.trailing;
            noWhitespace = left.noWhitespace && right.noWhitespace;
            weight = (int) Math.min(Integer.MAX_VALUE, (long) left.weight + right.weight + 12);
        }
        int advance(int column, int tabWidth) {
            if (!tabs) return column + leading;
            int beforeTab = column + leading;
            return beforeTab + tabWidth - beforeTab % tabWidth + trailing;
        }
    }
}
