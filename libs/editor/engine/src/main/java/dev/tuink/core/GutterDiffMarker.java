package dev.tuink.core;

/**
 * Resolved gutter marker for a rendered line.
 * `historical=true` means marker comes from "open -> current" diff layer.
 */
public record GutterDiffMarker(GutterDiffFlag flag, boolean historical) {
    public static final GutterDiffMarker NONE = new GutterDiffMarker(GutterDiffFlag.NONE, false);

    public GutterDiffMarker {
        flag = flag == null ? GutterDiffFlag.NONE : flag;
    }
}
