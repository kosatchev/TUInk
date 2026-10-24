package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.util.Arrays;

/** Persistent logical-cell summaries. Only tabs need retained per-grapheme coordinates. */
final class LogicalColumnIndex {
    private static final int MAX_BLOCK_GRAPHEMES = 256;
    private static final int[] EMPTY = new int[0];
    private final Node root;
    private final int tabWidth;
    private LogicalColumnIndex(Node root, int tabWidth) { this.root = root; this.tabWidth = tabWidth; }
    static LogicalColumnIndex build(GraphemeText graphemes, int start, int end, int tabWidth) {
        int first = graphemes.boundaryIndexAtOrBefore(start);
        int count = graphemes.boundaryIndexAtOrBefore(end) - first;
        if (count == 0) return new LogicalColumnIndex(null, tabWidth);
        int[] tabs = new int[8];
        int[] columns = new int[8];
        int size = 0;
        int previous = -1;
        int visual = 0;
        CharSequence sequence = graphemes.sequence();
        for (int offset = start; offset < end; offset++) {
            if (sequence.charAt(offset) != '\t') continue;
            int index = graphemes.boundaryIndexAtOrBefore(offset) - first;
            visual += index - previous - 1;
            visual += tabWidth - visual % tabWidth;
            if (size == tabs.length) {
                tabs = Arrays.copyOf(tabs, size * 2);
                columns = Arrays.copyOf(columns, size * 2);
            }
            tabs[size] = index;
            columns[size++] = visual;
            previous = index;
        }
        Data data = new Data(count, Arrays.copyOf(tabs, size), Arrays.copyOf(columns, size));
        return new LogicalColumnIndex(new Node(data, 0, count, tabWidth), tabWidth);
    }
    int count() { return count(root); }
    int column(int index) {
        Node node = root;
        int visual = 0;
        while (node != null && node.data == null) {
            if (index < count(node.left)) node = node.left;
            else { index -= count(node.left); visual = node.left.advance(visual, tabWidth); node = node.right; }
        }
        if (node == null) return 0;
        int from = node.from;
        int to = from + index;
        int tab = node.data.firstTab(from, to);
        if (tab < 0) return visual + index;
        int before = visual + tab - from;
        return before + tabWidth - before % tabWidth + node.data.column(to) - node.data.column(tab + 1);
    }
    int floorColumn(int target) {
        int low = 0;
        int high = count();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (column(middle) <= target) low = middle;
            else high = middle - 1;
        }
        return low;
    }
    LogicalColumnIndex updated(LogicalColumnIndex changed, int before, int after) {
        return new LogicalColumnIndex(join(join(slice(root, 0, before, tabWidth), changed.root, tabWidth),
            slice(root, after, count(), tabWidth), tabWidth), tabWidth);
    }
    private static Node slice(Node node, int from, int to, int tabWidth) {
        if (from == to) return null;
        if (from == 0 && to == count(node)) return node;
        if (node.data != null) {
            Node result = new Node(node.data, node.from + from, to - from, tabWidth);
            return result.count <= MAX_BLOCK_GRAPHEMES && result.count * 4L < node.data.count()
                ? compact(result, null, tabWidth) : result;
        }
        int left = count(node.left);
        if (to <= left) return slice(node.left, from, to, tabWidth);
        if (from >= left) return slice(node.right, from - left, to - left, tabWidth);
        return join(slice(node.left, from, left, tabWidth), slice(node.right, 0, to - left, tabWidth), tabWidth);
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
                Node middle = left.right;
                return new Node(new Node(left.left, middle.left, tabWidth), new Node(middle.right, right, tabWidth), tabWidth);
            }
            return new Node(left.left, new Node(left.right, right, tabWidth), tabWidth);
        }
        if (height(right) > height(left) + 1) {
            if (height(right.right) < height(right.left)) {
                Node middle = right.left;
                return new Node(new Node(left, middle.left, tabWidth), new Node(middle.right, right.right, tabWidth), tabWidth);
            }
            return new Node(new Node(left, right.left, tabWidth), right.right, tabWidth);
        }
        return new Node(left, right, tabWidth);
    }
    private static int count(Node node) { return node == null ? 0 : node.count; }
    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static Node compact(Node left, Node right, int tabWidth) {
        int count = left.count + count(right);
        if (!left.tabs && (right == null || !right.tabs)) return new Node(new Data(count, EMPTY, EMPTY), 0, count, tabWidth);
        int[] tabs = new int[count];
        int[] columns = new int[count];
        int size = 0;
        int visual = 0;
        for (int index = 0; index < count; index++) {
            Node source = index < left.count ? left : right;
            int at = source.from + (index < left.count ? index : index - left.count);
            if (source.data.firstTab(at, at + 1) >= 0) {
                visual += tabWidth - visual % tabWidth;
                tabs[size] = index;
                columns[size++] = visual;
            } else visual++;
        }
        return new Node(new Data(count, Arrays.copyOf(tabs, size), Arrays.copyOf(columns, size)), 0, count, tabWidth);
    }
    private record Data(int count, int[] tabs, int[] columns) {
        int firstTab(int from, int to) {
            int found = Arrays.binarySearch(tabs, from);
            int index = found >= 0 ? found : -found - 1;
            return index < tabs.length && tabs[index] < to ? tabs[index] : -1;
        }
        int column(int index) {
            int found = Arrays.binarySearch(tabs, index - 1);
            int previous = found >= 0 ? found : -found - 2;
            return previous < 0 ? index : columns[previous] + index - tabs[previous] - 1;
        }
    }
    private static final class Node {
        final Data data;
        final int from;
        final Node left;
        final Node right;
        final int count;
        final int height;
        final boolean tabs;
        final int leading;
        final int trailing;
        Node(Data data, int from, int count, int tabWidth) {
            this.data = data;
            this.from = from;
            left = right = null;
            this.count = count;
            height = 1;
            int firstTab = data.firstTab(from, from + count);
            tabs = firstTab >= 0;
            leading = tabs ? firstTab - from : count;
            trailing = tabs ? data.column(from + count) - data.column(firstTab + 1) : 0;
        }
        Node(Node left, Node right, int tabWidth) {
            data = null;
            from = 0;
            this.left = left;
            this.right = right;
            count = count(left) + count(right);
            height = Math.max(height(left), height(right)) + 1;
            tabs = left.tabs || right.tabs;
            leading = left.tabs ? left.leading : left.leading + right.leading;
            trailing = left.tabs ? right.advance(left.trailing, tabWidth) : right.trailing;
        }
        int advance(int column, int tabWidth) {
            if (!tabs) return column + leading;
            int before = column + leading;
            return before + tabWidth - before % tabWidth + trailing;
        }
    }
}
