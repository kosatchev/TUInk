package dev.tuiframe.text;

/** Per-line aggregates: point edits are logarithmic; line splices copy only integers. */
final class LineLayoutIndex {
    private final boolean maximum;
    private int size;
    private int leafBase;
    private int[] tree;

    LineLayoutIndex(int[] values, boolean maximum) {
        this.maximum = maximum;
        rebuild(values);
    }

    int aggregate() {
        return tree[1];
    }

    int sumBefore(int limit) {
        int left = leafBase;
        int right = leafBase + Math.max(0, Math.min(limit, size));
        int total = 0;
        while (left < right) {
            if ((left & 1) != 0) {
                total = add(total, tree[left++]);
            }
            if ((right & 1) != 0) {
                total = add(total, tree[--right]);
            }
            left >>= 1;
            right >>= 1;
        }
        return total;
    }

    int lineAtOffset(int offset) {
        if (size == 0) {
            return 0;
        }
        int remaining = Math.max(0, Math.min(offset, Math.max(0, aggregate() - 1)));
        int node = 1;
        while (node < leafBase) {
            node *= 2;
            if (remaining >= tree[node]) {
                remaining -= tree[node];
                node++;
            }
        }
        return Math.min(size - 1, node - leafBase);
    }

    void replace(int firstLine, int removedCount, int[] replacement) {
        if (removedCount == replacement.length) {
            for (int line = 0; line < replacement.length; line++) {
                set(firstLine + line, replacement[line]);
            }
            return;
        }
        int[] values = new int[size - removedCount + replacement.length];
        System.arraycopy(tree, leafBase, values, 0, firstLine);
        System.arraycopy(replacement, 0, values, firstLine, replacement.length);
        System.arraycopy(tree, leafBase + firstLine + removedCount,
            values, firstLine + replacement.length, size - firstLine - removedCount);
        rebuild(values);
    }

    private void set(int line, int value) {
        int node = leafBase + line;
        tree[node] = value;
        while ((node >>= 1) > 0) {
            tree[node] = combine(tree[node * 2], tree[node * 2 + 1]);
        }
    }

    private void rebuild(int[] values) {
        size = values.length;
        leafBase = 1;
        while (leafBase < size) {
            leafBase *= 2;
        }
        tree = new int[leafBase * 2];
        System.arraycopy(values, 0, tree, leafBase, size);
        for (int node = leafBase - 1; node > 0; node--) {
            tree[node] = combine(tree[node * 2], tree[node * 2 + 1]);
        }
    }

    private int combine(int left, int right) {
        return maximum ? Math.max(left, right) : add(left, right);
    }

    private static int add(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) left + right);
    }
}
