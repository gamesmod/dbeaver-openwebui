package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.context.DbContextResolver;
import io.dbtools.openwebui.context.SqlEditorContext;
import io.dbtools.openwebui.prefs.Prefs;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Базовый обработчик команд, работающих с активным SQL-редактором DBeaver.
 */
public abstract class AbstractSqlHandler extends AbstractHandler {

    static final String TITLE = "Open WebUI";
    static final String PREF_PAGE_ID = "io.dbtools.openwebui.prefs.main";

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        if (!ensureConfigured(shell)) {
            return null;
        }
        SqlEditorContext ctx = SqlEditorContext.from(HandlerUtil.getActiveEditor(event));
        if (ctx == null) {
            MessageDialog.openInformation(shell, TITLE, "Откройте SQL-редактор DBeaver и повторите команду.");
            return null;
        }
        run(shell, ctx);
        return null;
    }

    protected abstract void run(Shell shell, SqlEditorContext ctx);

    /** Если плагин не настроен — предлагает открыть страницу настроек. */
    static boolean ensureConfigured(Shell shell) {
        if (Prefs.isConfigured()) {
            return true;
        }
        if (MessageDialog.openQuestion(shell, TITLE,
            "Не указаны адрес Open WebUI или модель. Открыть настройки?")) {
            PreferencesUtil.createPreferenceDialogOn(shell, PREF_PAGE_ID, null, null).open();
        }
        return Prefs.isConfigured();
    }

    /**
     * Контекст подключения для промпта: при необходимости подключается к БД. Выполняется в фоне.
     *
     * @param hintTexts тексты (задача, запрос), по словам которых выбираются наиболее релевантные таблицы
     */
    static DbContextResolver.Resolved dbContext(IProgressMonitor monitor, SqlEditorContext ctx, String... hintTexts) {
        return DbContextResolver.resolve(monitor, ctx.container(), () -> ctx.editor().getExecutionContext(),
            Prefs.includeSchema(), hintTexts);
    }
}
