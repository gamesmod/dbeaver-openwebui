package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.Activator;
import io.dbtools.openwebui.ai.PromptFactory;
import io.dbtools.openwebui.ai.ResponseParser;
import io.dbtools.openwebui.api.ChatMessage;
import io.dbtools.openwebui.api.OpenWebUIClient;
import io.dbtools.openwebui.api.OpenWebUIException;
import io.dbtools.openwebui.context.DbContextResolver;
import io.dbtools.openwebui.context.EditorInserter;
import io.dbtools.openwebui.context.SqlEditorContext;
import io.dbtools.openwebui.prefs.Prefs;
import io.dbtools.openwebui.ui.ChatView;
import io.dbtools.openwebui.ui.GenerateSqlDialog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.util.List;

/**
 * Команда «Сгенерировать SQL…»: задача на естественном языке → SQL в редакторе.
 */
public class GenerateSqlHandler extends AbstractSqlHandler {

    @Override
    protected void run(Shell shell, SqlEditorContext ctx) {
        String note;
        if (ctx.container() == null) {
            note = "У редактора не выбрано подключение — структура БД не будет передана модели.";
        } else if (!Prefs.includeSchema()) {
            note = "Подключение: " + ctx.connectionName() + ". Передача структуры БД отключена в настройках.";
        } else {
            note = "Подключение: " + ctx.connectionName() + (ctx.isConnected() ? "" : " (откроется автоматически)")
                + ". Модель получит структуру таблиц (без данных).";
        }
        GenerateSqlDialog dialog = new GenerateSqlDialog(shell, note, ctx.isFromSelection());
        if (dialog.open() != Window.OK) {
            return;
        }
        String request = dialog.getRequest();
        String currentScript = dialog.isUseSelection() ? ctx.queryText() : null;
        OpenWebUIClient client = Prefs.client();
        Display display = shell.getDisplay();

        Job job = new Job("Open WebUI: генерация SQL") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Генерация SQL", IProgressMonitor.UNKNOWN);
                try {
                    DbContextResolver.Resolved resolved = dbContext(monitor, ctx, request, currentScript);
                    PromptFactory.DbContext db = resolved.db();
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    List<ChatMessage> messages =
                        PromptFactory.generateSql(Prefs.systemPrompt(), db, request, currentScript);
                    monitor.subTask("Ожидание ответа модели…");
                    String answer = client.chat(messages, null);
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    String sql = ResponseParser.extractSql(answer);
                    display.asyncExec(() -> deliver(ctx, request, messages, answer, sql, resolved));
                    return Status.OK_STATUS;
                } catch (OpenWebUIException e) {
                    display.asyncExec(() -> MessageDialog.openError(display.getActiveShell(), TITLE, e.getMessage()));
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    Activator.logError("Ошибка генерации SQL", e);
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Ошибка генерации SQL: " + e.getMessage(), e);
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** UI-поток: вставка SQL или, если запроса в ответе нет, показ ответа в чате. */
    private static void deliver(SqlEditorContext original, String request, List<ChatMessage> messages,
                                String answer, String sql, DbContextResolver.Resolved resolved) {
        showStatus(original, resolved);
        if (sql != null) {
            // Пересобираем контекст: пока шёл запрос, пользователь мог сдвинуть курсор или поменять текст.
            SqlEditorContext fresh = SqlEditorContext.from(original.editor());
            if (fresh != null && EditorInserter.insert(fresh, sql)) {
                return;
            }
        }
        ChatView view = ChatView.open();
        if (view != null) {
            view.showExchange("Генерация SQL: " + request + "\n" + resolved.note(), messages, answer, original);
        }
    }

    /** Итог передачи контекста — в строку состояния редактора. */
    private static void showStatus(SqlEditorContext ctx, DbContextResolver.Resolved resolved) {
        try {
            IStatusLineManager status = ctx.editor().getEditorSite().getActionBars().getStatusLineManager();
            if (resolved.ok()) {
                status.setErrorMessage(null);
                status.setMessage("Open WebUI · " + resolved.note());
            } else {
                status.setErrorMessage("Open WebUI · " + resolved.note());
            }
        } catch (RuntimeException e) {
            Activator.logInfo(resolved.note());
        }
    }
}
