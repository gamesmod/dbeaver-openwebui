/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/** Main part of the «Мои промпты» toolbar button: opens the settings page. */
public class OpenPromptsHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) {
        PromptActions.openSettings();
        return null;
    }
}
