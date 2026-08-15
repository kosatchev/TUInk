package dev.tuink.core;

/** Explicit authorization for the target-name part of a save operation. */
public enum SaveDisposition {
    /** Fail if the target name exists at commit time. */
    CREATE_NEW,
    /** Replace the target name after the caller has explicitly authorized overwrite. */
    REPLACE_EXISTING
}
