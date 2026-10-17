package dev.tuink.editor.language;

/** Presentation-independent document language identity; consumers own language policies. */
public enum LanguageId {
    UNKNOWN, PLAIN_TEXT, CSV,
    JAVA, C, CPP, PYTHON, MARKDOWN, JAVASCRIPT, TYPESCRIPT, GROOVY,
    SHELL, SQL, GO, RUST, PHP, RUBY, PERL, KOTLIN, PROTO,
    CSHARP, SWIFT, SCALA, DART,
    HTML, XML, CSS, SCSS, DIFF,
    JSON, YAML, TOML, INI, CONFIG, PROPERTIES,
    DOCKERFILE, ENV, GIT_IGNORE, MAKEFILE, EDITORCONFIG, GIT_COMMIT
}
