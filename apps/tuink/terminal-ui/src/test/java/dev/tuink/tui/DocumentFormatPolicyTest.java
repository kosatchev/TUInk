package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.PresentationId;
import org.junit.jupiter.api.Test;

class DocumentFormatPolicyTest {
    @Test void openingAnotherFileIsIndependentOfPresentationAndReadingPreference() {
        for (var kind : ActiveSurface.Kind.values()) {
            for (boolean reading : new boolean[] {false, true}) {
                var surface = surface(kind, reading, false, false, ActiveSurface.ModalScope.PRESENTATION);
                assertTrue(DocumentFormatPolicy.apply(DocumentFormatDialogView.Purpose.OPEN, surface).allowed());
                assertEquals(kind == ActiveSurface.Kind.SOURCE && !reading,
                    DocumentFormatPolicy.apply(DocumentFormatDialogView.Purpose.FORMAT, surface).allowed());
                assertEquals(kind == ActiveSurface.Kind.SOURCE,
                    DocumentFormatPolicy.apply(DocumentFormatDialogView.Purpose.REOPEN, surface).allowed());
            }
        }
    }

    @Test void locksAndApplicationDialogsRefuseBeforeAFormatPurposeCanExecute() {
        for (var kind : ActiveSurface.Kind.values()) {
            for (var purpose : DocumentFormatDialogView.Purpose.values()) {
                for (var surface : new ActiveSurface[] {
                    surface(kind, false, false, true, ActiveSurface.ModalScope.PRESENTATION),
                    surface(kind, false, true, false, ActiveSurface.ModalScope.PRESENTATION),
                    surface(kind, false, false, false, ActiveSurface.ModalScope.WINDOW)
                }) {
                    var decision = DocumentFormatPolicy.apply(purpose, surface);
                    assertFalse(decision.allowed());
                    assertNotNull(decision.refusal());
                    assertFalse(decision.refusal().isBlank());
                }
            }
        }
    }

    private static ActiveSurface surface(ActiveSurface.Kind kind, boolean reading, boolean locked,
        boolean ioBusy, ActiveSurface.ModalScope scope) {
        return new ActiveSurface(PresentationId.create(), kind, ActiveSurface.Focus.CONTROL,
            scope, reading, locked, ioBusy, true, true, false, dev.tuink.editor.language.LanguageId.MARKDOWN);
    }
}
