package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.ai.PromptFactory;
import io.dbtools.openwebui.context.SqlEditorContext;
import io.dbtools.openwebui.prefs.Prefs;
import io.dbtools.openwebui.ui.ChatView;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Общая логика «Объяснить / Оптимизировать / Найти ошибки»: берёт выделение или запрос под курсором
 * и выводит ответ модели в панель чата.
 */
public abstract class QueryActionHandler extends AbstractSqlHandler {

    private final PromptFactory.QueryAction action;

    protected QueryActionHandler(PromptFactory.QueryAction action) {
        this.action = action;
    }

    @Override
    protected void run(Shell shell, SqlEditorContext ctx) {
        String query = ctx.queryText();
        if (query == null || query.isBlank()) {
            MessageDialog.openInformation(shell, TITLE,
                "Выделите запрос или поставьте курсор внутрь запроса в SQL-редакторе.");
            return;
        }
        ChatView view = ChatView.open();
        if (view == null) {
            return;
        }
        String title = action.title() + (ctx.connectionName() != null ? " · " + ctx.connectionName() : "");
        view.startTask(title, query, ctx, (monitor, note) -> {
            var resolved = dbContext(monitor, ctx, query);
            note.accept(resolved.note());
            return PromptFactory.queryAction(Prefs.systemPrompt(), resolved.db(), action, query);
        });
    }
}
