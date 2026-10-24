package dev.tuink.editor.document;

import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable UTF-16 text held in a balanced tree of small string blocks. */
public final class ImmutableText implements CharSequence {
    public static final int BLOCK_SIZE = 2048;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicLong COPIED = new AtomicLong();
    private static final AtomicLong MATERIALIZED = new AtomicLong();
    private static final AtomicLong NODES = new AtomicLong();
    private static final int MAX_SHARED_TEXT_LENGTH = 256;
    private static final Map<String, ImmutableText> SHORT_TEXTS = new IdentityHashMap<>();
    private static final ImmutableText EMPTY = new ImmutableText(null, null, "");
    private final Node root;
    private final long identity = NEXT_ID.incrementAndGet();
    private final Edit edit;
    private volatile String materialized;
    private volatile LocatedLeaf lastLeaf;

    private ImmutableText(Node root, Edit edit, String materialized) {
        this.root = root;
        this.edit = edit;
        this.materialized = materialized;
    }

    public static ImmutableText of(CharSequence source) {
        Objects.requireNonNull(source, "source");
        if (source instanceof ImmutableText text) return text;
        String value = source instanceof String string ? string : source.toString();
        if (value == "") return EMPTY;
        if (value.length() <= MAX_SHARED_TEXT_LENGTH) {
            synchronized (SHORT_TEXTS) {
                ImmutableText shared = SHORT_TEXTS.get(value);
                if (shared != null) return shared;
                ImmutableText text = buildText(value);
                if (SHORT_TEXTS.size() == 256) SHORT_TEXTS.clear();
                SHORT_TEXTS.put(value, text);
                return text;
            }
        }
        return buildText(value);
    }

    private static ImmutableText buildText(String value) {
        Allocation allocation = new Allocation();
        Node root = build(value, 0, value.length(), allocation);
        allocation.record();
        return new ImmutableText(root, null, value);
    }

    public long identity() { return identity; }
    public Edit edit() { return edit; }
    public boolean simpleAscii() { return root == null || root.ascii; }
    /** Cached validation summaries are maintained while building or editing blocks. */
    public boolean isWellFormedUtf16() { return root == null || root.unpairedSurrogates == 0; }
    public boolean containsLineSeparator() { return root != null && root.lineSeparator; }

    /** A persistent value cannot exceed the index range of a Java CharSequence. */
    public static final class CapacityExceededException extends IllegalArgumentException {
        public CapacityExceededException() { super("Text exceeds the supported UTF-16 length"); }
    }
    public String cachedString() { return materialized; }
    @Override public int length() { return length(root); }
    @Override public boolean isEmpty() { return root == null; }

    @Override public char charAt(int index) {
        Objects.checkIndex(index, length());
        LocatedLeaf cached = lastLeaf;
        if (cached != null && index >= cached.start && index - cached.start < cached.leaf.length) {
            return cached.leaf.value.charAt(index - cached.start);
        }
        Node node = root;
        int start = 0;
        while (node instanceof Branch branch) {
            if (index - start < branch.left.length) node = branch.left;
            else {
                start += branch.left.length;
                node = branch.right;
            }
        }
        Leaf leaf = (Leaf) node;
        lastLeaf = new LocatedLeaf(leaf, start);
        return leaf.value.charAt(index - start);
    }

    public int codePointAt(int index) { return Character.codePointAt(this, index); }
    public int codePointBefore(int index) { return Character.codePointBefore(this, index); }
    public int codePointCount(int start, int end) { return Character.codePointCount(this, start, end); }
    public int offsetByCodePoints(int offset, int count) { return Character.offsetByCodePoints(this, offset, count); }

    @Override public ImmutableText subSequence(int start, int end) {
        Objects.checkFromToIndex(start, end, length());
        if (start == 0 && end == length()) return this;
        Allocation allocation = new Allocation();
        Split prefix = split(root, start, allocation);
        Split suffix = split(prefix.right, end - start, allocation);
        allocation.record();
        return new ImmutableText(suffix.left, null, null);
    }

    public ImmutableText replace(int start, int end, CharSequence inserted) {
        Objects.checkFromToIndex(start, end, length());
        Objects.requireNonNull(inserted, "inserted");
        if (start == end && inserted.length() == 0) return this;
        long resultLength = (long) length() - (end - start) + inserted.length();
        if (resultLength > Integer.MAX_VALUE - 8L) throw new CapacityExceededException();
        Allocation allocation = new Allocation();
        Node insertion = inserted instanceof ImmutableText text ? text.root
            : build(inserted.toString(), 0, inserted.length(), allocation);
        Split prefix = split(root, start, allocation);
        Split suffix = split(prefix.right, end - start, allocation);
        Node result = join(join(prefix.left, insertion, allocation), suffix.right, allocation);
        allocation.record();
        String existingString = start == 0 && end == length()
            ? inserted instanceof String string ? string
                : inserted instanceof ImmutableText text ? text.cachedString() : null
            : null;
        return new ImmutableText(result, new Edit(identity, start, end - start, inserted.length(),
            allocation.copied, allocation.nodes), existingString);
    }

