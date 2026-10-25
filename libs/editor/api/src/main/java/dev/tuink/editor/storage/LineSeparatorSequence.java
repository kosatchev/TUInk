package dev.tuink.editor.storage;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Immutable AVL rope of separator runs; edits share every unaffected subtree. */
public final class LineSeparatorSequence extends AbstractList<String> {
    private static final LineSeparatorSequence EMPTY = new LineSeparatorSequence(null);

    private final Node root;

    private LineSeparatorSequence(Node root) {
        this.root = root;
    }

    public static LineSeparatorSequence copyOf(List<String> separators) {
        if (separators instanceof LineSeparatorSequence sequence) {
            return sequence;
        }
        if (separators == null || separators.isEmpty()) {
            return EMPTY;
        }
        List<Node> runs = new ArrayList<>();
        String previous = null;
        int count = 0;
        for (String value : separators) {
            String separator = DocumentFileMetadata.requireLineSeparator(value);
            if (separator.equals(previous)) {
                count++;
            } else {
                if (count > 0) {
                    runs.add(Node.run(previous, count));
                }
                previous = separator;
                count = 1;
            }
        }
        runs.add(Node.run(previous, count));
        return new LineSeparatorSequence(build(runs, 0, runs.size()));
    }

    public static LineSeparatorSequence repeated(String separator, int count) {
        DocumentFileMetadata.requireLineSeparator(separator);
        if (count < 0) {
            throw new IllegalArgumentException("Negative separator count");
        }
        return count == 0 ? EMPTY : new LineSeparatorSequence(Node.run(separator, count));
    }

    public LineSeparatorSequence replace(int index, int removedCount, LineSeparatorSequence inserted) {
        Objects.checkFromIndexSize(index, removedCount, size());
        if (removedCount == 0 && inserted.isEmpty()) {
            return this;
        }
        if (size() - (long) removedCount + inserted.size() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too many line separators");
        }
        Split before = split(root, index);
        Split after = split(before.right(), removedCount);
        Node next = concat(concat(before.left(), inserted.root), after.right());
        return next == null ? EMPTY : new LineSeparatorSequence(next);
    }

    @Override
    public int size() {
        return size(root);
    }

    @Override
    public String get(int index) {
        Objects.checkIndex(index, size());
        Node node = root;
        while (!node.isRun()) {
            if (index < node.left.size) {
                node = node.left;
            } else {
                index -= node.left.size;
                node = node.right;
            }
        }
        return node.separator;
    }

    @Override
    public LineSeparatorSequence subList(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, size());
        if (fromIndex == toIndex) {
            return EMPTY;
        }
        if (fromIndex == 0 && toIndex == size()) {
            return this;
        }
        Node selected = split(split(root, toIndex).left(), fromIndex).right();
        return new LineSeparatorSequence(selected);
    }

    @Override
    public ListIterator<String> iterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<String> listIterator(int index) {
        Objects.checkIndex(index, size() + 1L);
        return new Cursor(index);
    }

    private final class Cursor implements ListIterator<String> {
        private final ArrayDeque<Step> path = new ArrayDeque<>();
        private Node run = root;
        private int offset;
        private int index;

        private Cursor(int index) {
            this.index = index;
            offset = index;
            while (run != null && !run.isRun()) {
                boolean left = offset < run.left.size;
                path.push(new Step(run, left));
                if (!left) {
                    offset -= run.left.size;
                }
                run = left ? run.left : run.right;
            }
        }

        @Override
        public boolean hasNext() {
            return index < size();
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (offset == run.size) {
                moveRun(true);
                offset = 0;
            }
            offset++;
            index++;
            return run.separator;
        }

        @Override
        public boolean hasPrevious() {
            return index > 0;
        }

        @Override
        public String previous() {
            if (!hasPrevious()) {
                throw new NoSuchElementException();
            }
            if (offset == 0) {
                moveRun(false);
                offset = run.size;
            }
            offset--;
            index--;
            return run.separator;
        }

        private void moveRun(boolean forward) {
            while (!path.isEmpty()) {
                Step step = path.pop();
                if (step.left() == forward) {
                    path.push(new Step(step.node(), !forward));
                    run = forward ? step.node().right : step.node().left;
                    while (!run.isRun()) {
                        path.push(new Step(run, forward));
                        run = forward ? run.left : run.right;
                    }
                    return;
                }
            }
            throw new NoSuchElementException();
        }

        @Override
        public int nextIndex() {
            return index;
        }

        @Override
        public int previousIndex() {
            return index - 1;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(String value) {
            throw new UnsupportedOperationException();
        }
    }

    private static Node build(List<Node> runs, int from, int to) {
        if (to - from == 1) {
            return runs.get(from);
        }
        int middle = from + (to - from) / 2;
        return Node.branch(build(runs, from, middle), build(runs, middle, to));
    }

    private static Split split(Node node, int index) {
        if (index == 0) {
            return new Split(null, node);
        }
        if (index == node.size) {
            return new Split(node, null);
        }
        if (node.isRun()) {
            return new Split(Node.run(node.separator, index), Node.run(node.separator, node.size - index));
        }
        if (index < node.left.size) {
            Split divided = split(node.left, index);
            return new Split(divided.left(), join(divided.right(), node.right));
        }
        Split divided = split(node.right, index - node.left.size);
        return new Split(join(node.left, divided.left()), divided.right());
    }

    /** Joins runs at the new boundary so repeated edits cannot fragment one run. */
    private static Node concat(Node left, Node right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        Node last = left;
        while (!last.isRun()) {
            last = last.right;
        }
        Node first = right;
        while (!first.isRun()) {
            first = first.left;
        }
        if (!last.separator.equals(first.separator)) {
            return join(left, right);
        }
        Node combined = Node.run(last.separator, Math.addExact(last.size, first.size));
        return join(join(split(left, left.size - last.size).left(), combined), split(right, first.size).right());
    }

    /** AVL join for trees whose boundary runs are already distinct. */
    private static Node join(Node left, Node right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        if (left.height > right.height + 1) {
            return balance(left.left, join(left.right, right));
        }
        if (right.height > left.height + 1) {
            return balance(join(left, right.left), right.right);
        }
        return Node.branch(left, right);
    }

    private static Node balance(Node left, Node right) {
        if (left.height > right.height + 1) {
            if (left.left.height >= left.right.height) {
                return Node.branch(left.left, Node.branch(left.right, right));
            }
            Node middle = left.right;
            return Node.branch(Node.branch(left.left, middle.left), Node.branch(middle.right, right));
        }
        if (right.height > left.height + 1) {
            if (right.right.height >= right.left.height) {
                return Node.branch(Node.branch(left, right.left), right.right);
            }
            Node middle = right.left;
            return Node.branch(Node.branch(left, middle.left), Node.branch(middle.right, right.right));
        }
        return Node.branch(left, right);
    }

    private static int size(Node node) {
        return node == null ? 0 : node.size;
    }

    private record Split(Node left, Node right) {}

    private record Step(Node node, boolean left) {}

    private static final class Node {
        private final Node left;
        private final Node right;
        private final String separator;
        private final int size;
        private final int height;

        private Node(Node left, Node right, String separator, int size, int height) {
            this.left = left;
            this.right = right;
            this.separator = separator;
            this.size = size;
            this.height = height;
        }

        private static Node run(String separator, int count) {
            return new Node(null, null, separator, count, 1);
        }

        private static Node branch(Node left, Node right) {
            return new Node(left, right, null, Math.addExact(left.size, right.size),
                Math.max(left.height, right.height) + 1);
        }

        private boolean isRun() {
            return separator != null;
        }
    }
}
