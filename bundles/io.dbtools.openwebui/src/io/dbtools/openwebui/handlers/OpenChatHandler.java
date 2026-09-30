package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.ui.ChatView;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/** Команда «Открыть чат Open WebUI». */
public class OpenChatHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ChatView.open();
        return null;
    }
}