    public String sliceString(int start, int end) {
        Objects.checkFromToIndex(start, end, length());
        if (start == end) return "";
        if (start == 0 && end == length()) return toString();
        String cached = materialized;
        if (cached != null) {
            String slice = cached.substring(start, end);
            MATERIALIZED.addAndGet(end - start);
            return slice;
        }
        char[] result = new char[end - start];
        copy(root, start, end, result, 0);
        MATERIALIZED.addAndGet(result.length);
        return new String(result);
    }

    public String substring(int start, int end) { return sliceString(start, end); }
    public String substring(int start) { return sliceString(start, length()); }

    public void getChars(int start, int end, char[] destination, int destinationStart) {
        Objects.checkFromToIndex(start, end, length());
        Objects.checkFromIndexSize(destinationStart, end - start, destination.length);
        copy(root, start, end, destination, destinationStart);
    }

    public int indexOf(char character, int from) {
        for (int index = Math.max(0, from); index < length(); index++) if (charAt(index) == character) return index;
        return -1;
    }

    public int indexOf(char character) { return indexOf(character, 0); }

    public boolean startsWith(String prefix, int from) {
        if (from < 0 || prefix.length() > length() - from) return false;
        for (int index = 0; index < prefix.length(); index++) if (charAt(from + index) != prefix.charAt(index)) return false;
        return true;
    }

    public boolean startsWith(String prefix) { return startsWith(prefix, 0); }

