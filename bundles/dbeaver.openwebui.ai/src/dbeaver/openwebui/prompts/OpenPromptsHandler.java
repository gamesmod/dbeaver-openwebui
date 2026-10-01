/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ToolItem;

/**
 * «Мои промпты» button: a click on the icon shows the same list as the arrow next to it
 * (before 2.2.0 the icon opened the settings page, which was confusing).
 */
public class OpenPromptsHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) {
        if (event.getTrigger() instanceof Event e && e.widget instanceof ToolItem item && !item.isDisposed()) {
            MenuManager manager = new MenuManager();
            manager.add(new SavedPromptsMenu());
            Menu menu = manager.createContextMenu(item.getParent());
            Rectangle bounds = item.getBounds();
            Point location = item.getParent().toDisplay(bounds.x, bounds.y + bounds.height);
            menu.setLocation(location);
            menu.addListener(org.eclipse.swt.SWT.Hide, ev -> item.getDisplay().asyncExec(manager::dispose));
            menu.setVisible(true);
            return null;
        }
        PromptActions.openSettings();
        return null;
    }
}
