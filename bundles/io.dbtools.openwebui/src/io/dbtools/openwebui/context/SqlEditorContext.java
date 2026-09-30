package io.dbtools.openwebui.context;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Снимок состояния SQL-редактора DBeaver, сделанный в UI-потоке.
 * Дальше может использоваться в фоновых заданиях.
 */
public final class SqlEditorContext {

    private final SQLEditor editor;
    private final DBPDataSourceContainer container;
    private final DBCExecutionContext executionContext;
    private final String dialectName;
    private final String productName;
    private final String defaultSchemaName;

    /** Текст для анализа: выделение, а если его нет — текущий запрос под курсором. */
    private final String queryText;
    private final int queryOffset;
    private final int queryLength;
    private final boolean fromSelection;
    private final int caretOffset;

    private SqlEditorContext(SQLEditor editor) {
        this.editor = editor;
        this.container = editor.getDataSourceContainer();
        this.executionContext = editor.getExecutionContext();

        SQLDialect dialect = editor.getSQLDialect();
        this.dialectName = dialect == null ? null : dialect.getDialectName();

        String product = null;
        DBPDataSource ds = container == null ? null : container.getDataSource();
        if (ds != null) {
            DBPDataSourceInfo info = ds.getInfo();
            if (info != null && info.getDatabaseProductName() != null) {
                product = info.getDatabaseProductName()
                    + (info.getDatabaseProductVersion() != null ? " " + info.getDatabaseProductVersion() : "");
            }
        }
        if (product == null && container != null && container.getDriver() != null) {
            product = container.getDriver().getName();
        }
        this.productName = product;
        this.defaultSchemaName = resolveDefaultSchemaName(executionContext);

        ISelection sel = editor.getSelectionProvider() == null ? null : editor.getSelectionProvider().getSelection();
        ITextSelection textSel = sel instanceof ITextSelection ts ? ts : null;
        this.caretOffset = textSel == null ? 0 : textSel.getOffset();

        if (textSel != null && textSel.getLength() > 0 && textSel.getText() != null && !textSel.getText().isBlank()) {
            queryText = textSel.getText();
            queryOffset = textSel.getOffset();
            queryLength = textSel.getLength();
            fromSelection = true;
        } else {
            SQLScriptElement element = editor.extractActiveQuery();
            if (element != null) {
                queryText = element.getOriginalText() != null ? element.getOriginalText() : element.getText();
                queryOffset = element.getOffset();
                queryLength = element.getLength();
            } else {
                queryText = null;
                queryOffset = caretOffset;
                queryLength = 0;
            }
            fromSelection = false;
        }
    }

    /** Возвращает контекст активного SQL-редактора или null, если активен другой редактор. */
    public static SqlEditorContext from(IEditorPart part) {
        SQLEditor sqlEditor = null;
        if (part instanceof SQLEditor e) {
            sqlEditor = e;
        } else if (part != null) {
            sqlEditor = part.getAdapter(SQLEditor.class);
        }
        if (sqlEditor == null || sqlEditor.getTextViewer() == null || sqlEditor.getTextViewer().getTextWidget() == null
            || sqlEditor.getTextViewer().getTextWidget().isDisposed()) {
            return null; // редактор закрыт
        }
        return new SqlEditorContext(sqlEditor);
    }

    @SuppressWarnings("rawtypes")
    private static String resolveDefaultSchemaName(DBCExecutionContext ctx) {
        if (ctx == null) {
            return null;
        }
        DBCExecutionContextDefaults defaults = ctx.getContextDefaults();
        if (defaults == null) {
            return null;
        }
        DBSObject schema = defaults.getDefaultSchema();
        DBSObject catalog = defaults.getDefaultCatalog();
        if (schema != null && catalog != null) {
            return catalog.getName() + "." + schema.getName();
        }
        if (schema != null) {
            return schema.getName();
        }
        return catalog == null ? null : catalog.getName();
    }

    public SQLEditor editor() {
        return editor;
    }

    public IDocument document() {
        return editor.getDocument();
    }

    public DBPDataSourceContainer container() {
        return container;
    }

    public DBCExecutionContext executionContext() {
        return executionContext;
    }

    public boolean isConnected() {
        return container != null && container.isConnected() && container.getDataSource() != null;
    }

    public String dialectName() {
        return dialectName;
    }

    public String productName() {
        return productName;
    }

    public String defaultSchemaName() {
        return defaultSchemaName;
    }

    public String queryText() {
        return queryText;
    }

    public int queryOffset() {
        return queryOffset;
    }

    public int queryLength() {
        return queryLength;
    }

    public boolean isFromSelection() {
        return fromSelection;
    }

    public int caretOffset() {
        return caretOffset;
    }

    public String connectionName() {
        return container == null ? null : container.getName();
    }
}