    public boolean isBlank() {
        for (int index = 0; index < length();) {
            int codePoint = codePointAt(index);
            if (!Character.isWhitespace(codePoint)) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }

    public boolean contentEquals(CharSequence other) {
        if (other == this) return true;
        if (other == null || length() != other.length()) return false;
        if (other instanceof ImmutableText text && (root == text.root || hashCode() != text.hashCode())) return root == text.root;
        for (int index = 0; index < length(); index++) if (charAt(index) != other.charAt(index)) return false;
        return true;
    }

    @Override public boolean equals(Object other) { return other instanceof ImmutableText text && contentEquals(text); }
    @Override public int hashCode() { return root == null ? 0 : root.hash; }

    @Override public String toString() {
        String value = materialized;
        if (value != null) return value;
        char[] result = new char[length()];
        copy(root, 0, result.length, result, 0);
        value = new String(result);
        MATERIALIZED.addAndGet(result.length);
        materialized = value;
        return value;
    }

    /** Latest edit coordinates and work; it retains no preceding text version. */
    public record Edit(long sourceIdentity, int start, int removedLength, int insertedLength,
                       long copiedCodeUnits, int allocatedNodes) {}

    /** Cumulative actual storage work, useful for allocation regressions without wall-clock deadlines. */
    public record Work(long copiedCodeUnits, long materializedCodeUnits, long allocatedNodes) {}
    public static Work work() { return new Work(COPIED.get(), MATERIALIZED.get(), NODES.get()); }

    public int treeHeight() { return height(root); }
    public int retainedNodes() { return count(root); }
    private static int count(Node node) {
        return node == null ? 0 : node instanceof Branch branch ? 1 + count(branch.left) + count(branch.right) : 1;
    }

    private static Node build(String value, int start, int end, Allocation allocation) {
        if (start == end) return null;
        if (end - start <= BLOCK_SIZE) {
            String block = value.substring(start, end);
            if (block != value) allocation.copied += block.length();
            return leaf(block, allocation);
        }
        int middle = start + (end - start) / 2;
        return branch(build(value, start, middle, allocation), build(value, middle, end, allocation), allocation);
    }

    private static Split split(Node node, int offset, Allocation allocation) {
        if (offset == 0) return new Split(null, node);
        if (offset == length(node)) return new Split(node, null);
        if (node instanceof Leaf leaf) {
            allocation.copied += leaf.length;
            return new Split(leaf(leaf.value.substring(0, offset), allocation),
                leaf(leaf.value.substring(offset), allocation));
        }
        Branch branch = (Branch) node;
        if (offset < branch.left.length) {
            Split divided = split(branch.left, offset, allocation);
            return new Split(divided.left, join(divided.right, branch.right, allocation));
        }
        Split divided = split(branch.right, offset - branch.left.length, allocation);
        return new Split(join(branch.left, divided.left, allocation), divided.right);
    }

    private static Node join(Node left, Node right, Allocation allocation) {
        if (left == null) return right;
        if (right == null) return left;
        if (left instanceof Leaf a && right instanceof Leaf b && left.length + right.length <= BLOCK_SIZE) {
            allocation.copied += left.length + right.length;
            return leaf(a.value + b.value, allocation);
        }
        if (height(left) > height(right) + 1) {
            Branch branch = (Branch) left;
            return balance(branch.left, join(branch.right, right, allocation), allocation);
        }
        if (height(right) > height(left) + 1) {
            Branch branch = (Branch) right;
            return balance(join(left, branch.left, allocation), branch.right, allocation);
        }
        return branch(left, right, allocation);
    }

    private static Node balance(Node left, Node right, Allocation allocation) {
        if (height(left) > height(right) + 1) {
            Branch pivot = (Branch) left;
            if (height(pivot.left) >= height(pivot.right)) {
                return branch(pivot.left, branch(pivot.right, right, allocation), allocation);
            }
            Branch middle = (Branch) pivot.right;
            return branch(branch(pivot.left, middle.left, allocation), branch(middle.right, right, allocation), allocation);
        }
        if (height(right) > height(left) + 1) {
            Branch pivot = (Branch) right;
            if (height(pivot.right) >= height(pivot.left)) {
                return branch(branch(left, pivot.left, allocation), pivot.right, allocation);
            }
            Branch middle = (Branch) pivot.left;
            return branch(branch(left, middle.left, allocation), branch(middle.right, pivot.right, allocation), allocation);
        }
        return branch(left, right, allocation);
    }

    private static Leaf leaf(String value, Allocation allocation) {
        allocation.nodes++;
        return new Leaf(value);
    }

    private static Branch branch(Node left, Node right, Allocation allocation) {
        allocation.nodes++;
        return new Branch(left, right);
    }

    private static int length(Node node) { return node == null ? 0 : node.length; }
    private static int height(Node node) { return node == null ? 0 : node.height; }

    private static void copy(Node node, int start, int end, char[] target, int targetStart) {
        if (start == end) return;
        if (node instanceof Leaf leaf) {
            leaf.value.getChars(start, end, target, targetStart);
            return;
        }
        Branch branch = (Branch) node;
        int leftLength = branch.left.length;
        if (start < leftLength) copy(branch.left, start, Math.min(end, leftLength), target, targetStart);
        if (end > leftLength) copy(branch.right, Math.max(0, start - leftLength), end - leftLength,
            target, targetStart + Math.max(0, leftLength - start));
    }

    private abstract static class Node {
        final int length;
        final int height;
        final int hash;
        final int power;
        final boolean ascii;
        final int unpairedSurrogates;
        final boolean startsWithLowSurrogate;
        final boolean endsWithHighSurrogate;
        final boolean lineSeparator;

        Node(int length, int height, int hash, int power, boolean ascii,
             int unpairedSurrogates, boolean startsWithLowSurrogate,
             boolean endsWithHighSurrogate, boolean lineSeparator) {
            this.length = length;
            this.height = height;
            this.hash = hash;
            this.power = power;
            this.ascii = ascii;
            this.unpairedSurrogates = unpairedSurrogates;
            this.startsWithLowSurrogate = startsWithLowSurrogate;
            this.endsWithHighSurrogate = endsWithHighSurrogate;
            this.lineSeparator = lineSeparator;
        }
    }

    private static final class Leaf extends Node {
        final String value;
        Leaf(String value) {
            super(value.length(), 1, value.hashCode(), power(value.length()), ascii(value),
                unpairedSurrogates(value), Character.isLowSurrogate(value.charAt(0)),
                Character.isHighSurrogate(value.charAt(value.length() - 1)),
                value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0);
            this.value = value;
        }
        private static int unpairedSurrogates(String text) {
            int count = 0;
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isHighSurrogate(current) && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) index++;
                else if (Character.isSurrogate(current)) count++;
            }
            return count;
        }
        private static boolean ascii(String text) {
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (ch < 0x20 || ch > 0x7e) return false;
            }
            return true;
        }
        private static int power(int length) {
            int result = 1;
            int factor = 31;
            for (int count = length; count > 0; count >>>= 1) {
                if ((count & 1) != 0) result *= factor;
                factor *= factor;
            }
            return result;
        }
    }

    private static final class Branch extends Node {
        final Node left;
        final Node right;
        Branch(Node left, Node right) {
            super(Math.addExact(left.length, right.length), 1 + Math.max(left.height, right.height),
                left.hash * right.power + right.hash, left.power * right.power, left.ascii && right.ascii,
                left.unpairedSurrogates + right.unpairedSurrogates
                    - (left.endsWithHighSurrogate && right.startsWithLowSurrogate ? 2 : 0),
                left.startsWithLowSurrogate, right.endsWithHighSurrogate,
                left.lineSeparator || right.lineSeparator);
            this.left = left;
            this.right = right;
        }
    }

    private record Split(Node left, Node right) {}
    private record LocatedLeaf(Leaf leaf, int start) {}
    private static final class Allocation {
        long copied;
        int nodes;
        void record() { COPIED.addAndGet(copied); NODES.addAndGet(nodes); }
    }
}
