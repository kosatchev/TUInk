package dev.tuink.render.surface;

/** Semantic boundary policy, independent of display rows and decorative glyphs. */
@FunctionalInterface
public interface TextSurfaceContent<P> {
    /** Clamp to content and a complete grapheme boundary; never split a surrogate or cluster. */
    P boundary(P position, boolean ceil);
}
