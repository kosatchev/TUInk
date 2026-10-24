package dev.tuink.unicode;

import java.util.Arrays;

/** Persistent slices of text-free boundary arrays. Updates retain no earlier source sequence. */
final class BoundaryIndex {
    private static final int MAX_BLOCK_GRAPHEMES = 256;
    interface Data {
        int count();
        int boundary(int index);
        int floor(int offset);
        int weight();
    }

    private final Node root;
    private BoundaryIndex(Node root) { this.root = root; }
    static BoundaryIndex of(Data data) {
        return new BoundaryIndex(data.count() == 0 ? null : new Node(data, 0, data.count()));
    }
    int count() { return count(root); }
    int length() { return units(root); }
    int weight() { return root == null ? 1 : root.weight; }
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
    BoundaryIndex slice(int from, int to) {
        if (from < 0 || to < from || to > count()) throw new IndexOutOfBoundsException();
        return new BoundaryIndex(slice(root, from, to));
    }
    static BoundaryIndex join(BoundaryIndex first, BoundaryIndex middle, BoundaryIndex last) {
        return new BoundaryIndex(join(join(first.root, middle.root), last.root));
    }
    private static Node slice(Node node, int from, int to) {
        if (from == to) return null;
        if (from == 0 && to == count(node)) return node;
        if (node.data != null) {
            Node result = new Node(node.data, node.from + from, to - from);
            return result.count <= MAX_BLOCK_GRAPHEMES && result.count * 4L < node.data.count()
                ? compact(result, null) : result;
        }
        int leftCount = count(node.left);
        if (to <= leftCount) return slice(node.left, from, to);
        if (from >= leftCount) return slice(node.right, from - leftCount, to - leftCount);
        return join(slice(node.left, from, leftCount), slice(node.right, 0, to - leftCount));
    }
    private static Node join(Node left, Node right) {
        if (left == null) return right;
        if (right == null) return left;
        if (left.data != null && left.data == right.data && left.from + left.count == right.from) {
            return new Node(left.data, left.from, left.count + right.count);
        }
        if (left.data != null && right.data != null && left.count + right.count <= MAX_BLOCK_GRAPHEMES) {
            return compact(left, right);
        }
        if (height(left) > height(right) + 1) return balance(left.left, join(left.right, right));
        if (height(right) > height(left) + 1) return balance(join(left, right.left), right.right);
        return new Node(left, right);
    }
    private static Node balance(Node left, Node right) {
        if (height(left) > height(right) + 1) {
            if (height(left.left) < height(left.right)) {
                Node pivot = left.right;
                return new Node(new Node(left.left, pivot.left), new Node(pivot.right, right));
            }
            return new Node(left.left, new Node(left.right, right));
        }
        if (height(right) > height(left) + 1) {
            if (height(right.right) < height(right.left)) {
                Node pivot = right.left;
                return new Node(new Node(left, pivot.left), new Node(pivot.right, right.right));
            }
            return new Node(new Node(left, right.left), right.right);
        }
        return new Node(left, right);
    }
    private static int count(Node node) { return node == null ? 0 : node.count; }
    private static int units(Node node) { return node == null ? 0 : node.units; }
    private static int height(Node node) { return node == null ? 0 : node.height; }

    /** Copies only a bounded index block, never the source character data. */
    private static Node compact(Node left, Node right) {
        int count = left.count + count(right);
        int units = left.units + units(right);
        int[] boundaries = units == count ? null : new int[count + 1];
        if (boundaries != null) {
            for (int index = 1; index <= count; index++) {
                boundaries[index] = index <= left.count
                    ? left.data.boundary(left.from + index) - left.data.boundary(left.from)
                    : left.units + right.data.boundary(right.from + index - left.count) - right.data.boundary(right.from);
            }
        }
        return new Node(new Block(count, boundaries), 0, count);
    }

    private record Block(int count, int[] boundaries) implements Data {
        @Override public int boundary(int index) { return boundaries == null ? index : boundaries[index]; }
        @Override public int floor(int offset) {
            if (boundaries == null) return offset;
            int found = Arrays.binarySearch(boundaries, offset);
            return found >= 0 ? found : Math.max(0, -found - 2);
        }
        @Override public int weight() { return boundaries == null ? 1 : boundaries.length; }
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
        Node(Data data, int from, int count) {
            this.data = data;
            this.from = from;
            left = right = null;
            this.count = count;
            units = data.boundary(from + count) - data.boundary(from);
            height = 1;
            // A slice keeps the entire backing arrays alive. Counting the
            // same shared arrays in multiple slices is deliberately conservative.
            weight = (int) Math.min(Integer.MAX_VALUE, (long) data.weight() + 8);
        }
        Node(Node left, Node right) {
            data = null;
            from = 0;
            this.left = left;
            this.right = right;
            count = count(left) + count(right);
            units = units(left) + units(right);
            height = Math.max(height(left), height(right)) + 1;
            weight = (int) Math.min(Integer.MAX_VALUE, (long) left.weight + right.weight + 8);
        }
    }
}
