/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.AIChatView;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;
import org.jkiss.dbeaver.ui.ai.chat.controls.PromptComposite;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Inserts a saved prompt into the AI chat (or sends it right away).
 */
public final class PromptActions {

    private static final Log log = Log.getLog(PromptActions.class);
    public static final String PREF_PAGE_ID = "dbeaver.openwebui.preferences.prompts";

    private PromptActions() {
    }

    public static void usePrompt(@NotNull PromptLibrary.SavedPrompt prompt) {
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        String text = PromptLibrary.expand(prompt.text, getEditorSelection(page));
        try {
            AIChatView view = AIChatView.show();
            AIChatControl chat = view == null ? null : view.getChat();
            if (chat == null) {
                return;
            }
            PromptComposite promptComposite = findPromptComposite(chat);
            if (promptComposite == null) {
                // Unknown layout of the chat in a newer DBeaver: send directly
                chat.submitPrompt(text);
                return;
            }
            promptComposite.setPromptText(text);
            if (prompt.send && !chat.isBusy()) {
                promptComposite.submitPrompt();
            } else {
                promptComposite.setFocusOnPrompt();
            }
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Saved prompt", "Can't open AI chat", e);
        }
    }

    public static void openSettings() {
        PreferencesUtil.createPreferenceDialogOn(UIUtils.getActiveWorkbenchShell(), PREF_PAGE_ID, null, null).open();
    }

    @Nullable
    private static String getEditorSelection(@Nullable IWorkbenchPage page) {
        if (page == null) {
            return null;
        }
        IEditorPart editor = page.getActiveEditor();
        if (editor instanceof SQLEditor sqlEditor) {
            ISelection selection = sqlEditor.getSelectionProvider().getSelection();
            if (selection instanceof ITextSelection ts && ts.getText() != null && !ts.getText().isBlank()) {
                return ts.getText();
            }
            // Nothing selected: the query under the cursor
            try {
                var query = sqlEditor.extractActiveQuery();
                return query == null ? null : query.getText();
            } catch (Throwable e) {
                log.debug("Can't extract active query", e);
            }
        }
        return null;
    }

    @Nullable
    private static PromptComposite findPromptComposite(@Nullable Control control) {
        if (control instanceof PromptComposite pc) {
            return pc;
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                PromptComposite found = findPromptComposite(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
