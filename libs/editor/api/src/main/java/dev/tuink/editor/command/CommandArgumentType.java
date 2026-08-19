package dev.tuink.editor.command;

/** Serialization types supported by the portable command invocation contract. */
public enum CommandArgumentType {
    STRING {
        @Override
        public boolean accepts(String value) {
            return value != null;
        }
    },
    INTEGER {
        @Override
        public boolean accepts(String value) {
            if (value == null) {
                return false;
            }
            try {
                Integer.parseInt(value);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    },
    BOOLEAN {
        @Override
        public boolean accepts(String value) {
            return "true".equals(value) || "false".equals(value);
        }
    };

    public abstract boolean accepts(String value);
}
