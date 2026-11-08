package dev.tuink.tui;

import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.language.LanguageId;
import dev.tuink.tui.command.TuinkCommand;
import java.util.Objects;

/** Immutable command capabilities; construction reads cached UI/session state only. */
record ActiveSurface(PresentationId presentationId, Kind kind, Focus focus, ModalScope modalScope,
    boolean reading, boolean documentLocked, boolean windowIoBusy, boolean mouseAvailable,
    boolean syntaxDecorationsAvailable, boolean wrapLines, LanguageId language,
    boolean externalBound, boolean externalNotice, boolean frozenCanUndo, boolean frozenCanRedo) {
    enum Kind { SOURCE, LIVE_PREVIEW, FROZEN_PREVIEW }
    enum Focus { CONTENT, EDITABLE_TEXT, CONTROL }
    enum ModalScope { NONE, PRESENTATION, WINDOW }

    ActiveSurface {
        Objects.requireNonNull(presentationId); Objects.requireNonNull(kind);
        Objects.requireNonNull(focus); Objects.requireNonNull(modalScope); Objects.requireNonNull(language);
        reading = reading || kind != Kind.SOURCE;
    }

    ActiveSurface(PresentationId presentationId, Kind kind, Focus focus, ModalScope modalScope,
        boolean reading, boolean documentLocked, boolean windowIoBusy, boolean mouseAvailable,
        boolean syntaxDecorationsAvailable, boolean wrapLines, LanguageId language) {
        this(presentationId, kind, focus, modalScope, reading, documentLocked, windowIoBusy,
            mouseAvailable, syntaxDecorationsAvailable, wrapLines, language, false, false, false, false);
    }

    ActiveSurface withExternal(boolean bound, boolean notice, boolean undo, boolean redo) {
        return new ActiveSurface(presentationId, kind, focus, modalScope, reading, documentLocked,
            windowIoBusy, mouseAvailable, syntaxDecorationsAvailable, wrapLines, language, bound, notice, undo, redo);
    }

    ActiveSurface withRuntime(boolean mouse, boolean syntaxDecorations) {
        return new ActiveSurface(presentationId, kind, focus, modalScope, reading, documentLocked,
            windowIoBusy, mouse, syntaxDecorations, wrapLines, language, externalBound, externalNotice, frozenCanUndo, frozenCanRedo);
    }

    ActiveSurface withKind(Kind next) {
        return new ActiveSurface(presentationId, next, focus, modalScope, reading, documentLocked,
            windowIoBusy, mouseAvailable, syntaxDecorationsAvailable, wrapLines, language, externalBound, externalNotice, frozenCanUndo, frozenCanRedo);
    }

    ActiveSurface withFocus(Focus next) {
        return new ActiveSurface(presentationId, kind, next, modalScope, reading, documentLocked,
            windowIoBusy, mouseAvailable, syntaxDecorationsAvailable, wrapLines, language, externalBound, externalNotice, frozenCanUndo, frozenCanRedo);
    }

    boolean preview() { return kind != Kind.SOURCE; }
    boolean editableField() { return focus == Focus.EDITABLE_TEXT; }
    boolean documentEditable() { return !preview() && !reading && !documentLocked && modalScope == ModalScope.NONE; }
    boolean documentWritable() { return documentEditable() && focus == Focus.CONTENT; }

    record Availability(boolean enabled, String reason) {
        private static final Availability AVAILABLE = new Availability(true, "");
    }

    Availability availability(TuinkCommand command) {
        if (command == null) return Availability.AVAILABLE;
        if (!syntaxDecorationsAvailable && switch (command) {
            case VIEW_TOGGLE_SYNTAX, VIEW_TOGGLE_RAINBOW_BRACKETS, SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX -> true;
            default -> false;
        }) return new Availability(false, "Syntax styles are unavailable");
        boolean enabled = switch (command) {
            case EDIT_UNDO -> documentWritable() || frozenHistoryReady() && frozenCanUndo;
            case EDIT_REDO -> documentWritable() || frozenHistoryReady() && frozenCanRedo;
            case EDIT_TOGGLE_LINE_COMMENT -> documentWritable();
            case DOCUMENT_KEEP_EXTERNAL -> externalNotice && externalReady();
            case DOCUMENT_UPDATE_EXTERNAL -> externalBound && externalReady();
            case SEARCH_REPLACE, SEARCH_REPLACE_ALL -> documentEditable();
            case EDIT_COPY, EDIT_SELECT_ALL -> focus != Focus.CONTROL;
            case EDIT_INSERT_NEWLINE, EDIT_INSERT_TAB, EDIT_BACKSPACE, EDIT_DELETE_FORWARD ->
                focus == Focus.CONTROL || editableField() || documentWritable();
            case TAB_NEW, TAB_CLOSE, TAB_NEXT, TAB_PREVIOUS, TAB_ACTIVATE -> modalScope != ModalScope.WINDOW;
            case VIEW_TOGGLE_MARKDOWN_PREVIEW -> modalScope != ModalScope.WINDOW
                && (preview() || language == LanguageId.MARKDOWN);
            case DOCUMENT_FORMAT, DOCUMENT_REOPEN_ENCODING -> !preview();
            case VIEW_PREVIEW_COPY_SOURCE, VIEW_PREVIEW_COPY_TABLE_TSV,
                 VIEW_PREVIEW_FOLLOW_LINK, VIEW_PREVIEW_NEXT_HEADING, VIEW_PREVIEW_PREVIOUS_HEADING -> preview();
            case VIEW_TOGGLE_WORD_WRAP -> wrapLines;
            case DOCUMENT_OPEN, DOCUMENT_OPEN_ENCODING, DOCUMENT_RECOVER_DRAFTS, VIEW_CYCLE_THEME -> modalScope != ModalScope.WINDOW;
            // Opening Save As and starting a save have different live/frozen busy
            // policies; the existing subject workflow revalidates those actions.
            default -> !command.editsDocumentText() || editableField() || documentWritable();
        };
        return enabled ? Availability.AVAILABLE : new Availability(false, refusalReason(command));
    }

    boolean allows(TuinkCommand command) { return availability(command).enabled(); }
    String refusal(TuinkCommand command) { return availability(command).reason(); }

    private boolean externalReady() { return modalScope == ModalScope.NONE && !documentLocked && !windowIoBusy; }
    private boolean frozenHistoryReady() { return kind == Kind.FROZEN_PREVIEW && focus == Focus.CONTENT && externalReady(); }

    private String refusalReason(TuinkCommand command) {
        if (command == TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW && !preview() && language != LanguageId.MARKDOWN)
            return "Preview is available for Markdown files";
        if (command == TuinkCommand.VIEW_TOGGLE_WORD_WRAP) return "Enable line wrapping before changing word wrapping";
        if (documentLocked) return "A file operation is already in progress";
        if (command == TuinkCommand.SEARCH_REPLACE || command == TuinkCommand.SEARCH_REPLACE_ALL) {
            if (preview()) return "Markdown preview: return to source to replace text";
            if (reading) return "Reading mode: replace is disabled";
        }
        if (preview()) return "Markdown preview: return to source to edit";
        if (reading) return "Reading mode: editing is disabled";
        return "This action is unavailable for the focused control";
    }
}
