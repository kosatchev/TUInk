package dev.tuink.render;

import dev.tuiframe.render.PreparedStyledRow;

/**
 * Opaque identity for the stable, prepared portion of a line's styles.
 *
 * <p>Render-plan consumers may retain and compare this value, but only render-plan producers in
 * this package can create one. A terminal adapter can project the identity without retaining its producer.</p>
 */
public final class BaseStyleIdentity {
    private final long ownerId;
    private final long modelId;
    private final long generation;
    private final long modelRevision;
    private final int lineIndex;
    private final int sliceStart;
    private final int sliceLength;
    private final int hashCode;

    private BaseStyleIdentity(
        long ownerId,
        long modelId,
        long generation,
        long modelRevision,
        int lineIndex,
        int sliceStart,
        int sliceLength
    ) {
        this.ownerId = ownerId;
        this.modelId = modelId;
        this.generation = generation;
        this.modelRevision = modelRevision;
        this.lineIndex = lineIndex;
        this.sliceStart = sliceStart;
        this.sliceLength = sliceLength;
        int hash = Long.hashCode(ownerId);
        hash = 31 * hash + Long.hashCode(modelId);
        hash = 31 * hash + Long.hashCode(generation);
        hash = 31 * hash + Long.hashCode(modelRevision);
        hash = 31 * hash + lineIndex;
        hash = 31 * hash + sliceStart;
        this.hashCode = 31 * hash + sliceLength;
    }

    static BaseStyleIdentity of(
        long ownerId,
        long modelId,
        long generation,
        long modelRevision,
        int lineIndex,
        int sliceStart,
        int sliceLength
    ) {
        return new BaseStyleIdentity(
            ownerId,
            modelId,
            generation,
            modelRevision,
            lineIndex,
            sliceStart,
            sliceLength
        );
    }

    /** Exact prepared-row identity; no hashing or application-owner references are involved. */
    public PreparedStyledRow.Key preparedRowKey(int tabWidth, int tabPhase) {
        return new PreparedStyledRow.Key(ownerId, modelId, modelRevision, generation,
            lineIndex, sliceStart, sliceLength, tabWidth, tabPhase);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseStyleIdentity identity)) {
            return false;
        }
        return ownerId == identity.ownerId
            && modelId == identity.modelId
            && generation == identity.generation
            && modelRevision == identity.modelRevision
            && lineIndex == identity.lineIndex
            && sliceStart == identity.sliceStart
            && sliceLength == identity.sliceLength;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
