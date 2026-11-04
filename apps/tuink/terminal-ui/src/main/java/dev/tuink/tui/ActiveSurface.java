package dev.tuink.tui;

import dev.tuink.editor.document.PresentationId;
import dev.tuink.tui.command.TuinkCommand;
import java.util.Objects;

/** Immutable command capabilities; construction reads cached UI/session state only. */
record ActiveSurface(PresentationId presentationId, Kind kind, Focus focus, ModalScope modalScope,
    boolean reading, boolean documentLocked, boolean windowIoBusy, boolean mouseAvailable,
    boolean syntaxAvailable, boolean wrapLines) {
    enum Kind { SOURCE, LIVE_PREVIEW, FROZEN_PREVIEW }
    enum Focus { CONTENT, EDITABLE_TEXT, CONTROL }
    enum ModalScope { NONE, PRESENTATION, WINDOW }

    ActiveSurface {
        Objects.requireNonNull(presentationId); Objects.requireNonNull(kind);
        Objects.requireNonNull(focus); Objects.requireNonNull(modalScope);
        reading = reading || kind != Kind.SOURCE;
    }

    ActiveSurface withRuntime(boolean mouse, boolean syntax) {
        return new ActiveSurface(presentationId, kind, focus, modalScope, reading, documentLocked,
            windowIoBusy, mouse, syntax, wrapLines);
    }

    ActiveSurface withKind(Kind next) {
        return new ActiveSurface(presentationId, next, focus, modalScope, reading, documentLocked,
            windowIoBusy, mouseAvailable, syntaxAvailable, wrapLines);
    }

    ActiveSurface withFocus(Focus next) {
        return new ActiveSurface(presentationId, kind, next, modalScope, reading, documentLocked,
            windowIoBusy, mouseAvailable, syntaxAvailable, wrapLines);
    }

    boolean preview() { return kind != Kind.SOURCE; }
    boolean editableField() { return focus == Focus.EDITABLE_TEXT; }
    boolean documentEditable() { return !preview() && !reading && !documentLocked && modalScope == ModalScope.NONE; }
    boolean documentWritable() { return documentEditable() && focus == Focus.CONTENT; }

    boolean allows(TuinkCommand command) {
        if (command == null) return true;
        return switch (command) {
            case EDIT_UNDO, EDIT_REDO, EDIT_TOGGLE_LINE_COMMENT -> documentWritable();
            case SEARCH_REPLACE, SEARCH_REPLACE_ALL -> documentEditable();
            case EDIT_COPY, EDIT_SELECT_ALL -> focus != Focus.CONTROL;
            case EDIT_INSERT_NEWLINE, EDIT_INSERT_TAB, EDIT_BACKSPACE, EDIT_DELETE_FORWARD ->
                focus == Focus.CONTROL || editableField() || documentWritable();
            case TAB_NEW, TAB_CLOSE, TAB_NEXT, TAB_PREVIOUS, TAB_ACTIVATE, VIEW_TOGGLE_MARKDOWN_PREVIEW ->
                modalScope != ModalScope.WINDOW;
            case DOCUMENT_FORMAT, DOCUMENT_REOPEN_ENCODING -> !preview();
            case VIEW_PREVIEW_COPY_SOURCE, VIEW_PREVIEW_COPY_TABLE_TSV, VIEW_PREVIEW_REVEAL_SOURCE,
                 VIEW_PREVIEW_FOLLOW_LINK, VIEW_PREVIEW_NEXT_HEADING, VIEW_PREVIEW_PREVIOUS_HEADING -> preview();
            case VIEW_TOGGLE_WORD_WRAP -> wrapLines;
            case VIEW_TOGGLE_SYNTAX -> syntaxAvailable;
            case DOCUMENT_OPEN, DOCUMENT_OPEN_ENCODING, DOCUMENT_RECOVER_DRAFTS, VIEW_CYCLE_THEME -> modalScope != ModalScope.WINDOW;
            // Opening Save As and starting a save have different live/frozen busy
            // policies; the existing subject workflow revalidates those actions.
            default -> !command.editsDocumentText() || editableField() || documentWritable();
        };
    }

    String refusal(TuinkCommand command) {
        if (command == TuinkCommand.VIEW_TOGGLE_WORD_WRAP) return "Enable line wrapping before changing word wrapping";
        if (command == TuinkCommand.VIEW_TOGGLE_SYNTAX) return "Syntax highlighting is unavailable in the current theme";
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
