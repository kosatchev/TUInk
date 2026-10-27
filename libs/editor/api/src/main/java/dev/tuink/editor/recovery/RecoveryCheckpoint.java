package dev.tuink.editor.recovery;

/**
 * Immutable owner capture whose text may be materialized on a worker. Creating a
 * checkpoint must not retain a mutable document or view; frontend layout state is
 * captured separately by its host.
 */
@FunctionalInterface
public interface RecoveryCheckpoint {
    RecoveryDocument materialize();
}
