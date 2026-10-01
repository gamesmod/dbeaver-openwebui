/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.Separator;
import org.eclipse.ui.actions.CompoundContributionItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Items of the «Мои промпты» drop-down in the AI chat toolbar and the AI main-toolbar menu.
 */
public class SavedPromptsMenu extends CompoundContributionItem {

    @Override
    protected IContributionItem[] getContributionItems() {
        List<IContributionItem> items = new ArrayList<>();
        for (PromptLibrary.SavedPrompt prompt : PromptLibrary.load()) {
            Action action = new Action(prompt.name.replace("&", "&&") + (prompt.send ? "" : "…")) {
                @Override
                public void run() {
                    PromptActions.usePrompt(prompt);
                }
            };
            action.setToolTipText(prompt.text.length() > 300 ? prompt.text.substring(0, 300) + "…" : prompt.text);
            items.add(new ActionContributionItem(action));
        }
        if (!items.isEmpty()) {
            items.add(new Separator());
        }
        items.add(new ActionContributionItem(new Action(PromptMessages.menu_manage) {
            @Override
            public void run() {
                PromptActions.openSettings();
            }
        }));
        return items.toArray(new IContributionItem[0]);
    }
}
