package dev.tuink.tui;

import java.io.IOException;

/** Drives detached effects explicitly in workflow tests which do not own a window event loop. */
final class SaveWorkflowTestSupport {
    private SaveWorkflowTestSupport() {}

    static SaveWorkflow.Result finish(SaveWorkflow.Context context, SaveWorkflow.Result result) {
        while (result.pendingSave() != null) {
            SaveWorkflow.SaveRequest request = result.pendingSave();
            try {
                result = SaveWorkflow.complete(context, request, request.perform(), null);
            } catch (IOException failure) {
                result = SaveWorkflow.complete(context, request, null, failure);
            }
        }
        return result;
    }
}
